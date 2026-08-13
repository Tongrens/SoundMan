package hk.uwu.soundman.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import hk.uwu.soundman.R
import hk.uwu.soundman.ipc.PreferredDeviceSync
import hk.uwu.soundman.ipc.SoundManHostBridgeClient
import hk.uwu.soundman.ipc.SoundManProtocol
import hk.uwu.soundman.model.AdjustableApp
import hk.uwu.soundman.model.OutputTarget
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

private const val TAG = "SoundManHostApps"

enum class ActiveMediaAppsError { HOST_UNAVAILABLE }

sealed interface ActiveMediaAppsState {
    data class Available(val apps: List<AdjustableApp>) : ActiveMediaAppsState
    data class Error(val reason: ActiveMediaAppsError) : ActiveMediaAppsState
}

data class HostCommandResult(
    val commandId: String,
    val uid: Int,
    val success: Boolean,
    val resultCode: Int,
    val effectiveTarget: OutputTarget?,
)

interface ActiveMediaAppsSource {
    fun observe(observer: (ActiveMediaAppsState) -> Unit): () -> Unit
}

/** App 端 Binder 客户端；业务快照与命令结果只通过 host callback 发布。 */
class HostPlaybackSource(
    context: Context,
    private val ruleStore: RuleStore,
    private val installedAppsAccess: InstalledAppsAccess,
) : ActiveMediaAppsSource, AutoCloseable {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val observers = CopyOnWriteArraySet<(ActiveMediaAppsState) -> Unit>()
    private val resultObservers = CopyOnWriteArraySet<(HostCommandResult) -> Unit>()
    private val deviceObservers = CopyOnWriteArraySet<(AudioDeviceScan) -> Unit>()
    private val worker = HandlerThread("SoundMan.AppIpc").apply { start() }
    private val handler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SoundMan.ConnectWait").apply { isDaemon = true }
    }

    @Volatile
    private var state: ActiveMediaAppsState = ActiveMediaAppsState.Available(emptyList())

    @Volatile
    private var deviceScan = AudioDeviceScan(emptyList(), AudioDeviceScanError.HOST_UNAVAILABLE)

    @Volatile
    private var closed = false

    private var sessionInitialized = false
    private var reconnectAttempt = 0
    private var reconnectScheduled = false

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        if (closed || sessionInitialized) return@Runnable
        Log.i(TAG, "Attempting scheduled SoundMan host reconnect")
        connectThenOnWorker("scheduled reconnect failed")
    }

    private val snapshotWatchdog = Runnable {
        if (closed || sessionInitialized) return@Runnable
        Log.e(TAG, "Host handshake produced no snapshot; dropping session and reconnecting")
        sessionInitialized = false
        bridge.resetSession()
        publishUnavailable()
        scheduleReconnect("snapshot watchdog")
    }

    private val bridge = SoundManHostBridgeClient(
        context = applicationContext,
        handshakeHandler = handler,
        eventListener = { event -> postToWorker("host event") { handleEvent(event) } },
        unavailableListener = { reason ->
            postToWorker("host unavailable") {
                sessionInitialized = false
                Log.e(TAG, "SoundMan host unavailable: $reason")
                publishUnavailable()
                scheduleReconnect("host unavailable: $reason")
            }
        },
    )

    init {
        handler.post {
            if (closed) return@post
            connectThenOnWorker("initial connection failed")
        }
    }

    override fun observe(observer: (ActiveMediaAppsState) -> Unit): () -> Unit {
        observers += observer
        observer(state)
        return { observers -= observer }
    }

    fun currentDeviceScan(): AudioDeviceScan = deviceScan

    fun observeDevices(observer: (AudioDeviceScan) -> Unit): () -> Unit {
        deviceObservers += observer
        observer(deviceScan)
        return { deviceObservers -= observer }
    }

    fun observeResults(observer: (HostCommandResult) -> Unit): () -> Unit {
        resultObservers += observer
        return { resultObservers -= observer }
    }

    fun replaceRules(): String = enqueueCommand("replaceRules") { commandId ->
        sendRules(commandId)
    }

    fun setVolume(uid: Int, percent: Int): String {
        require(uid >= 0) { "uid must be non-negative" }
        require(percent in 0..100) { "percent must be in 0..100" }
        return enqueueCommand("setVolume") { commandId ->
            bridge.setVolume(commandId, uid, percent)
        }
    }

    fun setRoute(uid: Int, target: OutputTarget): String {
        require(uid >= 0) { "uid must be non-negative" }
        return enqueueCommand("setRoute") { commandId ->
            bridge.setRoute(commandId, uid, target)
        }
    }

    private fun enqueueCommand(operation: String, command: (String) -> Unit): String {
        check(!closed) { "HostPlaybackSource is closed" }
        val commandId = UUID.randomUUID().toString()
        check(handler.post {
            if (closed) return@post
            cancelScheduledReconnect()
            if (bridge.isConnected() && sessionInitialized) {
                runCommand(operation, commandId, command)
                return@post
            }
            connectThenOnWorker("command connection failed: $operation") {
                runCommand(operation, commandId, command)
            }
        }) { "HostPlaybackSource worker rejected $operation" }
        return commandId
    }

    private fun connectThenOnWorker(failureReason: String, onConnected: () -> Unit = {}) {
        connectExecutor.execute {
            val connected = try {
                bridge.connect()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to connect to SoundMan host", error)
                false
            }
            handler.post {
                if (closed) return@post
                if (!connected || !initializeSessionIfNeeded()) {
                    if (failureReason.startsWith("command connection failed:")) {
                        Log.e(TAG, "Host command could not connect: ${failureReason.removePrefix("command connection failed: ")}")
                    }
                    publishUnavailable()
                    scheduleReconnect(failureReason)
                    return@post
                }
                onConnected()
            }
        }
    }

    private fun initializeSessionIfNeeded(): Boolean {
        try {
            if (!bridge.isConnected()) {
                sessionInitialized = false
                Log.e(TAG, "Unable to connect to SoundMan host")
                return false
            }
            if (!sessionInitialized) {
                sendRules(UUID.randomUUID().toString())
                bridge.requestSnapshot(UUID.randomUUID().toString())
                armSnapshotWatchdog()
                try {
                    PreferredDeviceSync.publishAll(applicationContext, ruleStore.readAll().values)
                } catch (error: Throwable) {
                    Log.e(TAG, "Failed to republish preferred device rules", error)
                }
            }
            return true
        } catch (error: RuntimeException) {
            sessionInitialized = false
            Log.e(TAG, "Unable to initialize SoundMan host session", error)
            return false
        }
    }

    private fun runCommand(operation: String, commandId: String, command: (String) -> Unit) {
        try {
            command(commandId)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Host command failed: $operation", error)
            sessionInitialized = false
            publishUnavailable()
            scheduleReconnect("host command failed: $operation")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (closed || sessionInitialized || reconnectScheduled) return
        val attempt = reconnectAttempt++
        val delayMs = (RECONNECT_BASE_DELAY_MS shl attempt.coerceAtMost(RECONNECT_MAX_SHIFT)).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectScheduled = true
        Log.w(TAG, "Scheduling SoundMan host reconnect attempt=${attempt + 1} delayMs=$delayMs reason=$reason")
        if (!handler.postDelayed(reconnectRunnable, delayMs)) {
            reconnectScheduled = false
            Log.e(TAG, "Unable to schedule SoundMan host reconnect")
        }
    }

    private fun armSnapshotWatchdog() {
        handler.removeCallbacks(snapshotWatchdog)
        if (!handler.postDelayed(snapshotWatchdog, SNAPSHOT_WATCHDOG_MS)) {
            Log.e(TAG, "Unable to arm snapshot watchdog")
        }
    }

    private fun cancelSnapshotWatchdog() {
        handler.removeCallbacks(snapshotWatchdog)
    }

    private fun cancelScheduledReconnect() {
        if (!reconnectScheduled) return
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
    }

    private fun sendRules(commandId: String) {
        val revision = ruleStore.revision()
        val rules = ruleStore.readAll().values.toList()
        bridge.replaceRules(commandId, revision, rules)
    }

    private fun handleEvent(event: SoundManProtocol.Event) {
        if (closed) return
        when (event) {
            is SoundManProtocol.Event.SnapshotAvailable -> {
                sessionInitialized = true
                reconnectAttempt = 0
                cancelScheduledReconnect()
                cancelSnapshotWatchdog()
                publishSnapshot(event.snapshot)
            }
            is SoundManProtocol.Event.ResultAvailable -> publishResult(event.result)
            is SoundManProtocol.Event.HostError -> {
                Log.e(TAG, "SoundMan host error: ${event.message}")
                publishUnavailable()
            }
            is SoundManProtocol.Event.HostClosed -> {
                sessionInitialized = false
                Log.e(TAG, "SoundMan host closed: ${event.reason}")
                publishUnavailable()
                scheduleReconnect("host closed: ${event.reason}")
            }
        }
    }

    private fun publishSnapshot(snapshot: SoundManProtocol.Snapshot) {
        publishDevices(AudioDeviceScan(snapshot.outputDevices, null))
        val apps = snapshot.playback
            .map { entry -> loadApp(entry.packageName, entry.uid) }
            .sortedBy { it.label.lowercase() }
        Log.i(
            TAG,
            "Publishing host snapshot revision=${snapshot.revision} apps=${apps.size} devices=${snapshot.outputDevices.size}",
        )
        publish(ActiveMediaAppsState.Available(apps))
    }

    private fun loadApp(packageName: String, uid: Int): AdjustableApp {
        if (!installedAppsAccess.hasAccess(applicationContext)) {
            Log.w(TAG, "Skipping package lookup for uid=$uid package=$packageName without installed-apps access")
            return unknownApp(packageName, uid)
        }
        try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            return AdjustableApp(
                packageName = packageName,
                label = info.loadLabel(packageManager).toString(),
                uid = uid,
                icon = info.loadIcon(packageManager),
            )
        } catch (error: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Active uid=$uid package=$packageName is no longer installed", error)
        }
        return unknownApp(packageName, uid)
    }

    private fun unknownApp(packageName: String, uid: Int): AdjustableApp {
        return AdjustableApp(
            packageName = packageName,
            label = applicationContext.getString(R.string.unknown_app, uid),
            uid = uid,
            icon = packageManager.defaultActivityIcon,
        )
    }

    private fun publishResult(result: SoundManProtocol.CommandResult) {
        val publicResult = HostCommandResult(
            commandId = result.commandId,
            uid = result.uid ?: -1,
            success = result.success,
            resultCode = result.resultCode,
            effectiveTarget = result.effectiveTarget,
        )
        mainHandler.post { resultObservers.forEach { it(publicResult) } }
    }

    private fun publishUnavailable() {
        publishDevices(AudioDeviceScan(emptyList(), AudioDeviceScanError.HOST_UNAVAILABLE))
        publish(ActiveMediaAppsState.Error(ActiveMediaAppsError.HOST_UNAVAILABLE))
    }

    private fun publishDevices(newScan: AudioDeviceScan) {
        deviceScan = newScan
        mainHandler.post { deviceObservers.forEach { it(newScan) } }
    }

    private fun publish(newState: ActiveMediaAppsState) {
        state = newState
        mainHandler.post { observers.forEach { it(newState) } }
    }

    private companion object {
        const val RECONNECT_BASE_DELAY_MS = 500L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
        const val RECONNECT_MAX_SHIFT = 6
        const val SNAPSHOT_WATCHDOG_MS = 2_000L
    }

    private fun postToWorker(label: String, action: () -> Unit) {
        if (closed) return
        if (!handler.post {
                if (!closed) action()
            }
        ) {
            Log.e(TAG, "HostPlaybackSource worker rejected $label")
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        reconnectScheduled = false
        cancelSnapshotWatchdog()
        connectExecutor.shutdownNow()
        bridge.close()
        mainHandler.removeCallbacksAndMessages(null)
        observers.clear()
        deviceObservers.clear()
        resultObservers.clear()
        worker.quitSafely()
        try {
            worker.join(1_000L)
            if (worker.isAlive) Log.e(TAG, "Host IPC worker did not stop within timeout")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Interrupted while stopping host IPC worker", error)
        }
    }
}
