package hk.uwu.soundman.ipc

import android.content.Intent
import android.os.IBinder
import hk.uwu.soundman.internal.ipc.ISoundManHostOffer

/**
 * Host 侧握手投递器。
 *
 * 动机：system_server 出站必须是 oneway。这里只解析 REQUEST_BINDER 并调用
 * `ISoundManHostOffer.onHostOffered`，不得再碰任何 two-way App 接口。
 * 广播 extras 缺字段、版本不对或 offer Binder 无法转成邮箱时必须立刻失败。
 *
 * @param resolveOffer 把 extras 里的 Binder 转成 [ISoundManHostOffer]。
 * 生产默认走 AIDL `asInterface`；单测注入内存邮箱，因为 JVM stub 的 `Binder.attachInterface` 不可用
 */
class HostOfferPublisher(
    private val resolveOffer: (IBinder) -> ISoundManHostOffer = ::asHostOffer,
) {
    /**
     * 把 Host 会话 Binder 丢进 App 邮箱后立即返回。
     *
     * @param intent App 发来的 REQUEST_BINDER，必须带 version 和 offer Binder
     * @param hostBinder 本进程 `ISoundManHostService` 的 Binder
     */
    fun offer(intent: Intent, hostBinder: IBinder) {
        deliver(SoundManProtocol.decodeRequestBinder(intent), hostBinder)
    }

    /**
     * 与 [offer] 相同的解析和投递，extras 用纯 Map 表达。
     *
     * 动机：单元测试无法在 JVM stub 上往 Intent 里放入 IBinder，必须直接验证同一套 fail-fast 逻辑。
     */
    fun offer(extras: Map<String, Any?>, hostBinder: IBinder) {
        deliver(SoundManProtocol.decodeRequestBinder(extras), hostBinder)
    }

    private fun deliver(request: SoundManProtocol.RequestBinder, hostBinder: IBinder) {
        val offer = resolveOffer(request.offerBinder)
        offer.onHostOffered(hostBinder, request.protocolVersion)
    }

    private companion object {
        fun asHostOffer(binder: IBinder): ISoundManHostOffer {
            return ISoundManHostOffer.Stub.asInterface(binder)
                ?: throw IllegalArgumentException("invalid extra: ${SoundManProtocol.EXTRA_HOST_OFFER}")
        }
    }
}
