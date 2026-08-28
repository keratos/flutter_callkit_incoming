package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import com.hiennv.flutter_callkit_incoming.Utils.Companion.reapCollection
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.lang.ref.WeakReference


/** FlutterCallkitIncomingPlugin */
@SuppressLint("LongLogTag")
class FlutterCallkitIncomingPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {
    companion object {

        const val EXTRA_CALLKIT_CALL_DATA = "EXTRA_CALLKIT_CALL_DATA"

        const val TAG = "FlutterCallkitIncomingPlugin"

        @SuppressLint("StaticFieldLeak")
        private lateinit var instance: FlutterCallkitIncomingPlugin

        fun getInstance(): FlutterCallkitIncomingPlugin? {
            if (hasInstance()) {
                return instance
            }
            return null
        }

        fun hasInstance(): Boolean {
            return ::instance.isInitialized
        }

        private val methodChannels = mutableMapOf<BinaryMessenger, MethodChannel>()
        private val eventChannels = mutableMapOf<BinaryMessenger, EventChannel>()
        private val eventHandlers = mutableMapOf<BinaryMessenger, EventCallbackHandler>()
        private val eventCallbacks = mutableListOf<WeakReference<CallkitEventCallback>>()

        fun sendEvent(event: String, body: Map<String, Any?>) {
            send(event, body)
        }

        fun sendEventCustom(event: String, body: Map<String, Any>) {
            send(event, body)
        }

        fun acceptCallHandleCallback(bundle: Bundle) {
            Handler(Looper.getMainLooper()).postDelayed({
                val extra = bundle.getSerializable(CallkitConstants.EXTRA_CALLKIT_EXTRA)
                methodChannels.values.forEach {
                    it.invokeMethod("acceptCallHandle", extra)
                }
            }, 750)
        }

        fun invokeFlutterCallback(data: Any) {
            Handler(Looper.getMainLooper()).postDelayed({
                methodChannels.values.forEach {
                    it.invokeMethod("invokeFlutter", data)
                }
            }, 750)
        }

        /**
         * Send event to Flutter UI if there are active handlers, otherwise send to background
         * executor if registered.
         */
        private fun send(event: String, body: Map<String, Any?>) {
            val uiHandlers = eventHandlers.values.filter { it.hasListener() }
            if (uiHandlers.isNotEmpty()) {
                Log.d(TAG, "Sending UI event: $event")
                uiHandlers.forEach { it.send(event, body) }
            } else if (CallkitBackgroundExecutor.registered) {
                Log.d(TAG, "Sending background event: $event (no UI handlers)")
                CallkitBackgroundExecutor.send(event, body)
            }
        }

        /**
         * Register a callback to receive call events (accept/decline) natively.
         * This allows other plugins/services to handle call events
         * even when Flutter engine is terminated.
         */
        fun registerEventCallback(callback: CallkitEventCallback) {
            eventCallbacks.add(WeakReference(callback))
        }

        /**
         * Unregister an event callback.
         */
        fun unregisterEventCallback(callback: CallkitEventCallback) {
            eventCallbacks.removeAll { it.get() == callback || it.get() == null }
        }

        /**
         * Notify all registered event callbacks.
         * Called internally when a call event occurs.
         */
        internal fun notifyEventCallbacks(event: CallkitEventCallback.CallEvent, callData: android.os.Bundle) {
            eventCallbacks.reapCollection().forEach { callbackRef ->
                callbackRef.get()?.onCallEvent(event, callData)
            }
        }


        fun sharePluginWithRegister(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
            initSharedInstance(
                flutterPluginBinding.applicationContext,
                flutterPluginBinding.binaryMessenger
            )
        }

        fun initSharedInstance(context: Context, binaryMessenger: BinaryMessenger) {
            if (!::instance.isInitialized) {
                instance = FlutterCallkitIncomingPlugin()
            }
            // Restore instance fields that may have been nulled during a previous
            // detach. The static `instance` survives across FlutterEngine teardown
            // when the host process is kept alive (e.g. foreground services),
            // so `onAttachedToEngine` must refresh these fields every time;
            // otherwise background-isolate calls end up as silent no-ops.
            if (instance.callkitSoundPlayerManager == null) {
                instance.callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
            }
            if (instance.callkitNotificationManager == null) {
                instance.callkitNotificationManager = CallkitNotificationManager(context, instance.callkitSoundPlayerManager)
            }
            if (instance.context == null) {
                instance.context = context
            } else {
                // Re-initialize managers if they were destroyed but instance still exists
                if (instance.callkitNotificationManager == null) {
                    instance.callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
                    instance.callkitNotificationManager = CallkitNotificationManager(context, instance.callkitSoundPlayerManager)
                }
            }

            val channel = MethodChannel(binaryMessenger, "flutter_callkit_incoming")
            methodChannels[binaryMessenger] = channel
            channel.setMethodCallHandler(instance)

            val events = EventChannel(binaryMessenger, "flutter_callkit_incoming_events")
            eventChannels[binaryMessenger] = events
            val handler = EventCallbackHandler()
            eventHandlers[binaryMessenger] = handler
            events.setStreamHandler(handler)

        }

    }

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private var activity: Activity? = null
    private var context: Context? = null
    private var callkitNotificationManager: CallkitNotificationManager? = null
    private var callkitSoundPlayerManager: CallkitSoundPlayerManager? = null

    fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return callkitNotificationManager
    }

    fun getCallkitSoundPlayerManager(): CallkitSoundPlayerManager? {
        return callkitSoundPlayerManager
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        sharePluginWithRegister(flutterPluginBinding)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            InAppCallManager(flutterPluginBinding.applicationContext).registerPhoneAccount()
        }
    }

    public fun showIncomingNotification(data: Data) {
        data.from = "notification"
        //send BroadcastReceiver
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentIncoming(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun showMissCallNotification(data: Data) {
        callkitNotificationManager?.showMissCallNotification(data.toBundle())
    }

    public fun startCall(data: Data) {
        val ctx = requireNotNull(context)
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentStart(ctx, data.toBundle())
        )
        placeTelecomCall(ctx, data)
    }

    /**
     * Enregistre l'appel sortant auprès du framework Telecom.
     *
     * Sans ça, un appel sortant n'a aucune existence système : les surfaces
     * distantes (Android Auto, kit Bluetooth, montre) n'affichent rien pendant
     * toute sa durée — pas même un bouton raccrocher. Le chemin entrant, lui,
     * passait déjà par addNewIncomingCall.
     *
     * L'échec n'est jamais propagé : un appel SIP qui fonctionne ne doit pas
     * être interrompu parce que sa représentation Telecom a échoué.
     */
    private fun placeTelecomCall(ctx: Context, data: Data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Même pré-vérification que le chemin entrant
        // (CallkitIncomingBroadcastReceiver.registerTelecomIncomingCall) : sans
        // elle, une permission révoquée ne se lisait qu'en SecurityException
        // attrapée plus bas, avec un message d'OEM peu exploitable au logcat —
        // alors que c'est LA cause d'un écran voiture resté vide.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.MANAGE_OWN_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "MANAGE_OWN_CALLS not granted — Telecom outgoing skipped id=${data.id}")
            return
        }
        try {
            val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handle = data.handle.ifEmpty { data.id }
            val callBundle = data.toBundle()
            val extras = Bundle().apply {
                putParcelable(
                    TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                    InAppCallManager(ctx).getPhoneAccountHandle(),
                )
                putBundle(
                    TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
                    Bundle().apply {
                        putBundle(CallkitConnection.EXTRA_CALL_BUNDLE, callBundle)
                    },
                )
            }
            telecomManager.placeCall(Uri.fromParts("tel", handle, null), extras)
            Log.d(TAG, "placeCall dispatched id=${data.id}")
        } catch (e: Exception) {
            // SecurityException (MANAGE_OWN_CALLS révoquée), PhoneAccount non
            // enregistré, OEM récalcitrant : l'appel SIP continue sans présence
            // en voiture.
            Log.w(TAG, "placeCall failed id=${data.id}: ${e.message}")
        }
    }

    public fun endCall(data: Data) {
        context?.sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentEnded(
                requireNotNull(context),
                data.toBundle()
            )
        )
    }

    public fun endAllCalls() {
        val calls = getDataActiveCalls(context)
        calls.forEach {
            context?.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentEnded(
                    requireNotNull(context),
                    it.toBundle()
                )
            )
        }
        removeAllCalls(context)
    }

    fun sendEventCustom(body: Map<String, Any>) {
        send(CallkitConstants.ACTION_CALL_CUSTOM, body)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        try {
            when (call.method) {
                "registerBackgroundHandler" -> {
                    val args = call.arguments as Map<*, *>
                    val pluginHandle = (args["pluginHandle"] as Number).toLong()
                    val userHandle = (args["userHandle"] as Number).toLong()
                    addBackgroundCallback(context, pluginHandle, userHandle)
                    CallkitBackgroundExecutor.start(requireNotNull(context), pluginHandle)
                    result.success(null)
                }

                "getBackgroundHandler" -> {
                    val handle = getUserCallback(context)
                    result.success(handle)
                }

                "setAcceptCallHandle" -> {
                    val args = call.arguments as? List<*>
                    if (args != null && args.size >= 2) {
                        val handle = args[0] as? Int ?: 0
                        val key = args[1] as? String ?: ""
                        saveHandle(context, key, handle)
                    }
                    result.success(null)
                }

                "showCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    //send BroadcastReceiver
                    context?.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentIncoming(
                            requireNotNull(context),
                            data.toBundle()
                        )
                    )

                    result.success(true)
                }

                "showCallkitIncomingSilently" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"

                    result.success(true)
                }

                "showMissCallNotification" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    callkitNotificationManager?.showMissCallNotification(data.toBundle())
                    result.success(true)
                }

                // Délègue au helper [startCall] plutôt que de rejouer son
                // broadcast en ligne : c'est LUI qui enregistre l'appel sortant
                // auprès de Telecom (placeTelecomCall). La duplication du seul
                // broadcast laissait tout le volet « appel sortant en voiture »
                // sans le moindre appelant — du code qui compilait
                // parfaitement et ne s'exécutait jamais. Un seul broadcast est
                // émis : le helper envoie déjà getIntentStart.
                "startCall" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    startCall(data)

                    result.success(true)
                }

                "muteCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    sendEvent(CallkitConstants.ACTION_CALL_TOGGLE_MUTE, map)

                    result.success(true)
                }

                "holdCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    sendEvent(CallkitConstants.ACTION_CALL_TOGGLE_HOLD, map)

                    result.success(true)
                }

                "isMuted" -> {
                    result.success(true)
                }

                // Accept a call programmatically, exactly as tapping "Accept" on the
                // plugin's own notification does. Callable from a background Dart
                // isolate — needed by companion surfaces (a paired watch's own
                // notification actions) whose button press is delivered to the
                // background isolate and can therefore not reach the app's
                // foreground call handling on its own.
                "acceptCall" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    val currentCall = calls.firstOrNull { it.id == data.id }
                    if (currentCall != null && context != null) {
                        context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentAccept(
                                requireNotNull(context),
                                currentCall.toBundle()
                            )
                        )
                        result.success(true)
                    } else {
                        // No such active call (already ended, or the id is stale).
                        result.success(false)
                    }
                }

                "endCall" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    val currentCall = calls.firstOrNull { it.id == data.id }
                    if (currentCall != null && context != null) {
                        if(currentCall.isAccepted) {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context),
                                    currentCall.toBundle()
                                )
                            )
                        }else {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context),
                                    currentCall.toBundle()
                                )
                            )
                        }
                    } else if (context != null) {
                        // Appel absent d'ACTIVE_CALLS : ne rien faire laissait
                        // une entrée orpheline pour toujours. L'entrée n'est
                        // écrite que par la branche ACTION_CALL_START du
                        // receiver, donc par un broadcast ASYNCHRONE : sur un
                        // appel sortant qui échoue tout de suite (micro refusé,
                        // 404 immédiat), `endCall(id)` s'exécute avant cette
                        // écriture, ne trouve rien, et aucun ENDED n'est jamais
                        // diffusé — donc jamais de removeCall. L'entrée restait
                        // en SharedPreferences avec isAccepted = true, et au
                        // démarrage suivant le prochain appel entrant était
                        // décroché tout seul.
                        //
                        // Diffuser ENDED quand même fait partir le nettoyage
                        // quoi qu'il arrive : les broadcasts vers ce receiver
                        // sont délivrés dans l'ordre d'émission, donc le START
                        // encore en vol est traité d'abord, puis ce ENDED
                        // supprime l'entrée qu'il vient d'écrire.
                        context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentEnded(
                                requireNotNull(context),
                                data.toBundle()
                            )
                        )
                    }
                    result.success(true)
                }

                "callConnected" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    val currentCall = calls.firstOrNull { it.id == data.id }
                    if (currentCall != null && context != null) {
                        context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentConnected(
                                requireNotNull(context),
                                currentCall.toBundle()
                            )
                        )
                    }
                    result.success(true)
                }

                "endAllCalls" -> {
                    val calls = getDataActiveCalls(context)
                    calls.forEach {
                        if (it.isAccepted) {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context),
                                    it.toBundle()
                                )
                            )
                        } else {
                            context?.sendBroadcast(
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context),
                                    it.toBundle()
                                )
                            )
                        }
                    }
                    removeAllCalls(context)
                    result.success(true)
                }

                "activeCalls" -> {
                    result.success(getDataActiveCallsForFlutter(context))
                }

                "getDevicePushTokenVoIP" -> {
                    result.success("")
                }

                "silenceEvents" -> {
                    val silence = call.arguments as? Boolean ?: false
                    CallkitIncomingBroadcastReceiver.silenceEvents = silence
                    result.success(true)
                }

                "requestNotificationPermission" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    callkitNotificationManager?.requestNotificationPermission(activity, map)
                    result.success(true)
                }

                "requestFullIntentPermission" -> {
                    callkitNotificationManager?.requestFullIntentPermission(activity)
                    result.success(true)
                }

                "canUseFullScreenIntent" -> {
                    result.success(callkitNotificationManager?.canUseFullScreenIntent() ?: true)
                }

                // EDIT - clear the incoming notification/ring (after accept/decline/timeout)
                "hideCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    callkitSoundPlayerManager?.stop()
                    callkitNotificationManager?.clearIncomingNotification(data.toBundle(), false)
                    result.success(true)
                }

                "endNativeSubsystemOnly" -> {
                    result.success(true)
                }

                "setAudioRoute" -> {
                    result.success(true)
                }
            }
        } catch (error: Exception) {
            result.error("error", error.message, "")
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannels.remove(binding.binaryMessenger)?.setMethodCallHandler(null)
        eventChannels.remove(binding.binaryMessenger)?.setStreamHandler(null)
        eventHandlers.remove(binding.binaryMessenger)

        // Only destroy and null the shared managers when the LAST engine detaches.
        // When multiple engines are attached (e.g. main UI engine + FCM background
        // isolate engine), tearing down the main engine must not pull the managers
        // out from under the background isolate that still needs them.
        if (methodChannels.isEmpty() && eventChannels.isEmpty()) {
            instance.callkitSoundPlayerManager?.destroy()
            instance.callkitNotificationManager?.destroy()
            instance.callkitSoundPlayerManager = null
            instance.callkitNotificationManager = null
        }
        Log.d(TAG, "onDetachedFromEngine")
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        // Keep instance.context alive. It is the applicationContext, shared by
        // every engine attachment and safe to hold for the lifetime of the JVM.
        // Nulling it here would break background-isolate method channel calls
        // (showCallkitIncoming relies on `context?.sendBroadcast(...)`) whenever
        // the activity is destroyed while a cached background engine keeps the
        // static `instance` alive.
        instance.activity = null
    }

    class EventCallbackHandler : EventChannel.StreamHandler {

        @Volatile
        private var eventSink: EventChannel.EventSink? = null

        fun hasListener(): Boolean = eventSink != null

        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            eventSink = sink
        }

        fun send(event: String, body: Map<String, Any?>) {
            val data = mapOf(
                "event" to event,
                "body" to body
            )
            Handler(Looper.getMainLooper()).post {
                eventSink?.success(data)
            }
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        instance.callkitNotificationManager?.onRequestPermissionsResult(
            instance.activity,
            requestCode,
            grantResults
        )
        return true
    }


}
