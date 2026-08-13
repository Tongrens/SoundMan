package hk.uwu.soundman.hook.scopes.system

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import hk.uwu.soundman.hook.core.YLog
import hk.uwu.soundman.ipc.PreferredDeviceSync
import hk.uwu.soundman.ipc.PreferredDeviceUsage
import hk.uwu.soundman.ipc.SoundManProtocol
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zygote 只钩 Application 启动，避免每个进程在 attach 阶段就背一整套音频 hook。
 * Application 起来后只装 `AudioTrack` + 广播。
 * 收到本 uid 的强制设备广播后，再装 `MediaPlayer` / strategy。
 */
object PreferredDeviceHooker : YukiBaseHooker() {
    private val applying = ThreadLocal.withInitial { false }
    private val tracks = CopyOnWriteArrayList<WeakReference<AudioTrack>>()
    private val players = CopyOnWriteArrayList<WeakReference<MediaPlayer>>()
    private val receiverRegistered = AtomicBoolean(false)
    private val trackHooksInstalled = AtomicBoolean(false)
    private val extraHooksInstalled = AtomicBoolean(false)
    @Volatile
    private var application: Application? = null
    private val routeLock = Any()
    private var cachedRoute: RouteState = RouteState.Unset
    private var cachedDevice: AudioDeviceInfo? = null
    @Volatile
    private var cachedUsage: Int = PreferredDeviceUsage.USAGE_MEDIA

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PreferredDeviceSync.ACTION) return
            val hint = try {
                PreferredDeviceSync.decodeIntent(intent)
            } catch (error: Throwable) {
                YLog.error("[route] decode broadcast failed uid=${Process.myUid()}", error)
                return
            }
            try {
                if (hint.uid != Process.myUid()) return
                synchronized(routeLock) {
                    cachedRoute = if (hint.followSystem) {
                        RouteState.FollowSystem
                    } else {
                        RouteState.Device(PreferredDeviceSync.DeviceSpec(hint.publicType, hint.address))
                    }
                    cachedDevice = null
                    cachedUsage = hint.usage
                }
                if (!hint.followSystem) installExtraHooks()
                applyToRegistered()
            } catch (error: Throwable) {
                YLog.error("[route] broadcast handle failed uid=${Process.myUid()}", error)
            }
        }
    }

    override fun onHook() {
        hookApplicationStart()
    }

    private fun hookApplicationStart() {
        "android.app.Instrumentation".toClass().resolve().firstMethod {
            name = "callApplicationOnCreate"
            parameters(Application::class.java)
        }.hook {
            after {
                if (throwable != null) return@after
                val app = args[0] as? Application ?: return@after
                application = app
                ensureReceiver()
                installTrackHooks()
            }
        }
    }

    private fun installTrackHooks() {
        if (!trackHooksInstalled.compareAndSet(false, true)) return
        try {
            hookAudioTrack()
        } catch (error: Throwable) {
            trackHooksInstalled.set(false)
            YLog.error("[route] AudioTrack hooks failed uid=${Process.myUid()}", error)
        }
    }

    private fun installExtraHooks() {
        if (!extraHooksInstalled.compareAndSet(false, true)) return
        try {
            hookMediaPlayer()
            hookAudioManagerStrategies()
        } catch (error: Throwable) {
            extraHooksInstalled.set(false)
            YLog.error("[route] extra hooks failed uid=${Process.myUid()}", error)
        }
    }

    private fun hookAudioTrack() {
        val trackClass = "android.media.AudioTrack".toClass()
        val resolved = trackClass.resolve()
        val constructors = resolved.constructor {
            parameterCount { count -> count >= 4 }
        }
        check(constructors.isNotEmpty()) { "AudioTrack has no constructors with >= 4 parameters" }
        constructors.hookAll {
            before {
                runHookSide("AudioTrack.<init> usage") {
                    rewriteUsageArgs()
                }
            }
            after {
                if (throwable != null) return@after
                runHookSide("AudioTrack.<init>") {
                    val track = instance as AudioTrack
                    registerTrack(track)
                    applyToTrack(track)
                }
            }
        }
        resolved.firstMethod {
            name = "play"
            emptyParameters()
        }.hook {
            after {
                if (throwable != null) return@after
                runHookSide("AudioTrack.play") {
                    val track = instance as AudioTrack
                    registerTrack(track)
                    applyToTrack(track)
                }
            }
        }
        resolved.firstMethod {
            name = "setPreferredDevice"
            parameters(AudioDeviceInfo::class.java)
        }.hook {
            before {
                if (applying.get() == true) return@before
                runHookSide("AudioTrack.setPreferredDevice") {
                    val forced = resolveForcedDevice() ?: return@runHookSide
                    args(0).set(forced)
                }
            }
        }
        "android.media.AudioTrack\$Builder".toClass().resolve().firstMethod {
            name = "build"
            emptyParameters()
        }.hook {
            before {
                runHookSide("AudioTrack.Builder.build usage") {
                    rewriteBuilderUsage(instance)
                }
            }
            after {
                if (throwable != null) return@after
                runHookSide("AudioTrack.Builder.build") {
                    val track = result as? AudioTrack ?: return@runHookSide
                    registerTrack(track)
                    applyToTrack(track)
                }
            }
        }
    }

    private fun hookMediaPlayer() {
        val resolved = "android.media.MediaPlayer".toClass().resolve()
        resolved.firstMethod {
            name = "start"
            emptyParameters()
        }.hook {
            after {
                if (throwable != null) return@after
                runHookSide("MediaPlayer.start") {
                    val player = instance as MediaPlayer
                    registerPlayer(player)
                    applyToPlayer(player)
                }
            }
        }
        resolved.firstMethod {
            name = "setPreferredDevice"
            parameters(AudioDeviceInfo::class.java)
        }.hook {
            before {
                if (applying.get() == true) return@before
                runHookSide("MediaPlayer.setPreferredDevice") {
                    val forced = resolveForcedDevice() ?: return@runHookSide
                    args(0).set(forced)
                }
            }
        }
    }

    private fun hookAudioManagerStrategies() {
        val resolved = "android.media.AudioManager".toClass().resolve()
        resolved.method { name = "getPreferredDeviceForStrategy" }.hookAll {
            after {
                if (throwable != null) return@after
                runHookSide("AudioManager.getPreferredDeviceForStrategy") {
                    val forced = cachedForcedDevice() ?: return@runHookSide
                    result = forced
                }
            }
        }
        resolved.method { name = "getPreferredDevicesForStrategy" }.hookAll {
            after {
                if (throwable != null) return@after
                runHookSide("AudioManager.getPreferredDevicesForStrategy") {
                    val forced = cachedForcedDevice() ?: return@runHookSide
                    result = listOf(forced)
                }
            }
        }
        resolved.method { name = "setPreferredDeviceForStrategy" }.hookAll {
            before {
                if (applying.get() == true) return@before
                runHookSide("AudioManager.setPreferredDeviceForStrategy") {
                    val forced = cachedForcedDevice() ?: return@runHookSide
                    overwriteStrategyDeviceArgs(forced)
                }
            }
        }
        resolved.method { name = "setPreferredDevicesForStrategy" }.hookAll {
            before {
                if (applying.get() == true) return@before
                runHookSide("AudioManager.setPreferredDevicesForStrategy") {
                    val forced = cachedForcedDevice() ?: return@runHookSide
                    overwriteStrategyDeviceArgs(forced)
                }
            }
        }
    }

    private fun com.highcapable.yukihookapi.hook.param.HookParam.rewriteUsageArgs() {
        val usage = cachedUsage
        if (!PreferredDeviceUsage.shouldRewrite(usage)) return
        args.forEachIndexed { index, value ->
            val attributes = value as? AudioAttributes ?: return@forEachIndexed
            if (attributes.usage == usage) return@forEachIndexed
            args(index).set(AudioAttributes.Builder(attributes).setUsage(usage).build())
        }
    }

    private fun rewriteBuilderUsage(builder: Any?) {
        val usage = cachedUsage
        if (builder == null || !PreferredDeviceUsage.shouldRewrite(usage)) return
        val field = builder.javaClass.getDeclaredField("mAttributes").apply { isAccessible = true }
        val attributes = field.get(builder) as? AudioAttributes ?: return
        if (attributes.usage == usage) return
        field.set(builder, AudioAttributes.Builder(attributes).setUsage(usage).build())
    }

    private fun com.highcapable.yukihookapi.hook.param.HookParam.overwriteStrategyDeviceArgs(forced: AudioDeviceInfo) {
        args.forEachIndexed { index, value ->
            when (value) {
                is AudioDeviceInfo -> args(index).set(forced)
                is List<*> -> args(index).set(listOf(forced))
            }
        }
    }

    private fun registerTrack(track: AudioTrack) {
        prune(tracks)
        if (tracks.none { it.get() === track }) {
            tracks += WeakReference(track)
        }
        ensureReceiver()
    }

    private fun registerPlayer(player: MediaPlayer) {
        prune(players)
        if (players.none { it.get() === player }) {
            players += WeakReference(player)
        }
        ensureReceiver()
    }

    private fun applyToTrack(track: AudioTrack) {
        when (val route = currentRoute()) {
            RouteState.Unset -> return
            RouteState.FollowSystem -> {
                if (track.preferredDevice == null) return
                applyPreferred { track.setPreferredDevice(null) }
            }
            is RouteState.Device -> {
                val device = liveDevice(route.spec) ?: return
                if (sameDevice(track.preferredDevice, device)) return
                applyPreferred {
                    if (!track.setPreferredDevice(device)) {
                        YLog.error("[route] AudioTrack.setPreferredDevice failed uid=${Process.myUid()} type=${device.type}")
                    }
                }
            }
        }
    }

    private fun applyToPlayer(player: MediaPlayer) {
        when (val route = currentRoute()) {
            RouteState.Unset -> return
            RouteState.FollowSystem -> {
                if (player.preferredDevice == null) return
                applyPreferred { player.setPreferredDevice(null) }
            }
            is RouteState.Device -> {
                val device = liveDevice(route.spec) ?: return
                if (sameDevice(player.preferredDevice, device)) return
                applyPreferred {
                    if (!player.setPreferredDevice(device)) {
                        YLog.error("[route] MediaPlayer.setPreferredDevice failed uid=${Process.myUid()} type=${device.type}")
                    }
                }
            }
        }
    }

    private fun sameDevice(current: AudioDeviceInfo?, target: AudioDeviceInfo): Boolean =
        current != null && current.type == target.type && current.address.orEmpty() == target.address.orEmpty()

    private fun applyToRegistered() {
        prune(tracks)
        prune(players)
        tracks.forEach { reference ->
            val track = reference.get() ?: return@forEach
            applyToTrack(track)
        }
        players.forEach { reference ->
            val player = reference.get() ?: return@forEach
            applyToPlayer(player)
        }
    }

    private fun resolveForcedDevice(): AudioDeviceInfo? {
        val route = currentRoute() as? RouteState.Device ?: return null
        return liveDevice(route.spec)
    }

    private fun cachedForcedDevice(): AudioDeviceInfo? {
        synchronized(routeLock) {
            if (cachedRoute !is RouteState.Device) return null
            cachedDevice?.let { return it }
        }
        return resolveForcedDevice()
    }

    private fun currentRoute(): RouteState = synchronized(routeLock) { cachedRoute }

    private fun liveDevice(spec: PreferredDeviceSync.DeviceSpec): AudioDeviceInfo? {
        val context = currentApplication() ?: run {
            YLog.warn("[route] no Application; skip device resolve uid=${Process.myUid()}")
            return null
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            YLog.error("[route] AudioManager unavailable uid=${Process.myUid()}")
            return null
        }
        val device = PreferredDeviceSync.findDevice(
            devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS),
            spec = spec,
        )
        if (device == null) {
            YLog.error(
                "[route] device not found uid=${Process.myUid()} publicType=${spec.publicType} " +
                    "address=${spec.address.ifEmpty { "<empty>" }}",
            )
            return null
        }
        synchronized(routeLock) { cachedDevice = device }
        return device
    }

    private fun ensureReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val context = currentApplication()
        if (context == null) {
            receiverRegistered.set(false)
            YLog.warn("[route] cannot register receiver before Application uid=${Process.myUid()}")
            return
        }
        val uid = Process.myUid()
        try {
            context.registerReceiver(
                receiver,
                IntentFilter(PreferredDeviceSync.ACTION),
                SoundManProtocol.CONTROL_PERMISSION,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            applyToRegistered()
        } catch (error: Throwable) {
            receiverRegistered.set(false)
            YLog.error("[route] register receiver failed uid=$uid", error)
        }
    }

    private fun currentApplication(): Application? {
        application?.let { return it }
        val found = try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Application
        } catch (_: Throwable) {
            null
        }
        if (found != null) application = found
        return found
    }

    private inline fun runHookSide(label: String, block: () -> Unit) {
        try {
            if (currentApplication() == null) return
            block()
        } catch (error: Throwable) {
            YLog.error("[route] $label failed uid=${Process.myUid()}", error)
        }
    }

    private fun applyPreferred(block: () -> Unit) {
        applying.set(true)
        try {
            block()
        } finally {
            applying.set(false)
        }
    }

    private fun <T : Any> prune(refs: CopyOnWriteArrayList<WeakReference<T>>) {
        refs.removeAll { it.get() == null }
    }

    private sealed interface RouteState {
        data object Unset : RouteState
        data object FollowSystem : RouteState
        data class Device(val spec: PreferredDeviceSync.DeviceSpec) : RouteState
    }
}
