package hk.uwu.soundman.hook.scopes.systemui.hidden.fakes;

/**
 * 具备 `getPackage()` 和 `mPluginFactory` 的假 PluginInstance。
 */
public final class FakePluginInstance {
    public final Object mPluginFactory;
    private final String packageName;
    public RuntimeException throwOnGetPackage;

    public FakePluginInstance(String packageName, Object pluginFactory) {
        this.packageName = packageName;
        this.mPluginFactory = pluginFactory;
    }

    public String getPackage() {
        if (throwOnGetPackage != null) {
            throw throwOnGetPackage;
        }
        return packageName;
    }
}
