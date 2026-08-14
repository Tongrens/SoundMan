package hk.uwu.soundman.data

import android.content.Context
import android.content.pm.PackageManager
import hk.uwu.soundman.log.AppLog

/**
 * 系统权限目录探测。
 *
 * 动机：小米「获取应用列表」是否支持动态申请，取决于权限是否由
 * `com.lbe.security.miui` 提供。生产环境走 [PackageManager]；
 * [PackageManager.NameNotFoundException] 表示当前 ROM 没有声明该权限，记日志后按「不支持」处理。
 * 测试可注入 [ownerOf] / [grantedOf]，避免单测依赖真实 ROM。
 */
class PermissionCatalog(
    private val ownerOf: (String) -> String?,
    private val grantedOf: (String) -> Boolean,
) {
    constructor(context: Context) : this(
        ownerOf = { permission ->
            try {
                context.applicationContext.packageManager.getPermissionInfo(permission, 0).packageName
            } catch (error: PackageManager.NameNotFoundException) {
                AppLog.info("Permission $permission is not defined on this system", error)
                null
            }
        },
        grantedOf = { permission ->
            context.applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        },
    )

    /**
     * 查询权限定义方的包名。
     *
     * 动机：`getPermissionInfo` 在权限不存在时抛 [PackageManager.NameNotFoundException]，
     * 这表示系统不支持该动态权限，必须收敛成 null 而不是崩溃。
     *
     * @param permission 完整权限名
     * @return 定义该权限的包名；权限不存在时返回 null
     */
    fun permissionOwner(permission: String): String? {
        require(permission.isNotBlank()) { "permission must not be blank" }
        return ownerOf(permission)
    }

    /**
     * 查询当前进程是否已获得该权限。
     *
     * 动机：MIUI 动态权限的授予结果与普通 runtime permission 相同，
     * 需要与 [permissionOwner] 分开探测，才能覆盖「支持但未授权」。
     *
     * @param permission 完整权限名
     * @return 已授予时 true
     */
    fun checkGranted(permission: String): Boolean {
        require(permission.isNotBlank()) { "permission must not be blank" }
        return grantedOf(permission)
    }

}
