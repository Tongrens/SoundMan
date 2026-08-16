package hk.uwu.soundman.hook.scopes.systemui.runtime

/**
 * 进程间入口的最小调用方策略。SystemUI 与模块可能共享系统 UID，故不能只按 UID 放行。
 */
object SystemUiSecurityPolicy {
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /**
     * 允许模块自身 UID；SystemUI 按经过 PackageManager 验证的包名识别，不假定其 UID 为 1000。
     *
     * HyperOS 可给 SystemUI 分配独立 UID，因此 [systemUid] 只保留为协议输入，不参与身份判定。
     * 当 ContentProvider 能提供调用包时必须精确匹配；旧系统拿不到调用包时，要求该 UID 的包集合
     * 明确包含且仅包含 SystemUI，避免把共享 UID 下的其他进程误认为 SystemUI。
     */
    fun isModuleOrSystemUi(
        callingUid: Int,
        moduleUid: Int,
        systemUid: Int,
        packagesForUid: Set<String>,
        callingPackage: String? = null,
    ): Boolean {
        if (callingUid == moduleUid) return true
        if (SYSTEM_UI_PACKAGE !in packagesForUid) return false
        return if (callingPackage != null) {
            callingPackage == SYSTEM_UI_PACKAGE
        } else {
            packagesForUid == setOf(SYSTEM_UI_PACKAGE)
        }
    }
}

/** 关闭或代际变化后，后台结果不得再触碰 SystemUI View。 */
object SystemUiGenerationGate {
    fun accepts(closed: Boolean, currentGeneration: Long, resultGeneration: Long): Boolean =
        !closed && currentGeneration == resultGeneration
}

/** 后台初始化或读取失败仅允许当前存活代际触发一次 Overlay 回退。 */
object SystemUiFallbackPolicy {
    fun shouldRequest(
        closed: Boolean,
        currentGeneration: Long,
        resultGeneration: Long,
        alreadyRequested: Boolean,
    ): Boolean = !alreadyRequested && SystemUiGenerationGate.accepts(
        closed,
        currentGeneration,
        resultGeneration
    )
}
