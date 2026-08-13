package hk.uwu.soundman.hook.scopes.systemui.hidden.fakes;

/** 有 `getPackage()` 但没有 `mPluginFactory`。 */
public final class FakePluginInstanceWithoutFactory {
    private final String packageName;

    public FakePluginInstanceWithoutFactory(String packageName) {
        this.packageName = packageName;
    }

    public String getPackage() {
        return packageName;
    }
}
