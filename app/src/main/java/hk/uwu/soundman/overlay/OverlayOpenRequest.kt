package hk.uwu.soundman.overlay

import android.content.Intent
import android.view.KeyEvent

/**
 * 打开 SoundMan 浮层时的来源约定。
 *
 * 动机：侧栏入口打开后要关音量面板，并且只对侧栏来源播入场动画。
 * 桌面/普通 MainActivity 打开不得带这个标记。
 *
 * @param fromVolumeSidebar 是否由音量侧栏入口打开
 */
data class OverlayOpenRequest(
    val fromVolumeSidebar: Boolean,
) {
    /**
     * 写入 Intent extra，供 Activity / Overlay 服务往返。
     *
     * @param intent 要写入的 Intent
     * @return 同一个 [intent]，方便链式调用
     */
    fun putInto(intent: Intent): Intent {
        extras().forEach { (key, value) ->
            intent.putExtra(key, value)
        }
        return intent
    }

    /**
     * extra 的纯数据形态，供 JVM 单测验证往返而不依赖 Intent stub。
     */
    fun extras(): Map<String, Boolean> = mapOf(EXTRA_FROM_VOLUME_SIDEBAR to fromVolumeSidebar)

    companion object {
        /** 侧栏入口打开浮层时写入的 extra 键。 */
        const val EXTRA_FROM_VOLUME_SIDEBAR = "hk.uwu.soundman.extra.FROM_VOLUME_SIDEBAR"

        /** SystemUI 侧栏打开浮层时使用的 action。 */
        const val ACTION_OPEN_OVERLAY = "hk.uwu.soundman.action.OPEN_OVERLAY"

        /** 模块包名。SystemUI 进程里必须用显式 ComponentName。 */
        const val MODULE_PACKAGE = "hk.uwu.soundman"

        /**
         * 侧栏打开浮层时的 trampoline Activity。
         *
         * 不能走 [hk.uwu.soundman.MainActivity]：它是 `singleTop`，只带
         * `FLAG_ACTIVITY_NEW_TASK` 会把已有主页任务拉到前台，半透明浮层后面就会露出主屏。
         */
        const val LAUNCH_ACTIVITY_CLASS = "hk.uwu.soundman.overlay.OverlayLaunchActivity"

        /**
         * SystemUI 侧栏打开浮层的 Activity 约定。
         *
         * 空 taskAffinity 的 trampoline 加上 NEW_TASK，不会加入主页任务。
         * EXCLUDE_FROM_RECENTS / NO_ANIMATION / NO_USER_ACTION 避免把主页 recents 项顶上来。
         */
        fun sidebarActivityLaunch(): OverlayActivityLaunch = OverlayActivityLaunch(
            packageName = MODULE_PACKAGE,
            className = LAUNCH_ACTIVITY_CLASS,
            action = ACTION_OPEN_OVERLAY,
            flags = sidebarActivityFlags(),
            extras = OverlayOpenRequest(fromVolumeSidebar = true).extras(),
        )

        /**
         * 侧栏 trampoline 的启动 flags。
         *
         * 不含 CLEAR_TASK / CLEAR_TOP：那会拆掉已有主页任务。
         */
        fun sidebarActivityFlags(): Int =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION

        /**
         * 从 Intent 读取打开约定。
         *
         * `null` 或没有 extra 都视为不是侧栏打开。
         */
        fun fromIntent(intent: Intent?): OverlayOpenRequest {
            if (intent == null) return fromExtras(null)
            if (!intent.hasExtra(EXTRA_FROM_VOLUME_SIDEBAR)) {
                return fromExtras(emptyMap())
            }
            return fromExtras(
                mapOf(
                    EXTRA_FROM_VOLUME_SIDEBAR to intent.getBooleanExtra(EXTRA_FROM_VOLUME_SIDEBAR, false),
                ),
            )
        }

        /**
         * 从纯 extra 映射构造请求。
         *
         * 动机：Android 单元测试里 Intent 经常是 stub，往返必须能直接测这个函数。
         */
        fun fromExtras(extras: Map<String, Boolean>?): OverlayOpenRequest =
            OverlayOpenRequest(parseFromVolumeSidebar(extras))

        /**
         * 解析侧栏来源标记。
         *
         * @param extras extra 映射；`null` 或缺少键都是 false
         */
        fun parseFromVolumeSidebar(extras: Map<String, Boolean>?): Boolean {
            if (extras == null) return false
            return extras[EXTRA_FROM_VOLUME_SIDEBAR] == true
        }

        /**
         * 打开浮层后用来关掉音量侧栏的按键序列。
         *
         * `VolumePanelDialog.dispatchKeyEvent` 在 KEYCODE_BACK + ACTION_UP 时
         * 会 `dialogEventListener.dismiss(7)`。先 DOWN 再 UP，模拟真实返回键。
         */
        fun volumeSidebarDismissSequence(): List<DismissKeyStroke> = listOf(
            DismissKeyStroke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK),
            DismissKeyStroke(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK),
        )
    }
}

/**
 * 关音量侧栏时要派发的一次按键。
 *
 * @param action [KeyEvent.ACTION_DOWN] 或 [KeyEvent.ACTION_UP]
 * @param keyCode [KeyEvent.KEYCODE_BACK]
 */
data class DismissKeyStroke(
    val action: Int,
    val keyCode: Int,
)

/**
 * SystemUI 打开浮层时要 startActivity 的目标。
 *
 * 纯数据，JVM 单测不依赖 Intent stub 也能核对 className / flags。
 *
 * @param packageName 模块包名
 * @param className trampoline Activity 全名
 * @param action [OverlayOpenRequest.ACTION_OPEN_OVERLAY]
 * @param flags [OverlayOpenRequest.sidebarActivityFlags]
 * @param extras 与 [OverlayOpenRequest.extras] 相同
 */
data class OverlayActivityLaunch(
    val packageName: String,
    val className: String,
    val action: String,
    val flags: Int,
    val extras: Map<String, Boolean>,
)
