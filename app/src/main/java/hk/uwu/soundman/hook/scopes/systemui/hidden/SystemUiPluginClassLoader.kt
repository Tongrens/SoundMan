package hk.uwu.soundman.hook.scopes.systemui.hidden

import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets.FIELD_CLASS_LOADER_FACTORY
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets.FIELD_PLUGIN_FACTORY
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets.GET_PACKAGE
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets.METHOD_GET
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 从 SystemUI `PluginInstance` 取出插件包名和插件 ClassLoader。
 *
 * 动机：HyperOS 音量侧栏类跑在 SystemUI 进程里，由插件 ClassLoader 加载；
 * `PluginInstance` / `PluginFactory` 不在编译 classpath，也没有可编译的公开 API，
 * 因此必须按 factory 链做 hidden 访问。提取器本身缺成员就立即失败，
 * 由 hooker 捕获并打日志，避免异常打穿 SystemUI。
 *
 * 反射理由：factory 具体类型会随 ROM / 插件 reload 变化，因此字段和方法都按 runtime class 查找并缓存。
 */
class SystemUiPluginClassLoader {
    private val fields = ConcurrentHashMap<MemberKey, Field>()
    private val methods = ConcurrentHashMap<MemberKey, Method>()

    /**
     * 调用 `getPackage()` 读取插件包名。
     *
     * @param pluginInstance `PluginInstance` 运行时对象
     * @return `getPackage()` 的返回值
     */
    fun packageName(pluginInstance: Any): String {
        val instanceClass = pluginInstance.javaClass
        val result = invoke(methodOf(instanceClass, GET_PACKAGE), pluginInstance)
        return result as? String
            ?: throw IllegalStateException(
                "Method $GET_PACKAGE() on ${instanceClass.name} returned non-String: " +
                    "${result?.javaClass?.name}",
            )
    }

    /**
     * 沿 `mPluginFactory` → `mClassLoaderFactory` → `get()` 取出插件 ClassLoader。
     *
     * @param pluginInstance `PluginInstance` 运行时对象
     * @return factory `get()` 返回的 ClassLoader
     */
    fun classLoader(pluginInstance: Any): ClassLoader {
        val factory = fieldValue(pluginInstance, FIELD_PLUGIN_FACTORY)
            ?: throw IllegalStateException(
                "Field $FIELD_PLUGIN_FACTORY on ${pluginInstance.javaClass.name} is null",
            )
        val classLoaderFactory = fieldValue(factory, FIELD_CLASS_LOADER_FACTORY)
            ?: throw IllegalStateException(
                "Field $FIELD_CLASS_LOADER_FACTORY on ${factory.javaClass.name} is null",
            )
        val result = invoke(methodOf(classLoaderFactory.javaClass, METHOD_GET), classLoaderFactory)
        return result as? ClassLoader
            ?: throw IllegalStateException(
                "Method $METHOD_GET() on ${classLoaderFactory.javaClass.name} returned non-ClassLoader: " +
                    "${result?.javaClass?.name}",
            )
    }

    private fun fieldValue(instance: Any, name: String): Any? =
        fieldOf(instance.javaClass, name).get(instance)

    private fun fieldOf(clazz: Class<*>, name: String): Field =
        fields.getOrPut(MemberKey(clazz, name)) { resolveDeclaredField(clazz, name) }

    private fun methodOf(clazz: Class<*>, name: String): Method =
        methods.getOrPut(MemberKey(clazz, name)) { resolveNoArgMethod(clazz, name) }

    private fun invoke(method: Method, instance: Any): Any? = try {
        method.invoke(instance)
    } catch (error: InvocationTargetException) {
        throw error.targetException ?: error
    }

    private data class MemberKey(
        val clazz: Class<*>,
        val name: String,
    )

    private companion object {
        fun resolveDeclaredField(clazz: Class<*>, name: String): Field {
            val field = try {
                clazz.getDeclaredField(name)
            } catch (error: NoSuchFieldException) {
                throw IllegalStateException("Missing field $name on ${clazz.name}", error)
            }
            field.isAccessible = true
            return field
        }

        fun resolveNoArgMethod(clazz: Class<*>, name: String): Method {
            val method = try {
                clazz.getDeclaredMethod(name)
            } catch (declaredMissing: NoSuchMethodException) {
                try {
                    clazz.getMethod(name)
                } catch (publicMissing: NoSuchMethodException) {
                    throw IllegalStateException("Missing method $name() on ${clazz.name}", publicMissing)
                }
            }
            method.isAccessible = true
            return method
        }
    }
}
