package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
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
            deadCalls.clear()
        }

        fun activeCount(): Int = activeConnections.size

        /**
         * Calls that were declined/ended before Telecom created their Connection.
         *
         * `addNewIncomingCall` is asynchronous. In the gap between the request and
         * [CallkitConnectionService.onCreateIncomingConnection] the call can already
         * be over — a cancel push, an answer on another device, a second CallKit
         * superseding the first. The decline then runs against a Connection that does
         * not exist yet, [find] returns null, and it disconnects nothing. The
         * Connection is born RINGING moments later with nobody left to kill it.
         *
         * Such a phantom outlives the real call, keeps the Telecom audio focus, and
         * hijacks every OS-level call UI — watch, Android Auto, car kit. Their buttons
         * then drive a call that no longer exists, so none of them appear to work.
         * Observed on a Xiaomi (Android 16): a phantom held the UI for 85 s while the
         * real call had been gone for over a minute.
         */
        private val deadCalls = Collections.synchronizedSet(LinkedHashSet<String>())

        /** Only the narrow race window matters; the cap just stops unbounded growth. */
        private const val MAX_DEAD_CALLS = 64

        /** Records that [callId] ended before its Connection existed. */
        fun markDead(callId: String) {
            if (callId.isEmpty()) return
            synchronized(deadCalls) {
                deadCalls.add(callId)
                while (deadCalls.size > MAX_DEAD_CALLS) {
                    val oldest = deadCalls.iterator().let { if (it.hasNext()) it.next() else null }
                        ?: break
                    deadCalls.remove(oldest)
                }
            }
        }

        /** True if [callId] died during the race window. Consumes the marker. */
        fun consumeDead(callId: String): Boolean = deadCalls.remove(callId)
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

    /**
     * Sends one of the plugin's own broadcasts, exactly as a notification button
     * would. Returns true when the broadcast actually went out.
     */
    private fun dispatchToPlugin(intent: Intent, what: String): Boolean {
        if (!dispatched.add(what)) {
            Log.d(TAG, "$what already dispatched id=$callId — ignoring duplicate")
            return false
        }
        return try {
            appContext.sendBroadcast(intent)
            Log.d(TAG, "$what dispatched to plugin id=$callId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "$what dispatch failed id=$callId: ${e.message}")
            // The broadcast is the only thing that tells the app about this
            // action. If it could not be sent, do not leave Telecom in a state
            // that claims otherwise — fall back to the local-only behaviour.
            dispatched.remove(what)
            fallback(what)
            false
        }
    }

    /**
     * Replays the parts of accepting that only [CallkitIncomingActivity.onAcceptClick]
     * performs, for answers that did not come from that screen.
     *
     * The full-screen incoming call UI has exactly one way of going away: it
     * calls `finish()` on itself when the user taps one of its own buttons. When
     * the answer arrives from Telecom — a watch, Android Auto, a car kit — nobody
     * taps it, so it stays up over the lock screen and keeps ringing while the
     * call is already connected.
     *
     * There is a second, indirect route (the ACCEPT branch starts
     * CallkitNotificationService, whose onStartCommand clears the incoming
     * notification, which broadcasts the dismiss) but it cannot be relied on: it
     * runs through `FlutterCallkitIncomingPlugin.getInstance()`, which is null
     * while the app is asleep, and every call on it is null-safe — so it fails
     * silently exactly in the case that matters. Hence doing it here, directly.
     */
    private fun tearDownIncomingUiAndOpenApp() {
        // Ringtone + incoming notification. Both hang off the plugin instance,
        // which may be null while the app is asleep — the dismiss broadcast
        // below does not depend on it and is sent regardless.
        try {
            val plugin = FlutterCallkitIncomingPlugin.getInstance()
            plugin?.getCallkitSoundPlayerManager()?.stop()
            plugin?.getCallkitNotificationManager()?.clearIncomingNotification(bundle, true)
        } catch (e: Exception) {
            Log.w(TAG, "clearing incoming notification failed id=$callId: ${e.message}")
        }
        // Dismiss the full-screen Activity. Harmless if the line above already
        // sent this same broadcast — the Activity ignores it once finishing.
        try {
            appContext.sendBroadcast(CallkitIncomingActivity.getIntentEnded(appContext, true))
        } catch (e: Exception) {
            Log.w(TAG, "dismissing incoming activity failed id=$callId: ${e.message}")
        }
        // Bring the app up on the connected call, like a normal answer does.
        // Launched from a self-managed Connection that Telecom still considers
        // RINGING, which is what earns the PHONE_CALL exemption to the
        // background-activity-launch restrictions of Android 12+.
        try {
            val intent = AppUtils.getAppIntent(
                appContext,
                CallkitConstants.ACTION_CALL_ACCEPT,
                bundle,
            )
            if (intent == null) {
                Log.w(TAG, "no launch intent for package — app not opened id=$callId")
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
                Log.d(TAG, "app opened on active call id=$callId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "opening app failed id=$callId: ${e.message}")
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
        val sent = dispatchToPlugin(
            CallkitIncomingBroadcastReceiver.getIntentAccept(appContext, bundle),
            "onAnswer",
        )
        // Only for accept: the DECLINE and ENDED branches of the receiver already
        // call clearIncomingNotification themselves, ACCEPT is the one that does not.
        if (sent) tearDownIncomingUiAndOpenApp()
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

    // -------------------------------------------------------------------------
    // Contrôles venus d'une surface distante (Android Auto, kit Bluetooth,
    // montre, bouton au volant).
    //
    // Ces trois callbacks manquaient. Leurs capacités étaient pourtant déjà
    // déclarées (CAPABILITY_MUTE, CAPABILITY_SUPPORT_HOLD) : la voiture
    // affichait donc les boutons, les allumait au clic, et rien n'atteignait
    // jamais la pile SIP. Le correspondant continuait d'entendre pendant que
    // l'écran voiture affichait « muet » ou « en attente ».
    //
    // Le plugin définit déjà les événements correspondants côté Dart — iOS les
    // émet depuis CallKit. On émet ici exactement les mêmes, pour que le Dart
    // de l'app n'ait qu'un seul contrat à connaître, quelle que soit la
    // plateforme.
    // -------------------------------------------------------------------------

    override fun onHold() {
        super.onHold()
        Log.d(TAG, "onHold id=$callId")
        emitToggleHold(true)
        setOnHold()
    }

    override fun onUnhold() {
        super.onUnhold()
        Log.d(TAG, "onUnhold id=$callId")
        emitToggleHold(false)
        setActive()
    }

    /**
     * Mute demandé depuis une surface distante (API < 34).
     *
     * Déprécié en API 34 au profit de [onMuteStateChanged], mais toujours
     * appelé sur les versions antérieures : les deux sont implémentés, et
     * [emitToggleMute] déduplique.
     */
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        super.onCallAudioStateChanged(state)
        if (state == null) return
        emitToggleMute(state.isMuted)
    }

    /** Mute demandé depuis une surface distante (API 34+). */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onMuteStateChanged(isMuted: Boolean) {
        super.onMuteStateChanged(isMuted)
        emitToggleMute(isMuted)
    }

    /** Touche du clavier DTMF pressée sur l'écran voiture. */
    override fun onPlayDtmfTone(c: Char) {
        super.onPlayDtmfTone(c)
        Log.d(TAG, "onPlayDtmfTone id=$callId digit=$c")
        FlutterCallkitIncomingPlugin.sendEvent(
            CallkitConstants.ACTION_CALL_TOGGLE_DMTF,
            mapOf(
                "id" to callId,
                "digits" to c.toString(),
                // Le mapper Dart n'accepte que ces libellés (toDTMFActionType).
                // Telecom ne transmet qu'une touche à la fois : singleTone.
                "type" to "singleTone",
            ),
        )
    }

    /**
     * Dernier état de mute annoncé à Dart.
     *
     * Sur API 34+, onCallAudioStateChanged (déprécié) et onMuteStateChanged
     * peuvent tous deux se déclencher pour une même action utilisateur. Sans
     * ce garde, l'app recevrait l'événement en double.
     */
    private var lastMutedSent: Boolean? = null

    private fun emitToggleMute(isMuted: Boolean) {
        if (lastMutedSent == isMuted) return
        lastMutedSent = isMuted
        Log.d(TAG, "toggleMute id=$callId isMuted=$isMuted")
        FlutterCallkitIncomingPlugin.sendEvent(
            CallkitConstants.ACTION_CALL_TOGGLE_MUTE,
            mapOf("id" to callId, "isMuted" to isMuted),
        )
    }

    private fun emitToggleHold(isOnHold: Boolean) {
        FlutterCallkitIncomingPlugin.sendEvent(
            CallkitConstants.ACTION_CALL_TOGGLE_HOLD,
            mapOf("id" to callId, "isOnHold" to isOnHold),
        )
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
