package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-managed Telecom [Connection] implementation for flutter_callkit_incoming.
 *
 * Hosts the call in the Android Telecom framework with `PROPERTY_SELF_MANAGED` so
 * the OS treats it as a first-party phone call — granting it keyguard-bypass /
 * full-screen-intent priority on strict OEMs (Samsung Knox, Xiaomi, etc.) that
 * otherwise ignore [android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED]
 * on ordinary Activities.
 *
 * Lifecycle:
 *  1. [CallkitConnectionService.onCreateIncomingConnection] returns a
 *     ringing Connection instance.
 *  2. User taps Accept in our notification → plugin's BroadcastReceiver maps the
 *     broadcast to the matching connection via [find] and calls [setActive].
 *  3. User taps Decline / End → [setDisconnected] + [destroy] → unregister.
 *
 * Connection lookup uses a process-wide [ConcurrentHashMap] keyed by the call id
 * (the plugin's existing `Data.id` field). The OS holds the Connection object in
 * the Telecom framework; we keep a parallel reference so our BroadcastReceiver
 * can drive state transitions.
 */
@RequiresApi(Build.VERSION_CODES.M)
class CallkitConnection(
    val callId: String,
    val bundle: Bundle,
    private val appContext: Context,
) : Connection() {

    companion object {
        private const val TAG = "CallkitConnection"

        /** Bundle key — pass the full call Data bundle through Telecom extras. */
        const val EXTRA_CALL_BUNDLE = "com.hiennv.flutter_callkit_incoming.CALL_BUNDLE"

        private val activeConnections = ConcurrentHashMap<String, CallkitConnection>()

        fun find(callId: String): CallkitConnection? = activeConnections[callId]

        fun register(callId: String, conn: CallkitConnection) {
            activeConnections[callId] = conn
        }

        fun unregister(callId: String) {
            activeConnections.remove(callId)
        }

        /** For testing / cleanup — release all refs (Connection objects already destroyed by OS). */
        fun clearAll() {
            activeConnections.clear()
        }

        fun activeCount(): Int = activeConnections.size
    }

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD
        register(callId, this)
        Log.d(TAG, "Connection created id=$callId active=${activeCount()}")
    }

    // -------------------------------------------------------------------------
    // Telecom → app lifecycle callbacks
    //
    // These fire when the user interacts with an OS-level call UI: Android Auto's
    // generic incoming-call screen, a Bluetooth car kit, the steering-wheel
    // answer button, a paired watch's system call UI, the system dialer.
    //
    // These paths MUST end up doing the same work as our own notification
    // buttons. Answering an OS-level call is not just a Telecom state change —
    // the app has to be told, so it can answer the underlying VoIP call.
    //
    // Previously these overrides only moved the Telecom state (setActive /
    // setDisconnected) and never notified the app. Symptom: the call rang in the
    // car, "Answer" flipped the car UI to "in call", and nothing else happened —
    // the VoIP INVITE was never answered. Same hole for reject/disconnect, which
    // left the VoIP call alive after hanging up from the car.
    //
    // Fix: re-dispatch to the very same broadcast the notification buttons send,
    // so there is ONE accept/decline/end pipeline instead of three, of which two
    // were dead ends. CallkitIncomingBroadcastReceiver then does the full job:
    // drive this Connection (markAccepted/markDeclined/markEnded), notify Dart
    // (sendEventFlutter), persist isAccepted for cold-start recovery (addCall),
    // and bring the app up (acceptCallHandleCallback).
    // -------------------------------------------------------------------------

    /**
     * OS actions already re-dispatched to the plugin, to swallow duplicates.
     *
     * The receiver's branches call back into [markAccepted]/[markDeclined]/
     * [markEnded]; those only move Telecom state and never re-trigger these
     * overrides, so there is no loop — but a double tap (or an OS retry) would
     * emit the event to Dart twice, which app-side accept handlers typically
     * read as "accept, then immediately end".
     *
     * Keyed per action rather than a single flag: answering from the car and
     * *then* hanging up from the car is a normal sequence, and the second
     * action must get through.
     */
    private val dispatched = Collections.synchronizedSet(mutableSetOf<String>())

    /** Sends one of the plugin's own broadcasts, exactly as a notification button would. */
    private fun dispatchToPlugin(intent: Intent, what: String) {
        if (!dispatched.add(what)) {
            Log.d(TAG, "$what already dispatched id=$callId — ignoring duplicate")
            return
        }
        try {
            appContext.sendBroadcast(intent)
            Log.d(TAG, "$what dispatched to plugin id=$callId")
        } catch (e: Exception) {
            Log.w(TAG, "$what dispatch failed id=$callId: ${e.message}")
            // The broadcast is the only thing that tells the app about this
            // action. If it could not be sent, do not leave Telecom in a state
            // that claims otherwise — fall back to the local-only behaviour.
            dispatched.remove(what)
            fallback(what)
        }
    }

    private fun fallback(what: String) {
        when (what) {
            "onAnswer" -> setActive()
            "onReject" -> finishWithCause(DisconnectCause.REJECTED)
            else -> finishWithCause(DisconnectCause.LOCAL)
        }
    }

    override fun onAnswer() {
        super.onAnswer()
        Log.d(TAG, "onAnswer id=$callId")
        dispatchToPlugin(
            CallkitIncomingBroadcastReceiver.getIntentAccept(appContext, bundle),
            "onAnswer",
        )
    }

    override fun onReject() {
        super.onReject()
        Log.d(TAG, "onReject id=$callId")
        dispatchToPlugin(
            CallkitIncomingBroadcastReceiver.getIntentDecline(appContext, bundle),
            "onReject",
        )
    }

    override fun onDisconnect() {
        super.onDisconnect()
        Log.d(TAG, "onDisconnect id=$callId")
        dispatchToPlugin(
            CallkitIncomingBroadcastReceiver.getIntentEnded(appContext, bundle),
            "onDisconnect",
        )
    }

    override fun onAbort() {
        super.onAbort()
        Log.d(TAG, "onAbort id=$callId")
        finishWithCause(DisconnectCause.UNKNOWN)
    }

    override fun onHold() {
        super.onHold()
        setOnHold()
    }

    override fun onUnhold() {
        super.onUnhold()
        setActive()
    }

    // -------------------------------------------------------------------------
    // App → Telecom driving helpers (invoked by the plugin's BroadcastReceiver)
    // -------------------------------------------------------------------------

    /** Mark the call as answered — user accepted via app notification. */
    fun markAccepted() {
        Log.d(TAG, "markAccepted id=$callId")
        // Already accepted app-side: an OS-level answer arriving afterwards must
        // not re-enter the broadcast pipeline (see [dispatched]).
        dispatched.add("onAnswer")
        setActive()
    }

    /**
     * Mark the call as declined/ended — user declined via app notification.
     *
     * Cold-launch DECLINE recovery is fired
     * from inside the self-managed [Connection] context (and *before*
     * [setDisconnected] runs) so the call is still in the RINGING state when
     * [Context.startActivity] is invoked. The hope: Android 14+ BAL grants a
     * PHONE_CALL exemption for active self-managed Telecom calls. Previous
     * placement inside the BroadcastReceiver hit BAL_BLOCK with
     * `callingUidProcState: BOUND_FOREGROUND_SERVICE; callingUidHasVisibleActivity: false`
     * (Galaxy logcat 2026-05-05 03:49:04.039).
     *
     * If this position still BAL_BLOCK's, the next escalation is to promote
     * `CallkitConnectionService` to a `foregroundServiceType="phoneCall"`
     * FGS for the lifetime of the connection.
     */
    fun markDeclined(context: Context) {
        Log.d(TAG, "markDeclined id=$callId")
        dispatched.add("onReject")
        triggerDeclineRecovery(context)
        // [H' fix 2026-05-05 v2] Delay finishWithCause so the self-managed
        // call stays RINGING long enough for:
        //   1. MainActivity to bootstrap the main FlutterEngine (~100ms).
        //   2. SealedSender service to cold-init (secure_storage open +
        //      JWT load + server verify-key fetch — ~1~2s, network-bound).
        //   3. callSignalingProvider → callServiceProvider → callProvider
        //      to rebuild from _IdleCallNotifier into the real CallNotifier
        //      whose ctor finally fires _consumePendingVoipDecline.
        // The 1500ms first attempt was insufficient (logcat showed Activity
        // booted + Dart plugins ran, but real CallNotifier ctor never reached
        // before delay expired and Activity was killed).
        // Trade-off: Telecom phone state stays RINGING for ~3s after decline.
        // The notification is already dismissed by the BroadcastReceiver
        // path, so the user sees no UI artifact — only the system-level
        // call state lingers briefly while Dart finishes wiring.
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "[H' fix] delayed finishWithCause id=$callId")
            finishWithCause(DisconnectCause.REJECTED)
        }, 3000L)
    }

    /**
     * Persist declined nonce and start MainActivity. Idempotent — safe even
     * if the BroadcastReceiver fallback also fires (single-task launch flag,
     * one-shot consumePending).
     */
    private fun triggerDeclineRecovery(context: Context) {
        try {
            val prefs = context.getSharedPreferences(
                "flutter_callkit_incoming_decline",
                Context.MODE_PRIVATE,
            )
            prefs.edit()
                .putString("pending_nonce", callId)
                .putLong("pending_at", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "[DIAG-DECLINE-CS] prefs written nonce=$callId")
            val launchIntent = AppUtils.getAppIntent(context, null, null)
            launchIntent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.d(TAG, "[DIAG-DECLINE-CS] startActivity dispatched (CS ctx, RINGING)")
            } else {
                Log.w(TAG, "[DIAG-DECLINE-CS] launchIntent null — recovery skipped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DIAG-DECLINE-CS] recovery failed: ${e.message}")
        }
    }

    /** Mark the call as terminated — call ended (either side hung up). */
    fun markEnded() {
        Log.d(TAG, "markEnded id=$callId")
        dispatched.add("onDisconnect")
        finishWithCause(DisconnectCause.LOCAL)
    }

    /** Mark the call as missed — timeout without answer. */
    fun markMissed() {
        Log.d(TAG, "markMissed id=$callId")
        finishWithCause(DisconnectCause.MISSED)
    }

    private fun finishWithCause(cause: Int) {
        try {
            setDisconnected(DisconnectCause(cause))
        } catch (e: Exception) {
            Log.w(TAG, "setDisconnected failed: ${e.message}")
        }
        unregister(callId)
        try {
            destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed: ${e.message}")
        }
    }
}
