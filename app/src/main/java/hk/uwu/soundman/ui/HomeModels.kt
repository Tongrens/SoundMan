package hk.uwu.soundman.ui

import android.content.Context
import com.highcapable.yukihookapi.YukiHookAPI
import hk.uwu.soundman.generated.AppProperties
import hk.uwu.soundman.ui.XposedStatusCopy.executorName

private const val APP_AUTHOR = "AnserJim"

/**
 * 主页「关于」卡片的展示数据。
 *
 * 动机：图标在 Compose 里另取；版本串由 Gropify / git 元数据拼出来，不读 PackageManager。
 */
data class AppAboutInfo(
    val label: String,
    val author: String,
    val moduleVersion: String,
    val versionCodename: String,
    val buildChannel: String,
    val gitBranch: String,
    val githubUrl: String,
) {
    companion object {
        /**
         * 从本包和 [AppProperties] 读取当前模块信息。
         *
         * @param context 任意 Context，内部用 applicationContext
         */
        fun load(context: Context): AppAboutInfo {
            val appContext = context.applicationContext
            val pm = appContext.packageManager
            return AppAboutInfo(
                label = appContext.applicationInfo.loadLabel(pm).toString(),
                author = APP_AUTHOR,
                moduleVersion = AppVersionCopy.moduleVersion(
                    versionName = AppProperties.PROJECT_APP_VERSION_NAME,
                    gitHash = AppProperties.GIT_HASH,
                    buildNumber = AppProperties.BUILD_NUMBER,
                    channel = AppProperties.BUILD_CHANNEL,
                ),
                versionCodename = AppProperties.PROJECT_APP_VERSION_CODENAME,
                buildChannel = AppProperties.BUILD_CHANNEL,
                gitBranch = GitRepoCopy.displayBranch(AppProperties.GIT_BRANCH),
                githubUrl = GitRepoCopy.githubUrl(AppProperties.GIT_BRANCH),
            )
        }
    }
}

/**
 * 主页版本串拼装规则。
 *
 * 动机：与 REAREye 一致，界面展示 `version-hash-rN-channel`，单测不依赖生成类。
 */
object AppVersionCopy {
    /**
     * `1.0.0-d49c5ae-r1-dev`
     *
     * @param versionName 基础版本号，不含 git 后缀
     * @param gitHash 短 hash
     * @param buildNumber git 提交数派生的 versionCode
     * @param channel 构建渠道，默认 dev
     */
    fun moduleVersion(
        versionName: String,
        gitHash: String,
        buildNumber: Int,
        channel: String,
    ): String {
        require(versionName.isNotBlank()) { "versionName is blank" }
        require(gitHash.isNotBlank()) { "gitHash is blank" }
        require(buildNumber > 0) { "buildNumber must be positive" }
        require(channel.isNotBlank()) { "channel is blank" }
        return "$versionName-$gitHash-r$buildNumber-$channel"
    }
}

/**
 * `GIT_BRANCH` 在构建时写成 `owner/repo/branch`。界面只展示 branch，GitHub 用前两段拼仓库地址。
 */
object GitRepoCopy {
    private const val FALLBACK_GITHUB_URL = "https://github.com/killerprojecte/SoundMan"

    /**
     * `killerprojecte/SoundMan/master` → `master`
     * `killerprojecte/SoundMan/feature/ui` → `feature/ui`
     */
    fun displayBranch(raw: String): String {
        val parts = raw.trim().split('/').filter { it.isNotBlank() }
        require(parts.isNotEmpty()) { "git branch is blank" }
        return if (parts.size >= 3) parts.drop(2).joinToString("/") else parts.last()
    }

    /**
     * `killerprojecte/SoundMan/master` → `https://github.com/killerprojecte/SoundMan`
     */
    fun githubUrl(raw: String): String {
        val parts = raw.trim().split('/').filter { it.isNotBlank() }
        val owner = parts.getOrNull(0)
        val repo = parts.getOrNull(1)
        return if (owner != null && repo != null && parts.size >= 3) {
            "https://github.com/$owner/$repo"
        } else {
            FALLBACK_GITHUB_URL
        }
    }
}

/**
 * Xposed / LSPosed 激活状态。
 *
 * @param active 模块是否已在框架中启用
 * @param executorName 框架名；未激活时可能为空
 * @param apiLevel 框架 API；未知为 0
 */
data class XposedStatusInfo(
    val active: Boolean,
    val executorName: String,
    val apiLevel: Int,
) {
    companion object {
        /**
         * 读取 YukiHook 注入的模块状态。回到前台时应再读一次。
         */
        fun load(): XposedStatusInfo = XposedStatusInfo(
            active = YukiHookAPI.Status.isModuleActive,
            executorName = YukiHookAPI.Status.Executor.name,
            apiLevel = YukiHookAPI.Status.Executor.apiLevel,
        )
    }
}

/**
 * 主页 Xposed 卡片文案规则。
 *
 * 动机：激活态才展示框架名；空白名称不能直接上屏。
 */
object XposedStatusCopy {
    /**
     * 未激活时只展示引导，不展示可能为空的框架名。
     */
    fun showExecutor(active: Boolean): Boolean = active

    /**
     * 框架名为空时回退到 Xposed，避免卡片出现空白副标题。
     */
    fun executorName(raw: String): String = raw.trim().ifBlank { "Xposed" }

    /**
     * API 为 0 视为未知，只保留框架名。
     *
     * @param name 已经过 [executorName] 处理的展示名
     * @param apiLevel 框架 API
     */
    fun executorLine(name: String, apiLevel: Int): String {
        require(name.isNotBlank()) { "executor display name is blank" }
        require(apiLevel >= 0) { "apiLevel must not be negative" }
        return if (apiLevel == 0) name else "$name · API $apiLevel"
    }
}
