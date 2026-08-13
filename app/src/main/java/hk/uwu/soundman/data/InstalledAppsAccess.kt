package hk.uwu.soundman.data

import android.content.Context
import android.util.Log

/**
 * 读取已安装应用信息的访问门禁。
 *
 * 动机：小米在 `QUERY_ALL_PACKAGES` 之上额外管控「获取应用列表」。
 * 面板展示应用名和图标前必须先判断 ROM 是否提供
 * `com.android.permission.GET_INSTALLED_APPS`，以及用户是否已授权。
 * 不支持该动态权限的系统走清单里的 `QUERY_ALL_PACKAGES`。
 * 只有权限由 `com.lbe.security.miui` 提供时才要求 runtime 授权。
 *
 * @param catalog 权限目录探测
 * @param warn 未授权或 owner 异常时的警告出口；测试可注入
 */
class InstalledAppsAccess(
    private val catalog: PermissionCatalog,
    private val warn: (tag: String, message: String) -> Unit = { tag, message -> Log.w(tag, message) },
) {
    /**
     * 当前系统是否把该权限实现为可动态申请的 runtime permission。
     *
     * 动机：只有权限由 `com.lbe.security.miui` 提供时才应弹出授权框；
     * 权限不存在或由其它包定义时，不能当成 MIUI 动态权限。
     */
    fun isRuntimePermissionPresent(): Boolean {
        val owner = catalog.permissionOwner(PERMISSION)
        if (owner != null && owner != MIUI_OWNER) {
            warn(TAG, "GET_INSTALLED_APPS owner is $owner, not $MIUI_OWNER")
        }
        return owner == MIUI_OWNER
    }

    /**
     * 当前进程是否允许查询其它已安装应用。
     *
     * 动机：支持动态权限时必须已 GRANTED；不支持时返回 true，
     * 由系统按 `QUERY_ALL_PACKAGES` 处理。
     *
     * @param context 调用方 Context，与 Activity/Service 生命周期对齐
     */
    fun hasAccess(context: Context): Boolean {
        if (!isRuntimePermissionPresent()) return true
        val granted = catalog.checkGranted(PERMISSION)
        if (!granted) {
            warn(TAG, "Installed-apps permission $PERMISSION is not granted")
        }
        return granted
    }

    /**
     * 需要向系统申请的权限名。
     *
     * 动机：Activity 用 `RequestPermission` 申请时必须拿到精确字符串，
     * 不能在 UI 层再写一份常量。
     */
    fun permissionName(): String = PERMISSION

    private companion object {
        const val TAG = "SoundMan.AppsAccess"
        const val PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
        const val MIUI_OWNER = "com.lbe.security.miui"
    }
}
