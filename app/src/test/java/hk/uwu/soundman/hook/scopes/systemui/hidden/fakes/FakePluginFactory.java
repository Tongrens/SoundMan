package hk.uwu.soundman.hook.scopes.systemui.hidden.fakes;

/** 具备 `mClassLoaderFactory` 的假 PluginFactory。 */
public final class FakePluginFactory {
    public final Object mClassLoaderFactory;

    public FakePluginFactory(Object classLoaderFactory) {
        this.mClassLoaderFactory = classLoaderFactory;
    }
}
