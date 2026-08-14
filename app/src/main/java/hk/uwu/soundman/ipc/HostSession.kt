package hk.uwu.soundman.ipc

import android.os.IBinder
import android.os.RemoteException
import hk.uwu.soundman.internal.ipc.ISoundManClientCallback
import hk.uwu.soundman.internal.ipc.ISoundManHostService
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.OutputTarget

/**
 * 已连接的 Host 命令面。
 *
 * 动机：握手完成后的命令、death recipient 和注销不应再和广播邮箱编排混在一起，
 * 避免会话生命周期被 Binder 回调线程直接改写。
 * 把 `registerClient` / `unlinkToDeath` / 命令校验从握手编排里拆出来，
 * 让 [SoundManHostBridgeClient] 只负责切线程。握手版本来自 offer，不再 two-way `getProtocolVersion`。
 *
 * @param host 已 `asInterface` 的 Host 服务
 * @param hostBinder 用于 death recipient 的原始 Binder
 * @param callback 已注册或即将注册的客户端回调
 * @param onRemoteDied Binder 死亡或事务失败后通知编排层
 */
class HostSession private constructor(
    private val host: ISoundManHostService,
    private val hostBinder: IBinder,
    private val callback: ISoundManClientCallback,
    private val onRemoteDied: () -> Unit,
) : AutoCloseable {
    private val lock = Any()

    @Volatile
    private var connected = true

    private val deathRecipient = IBinder.DeathRecipient {
        val notify = synchronized(lock) {
            if (!connected) return@DeathRecipient
            connected = false
            true
        }
        if (notify) onRemoteDied()
    }

    /**
     * 向 Host 请求最新快照。
     *
     * @param commandId 非空命令 ID，用于匹配异步结果
     */
    fun requestSnapshot(commandId: String) {
        requireCommandId(commandId)
        invokeHost("requestSnapshot") { it.requestSnapshot(commandId) }
    }

    /**
     * 用完整规则集替换 Host 侧规则。
     *
     * @param commandId 非空命令 ID
     * @param revision 本次替换的规则修订号，不得为负，且不得小于单条规则 revision
     * @param rules 完整规则列表
     */
    fun replaceRules(commandId: String, revision: Long, rules: List<AppAudioRule>) {
        requireCommandId(commandId)
        require(revision >= 0L) { "revision must not be negative" }
        require(rules.all { it.revision <= revision }) { "rule revision exceeds replacement revision" }
        val encoded = SoundManProtocol.encodeRules(rules)
        invokeHost("replaceRules") { it.replaceRules(commandId, revision, encoded) }
    }

    /**
     * 设置指定 uid 的播放音量百分比。
     *
     * @param commandId 非空命令 ID
     * @param uid 目标应用 uid，必须非负
     * @param percent 0..100
     */
    fun setVolume(commandId: String, uid: Int, percent: Int) {
        requireCommandId(commandId)
        require(uid >= 0) { "uid must be non-negative" }
        require(percent in 0..100) { "percent must be in 0..100" }
        invokeHost("setVolume") { it.setVolume(commandId, uid, percent) }
    }

    /**
     * 设置指定 uid 的输出路由。
     *
     * @param commandId 非空命令 ID
     * @param uid 目标应用 uid，必须非负
     * @param target 跟随系统或固定设备
     */
    fun setRoute(commandId: String, uid: Int, target: OutputTarget) {
        requireCommandId(commandId)
        require(uid >= 0) { "uid must be non-negative" }
        val encoded = SoundManProtocol.encodeTarget(target)
        invokeHost("setRoute") { it.setRoute(commandId, uid, encoded) }
    }

    /**
     * 会话是否仍持有可用 Host Binder。
     *
     * 动机：编排层在发命令前必须能判断是否需要重新握手，而不能去探活 two-way API。
     */
    fun isConnected(): Boolean = connected

    /**
     * 注销客户端并断开 death recipient。
     *
     * 动机：App 关闭或替换会话时必须明确释放 Host 侧 callback，避免残留回调打到已死客户端。
     */
    override fun close() {
        if (!markDisconnected()) return
        unregisterQuietly()
        unlinkQuietly()
    }

    private inline fun invokeHost(operation: String, call: (ISoundManHostService) -> Unit) {
        check(connected) { "SoundMan host session is closed" }
        try {
            call(host)
        } catch (error: RemoteException) {
            AppLog.error("SoundMan host transaction failed: $operation", error)
            dropDeadSession()
            throw IllegalStateException("SoundMan host transaction failed: $operation", error)
        } catch (error: RuntimeException) {
            AppLog.error("SoundMan host rejected operation: $operation", error)
            if (HostIpcRecovery.isFatalHostFailure(error)) {
                dropDeadSession()
            }
            throw error
        }
    }

    private fun dropDeadSession() {
        if (markDisconnected()) {
            unregisterQuietly()
            unlinkQuietly()
            onRemoteDied()
        }
    }

    private fun abortIncompleteConnect() {
        markDisconnected()
        unlinkQuietly()
    }

    private fun markDisconnected(): Boolean = synchronized(lock) {
        if (!connected) return false
        connected = false
        true
    }

    private fun unregisterQuietly() {
        try {
            host.unregisterClient(callback)
        } catch (error: RemoteException) {
            AppLog.error("Unable to unregister SoundMan client", error)
        } catch (error: RuntimeException) {
            AppLog.error("Host rejected SoundMan client unregister", error)
        }
    }

    private fun unlinkQuietly() {
        try {
            hostBinder.unlinkToDeath(deathRecipient, 0)
        } catch (error: Throwable) {
            AppLog.error("Unable to unlink SoundMan host death recipient", error)
        }
    }

    private fun requireCommandId(commandId: String) {
        require(commandId.isNotBlank()) { "commandId must not be blank" }
    }

    companion object {
        /**
         * 用 offer 带来的协议版本安装会话：linkToDeath 后 registerClient。
         *
         * 动机：版本以邮箱投递值为准，禁止再 two-way 询问 Host。
         */
        fun connect(
            hostBinder: IBinder,
            protocolVersion: Int,
            callback: ISoundManClientCallback,
            onRemoteDied: () -> Unit,
            expectedVersion: Int = SoundManProtocol.VERSION,
        ): HostSession {
            require(protocolVersion == expectedVersion) {
                "host protocol version $protocolVersion does not match $expectedVersion"
            }
            val host = ISoundManHostService.Stub.asInterface(hostBinder)
                ?: throw IllegalArgumentException("host Binder does not expose ISoundManHostService")
            val session = HostSession(host, hostBinder, callback, onRemoteDied)
            try {
                hostBinder.linkToDeath(session.deathRecipient, 0)
                host.registerClient(expectedVersion, callback)
            } catch (error: Throwable) {
                session.abortIncompleteConnect()
                throw error
            }
            return session
        }
    }
}
