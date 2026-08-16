package hk.uwu.soundman.ipc

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import hk.uwu.soundman.internal.ipc.ISoundManClientCallback
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.OutputTarget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App 侧 Host Binder 会话编排。
 *
 * 动机：握手只创建 oneway 邮箱、发广播、等 latch，并把 Host 投递切到 [handshakeHandler]
 * 再 `registerClient`。Binder 线程不得安装会话，以免 Host 出站路径和 App 回呼互相卡住。
 */
class SoundManHostBridgeClient(
    context: Context,
    private val handshakeHandler: Handler,
    private val eventListener: (SoundManProtocol.Event) -> Unit,
    private val unavailableListener: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var session: HostSession? = null

    private var connectLatch: CountDownLatch? = null
    private var closed = false

    private val callback = object : ISoundManClientCallback.Stub() {
        override fun onEvent(event: String?, payload: Bundle?) {
            enforceSystemCaller("client callback")
            val eventName = requireNotNull(event) { "host event name is null" }
            val eventPayload = requireNotNull(payload) { "host event payload is null" }
            val decoded = try {
                SoundManProtocol.decodeEvent(eventName, eventPayload)
            } catch (error: RuntimeException) {
                AppLog.error("Rejected malformed host event: $eventName", error)
                dropSession(REASON_PROTOCOL_ERROR, notify = true)
                return
            }
            if (decoded is SoundManProtocol.Event.HostClosed) {
                dropSession(decoded.reason, notify = true)
                return
            }
            eventListener(decoded)
        }
    }

    /** Requests the host Binder and waits until callback registration has completed. */
    fun connect(timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS): Boolean {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        check(Looper.myLooper() != handshakeHandler.looper) {
            "connect() cannot block handshakeHandler; that looper must stay free to install the session"
        }
        val latch = synchronized(lock) {
            check(!closed) { "SoundManHostBridgeClient is closed" }
            if (session?.isConnected() == true) return true
            connectLatch?.let { return@synchronized it }
            CountDownLatch(1).also { pending ->
                connectLatch = pending
                try {
                    val mailbox = HostOfferMailbox(
                        dispatch = { hostBinder, protocolVersion ->
                            enqueueInstall(hostBinder, protocolVersion)
                        },
                    )
                    appContext.sendBroadcast(SoundManProtocol.requestBinderIntent(mailbox.binder))
                } catch (error: Throwable) {
                    AppLog.error("Unable to request SoundMan host Binder", error)
                    connectLatch = null
                    pending.countDown()
                }
            }
        }
        val signalled = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            val closing = synchronized(lock) { closed }
            if (!closing) {
                AppLog.error("Interrupted while waiting for SoundMan host Binder", error)
            }
            false
        }
        val outcome = synchronized(lock) {
            if (connectLatch === latch) connectLatch = null
            SoundManConnectOutcome(
                connected = signalled && session?.isConnected() == true,
                closed = closed,
            )
        }
        if (SoundManConnectOutcomePolicy.shouldReportFailure(outcome)) {
            AppLog.error("SoundMan host connection attempt failed signalled=$signalled")
            unavailableListener(REASON_CONNECT_FAILED)
        }
        return outcome.connected
    }

    fun isConnected(): Boolean = session?.isConnected() == true

    /**
     * 丢掉当前会话但不通知 unavailable。
     *
     * 动机：热重载后旧 Binder 还“连着”，但快照一直不来，必须重新发 REQUEST_BINDER。
     */
    fun resetSession() {
        dropSession("session reset", notify = false)
    }

    fun requestSnapshot(commandId: String) {
        connectedSession().requestSnapshot(commandId)
    }

    fun replaceRules(commandId: String, revision: Long, rules: List<AppAudioRule>) {
        connectedSession().replaceRules(commandId, revision, rules)
    }

    fun setVolume(commandId: String, uid: Int, percent: Int) {
        connectedSession().setVolume(commandId, uid, percent)
    }

    fun setRoute(commandId: String, uid: Int, target: OutputTarget) {
        connectedSession().setRoute(commandId, uid, target)
    }

    override fun close() {
        val current = synchronized(lock) {
            if (closed) return
            closed = true
            session.also { session = null }
        }
        current?.close()
        synchronized(lock) {
            connectLatch?.countDown()
            connectLatch = null
        }
    }

    private fun connectedSession(): HostSession {
        synchronized(lock) {
            session?.takeIf { it.isConnected() }?.let { return it }
        }
        if (Looper.myLooper() == handshakeHandler.looper) {
            error("SoundMan host is not connected")
        }
        check(connect()) { "SoundMan host is not connected" }
        return requireNotNull(synchronized(lock) { session?.takeIf { it.isConnected() } }) {
            "SoundMan host disappeared after connection"
        }
    }

    private fun enqueueInstall(hostBinder: IBinder, protocolVersion: Int) {
        val accepted = synchronized(lock) {
            if (closed) return
            handshakeHandler.post {
                if (synchronized(lock) { closed }) return@post
                try {
                    installSession(hostBinder, protocolVersion)
                } catch (error: Throwable) {
                    if (synchronized(lock) { closed }) return@post
                    AppLog.error("Unable to install SoundMan host session", error)
                    failConnect()
                }
            }
        }
        if (!accepted) {
            val closing = synchronized(lock) { closed }
            if (!closing) {
                AppLog.error("handshakeHandler rejected host offer dispatch")
                failConnect()
            }
        }
    }

    private fun installSession(hostBinder: IBinder, protocolVersion: Int) {
        val candidate = try {
            HostSession.connect(
                hostBinder = hostBinder,
                protocolVersion = protocolVersion,
                callback = callback,
                onRemoteDied = { dropSession(REASON_REMOTE_DIED, notify = true) },
            )
        } catch (error: Throwable) {
            AppLog.error("Unable to register with SoundMan host", error)
            failConnect()
            return
        }

        val accepted = synchronized(lock) {
            if (closed || session?.isConnected() == true) {
                false
            } else {
                session = candidate
                connectLatch?.countDown()
                connectLatch = null
                true
            }
        }
        if (!accepted) {
            candidate.close()
        }
    }

    private fun dropSession(reason: String, notify: Boolean) {
        val current = synchronized(lock) {
            val existing = session
            session = null
            connectLatch?.countDown()
            connectLatch = null
            existing
        }
        current?.close()
        if (notify && current != null) unavailableListener(reason)
    }

    private fun failConnect() {
        synchronized(lock) {
            connectLatch?.countDown()
            connectLatch = null
        }
    }

    private fun enforceSystemCaller(endpoint: String) {
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SYSTEM_UID) {
            AppLog.error("$endpoint rejected caller uid=$callingUid")
            throw SecurityException("$endpoint requires SYSTEM_UID")
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000L
        const val REASON_REMOTE_DIED = "remote_died"
        const val REASON_PROTOCOL_ERROR = "protocol_error"
        const val REASON_CONNECT_FAILED = "connect_failed"
    }
}

internal data class SoundManConnectOutcome(
    val connected: Boolean,
    val closed: Boolean,
)

/** 决定连接等待结束后是否应向业务层报告真实连接故障。 */
internal object SoundManConnectOutcomePolicy {
    fun shouldReportFailure(outcome: SoundManConnectOutcome): Boolean =
        !outcome.connected && !outcome.closed
}
