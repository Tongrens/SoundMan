package hk.uwu.soundman.ipc

import android.os.RemoteException

/**
 * Host 热重载后的会话恢复判定。
 *
 * 动机：system_server 进程不会死，旧 Stub 的 `linkToDeath` 不会响。
 * 旧代 `close()` 之后下一次命令抛的是 `IllegalStateException("host is closed")`，
 * 不是 `DeadObjectException`。必须把这种失败当成会话死亡并重连。
 */
object HostIpcRecovery {
    /**
     * 这次 Host 失败是否意味着当前 Binder 已经作废，必须丢掉会话。
     *
     * @param error Host 调用抛出的异常，含 cause 链
     */
    fun isFatalHostFailure(error: Throwable): Boolean {
        generateSequence(error) { current -> current.cause }.forEach { current ->
            if (current is RemoteException) return true
            val message = current.message.orEmpty()
            if (message.contains("host is closed") ||
                message.contains("host is not connected") ||
                message.contains("generation is closed")
            ) {
                return true
            }
        }
        return false
    }
}
