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
