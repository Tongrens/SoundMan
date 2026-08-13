package hk.uwu.soundman.ipc

import android.os.Binder
import android.os.IBinder
import android.os.Process
import hk.uwu.soundman.internal.ipc.ISoundManHostOffer

/**
 * App 侧 oneway 握手邮箱。
 *
 * 动机：system_server 不能同步回调 App。App 先把这个邮箱放进 REQUEST_BINDER，
 * Host 把会话 Binder 丢进来就结束，握手线程不得在这里 registerClient。
 * 邮箱只做 UID / Binder / 版本校验，通过后把 Host Binder 交给 [dispatch]。
 *
 * @param callingUid 读取调用方 UID。测试可注入，生产默认 `Binder.getCallingUid()`
 * @param expectedVersion 期望的协议版本，默认当前 [SoundManProtocol.VERSION]
 * @param dispatch 校验通过后的唯一出口，由 [SoundManHostBridgeClient] 投到 handshakeHandler
 */
class HostOfferMailbox(
    private val callingUid: () -> Int = { Binder.getCallingUid() },
    private val expectedVersion: Int = SoundManProtocol.VERSION,
    private val dispatch: (hostBinder: IBinder, protocolVersion: Int) -> Unit,
) {
    /**
     * 暴露给广播的 `ISoundManHostOffer` Stub。
     *
     * 动机：Host 只认 AIDL 邮箱 Binder，不能把业务会话对象直接塞进 Intent。
     */
    val binder: IBinder by lazy {
        object : ISoundManHostOffer.Stub() {
            override fun onHostOffered(hostBinder: IBinder?, protocolVersion: Int) {
                this@HostOfferMailbox.onHostOffered(hostBinder, protocolVersion)
            }
        }
    }

    /**
     * 接收 Host 投递的会话 Binder。
     *
     * 非 SYSTEM_UID、Binder 为空或版本不匹配立即失败；成功时只调用 [dispatch]。
     */
    fun onHostOffered(hostBinder: IBinder?, protocolVersion: Int) {
        val uid = callingUid()
        if (uid != Process.SYSTEM_UID) {
            throw SecurityException("onHostOffered requires SYSTEM_UID, got uid=$uid")
        }
        val offered = hostBinder
            ?: throw IllegalArgumentException("hostBinder is null")
        require(protocolVersion == expectedVersion) {
            "protocol version mismatch: $protocolVersion != $expectedVersion"
        }
        dispatch(offered, protocolVersion)
    }
}
