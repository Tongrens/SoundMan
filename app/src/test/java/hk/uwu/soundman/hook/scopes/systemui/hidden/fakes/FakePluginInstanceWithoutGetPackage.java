package hk.uwu.soundman.hook.scopes.systemui.hidden.fakes;

/** 没有 `getPackage()`，用来验证缺方法立即失败。 */
public final class FakePluginInstanceWithoutGetPackage {
    public final Object mPluginFactory;

    public FakePluginInstanceWithoutGetPackage(Object pluginFactory) {
        this.mPluginFactory = pluginFactory;
    }
}
