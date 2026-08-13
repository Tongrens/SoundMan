package hk.uwu.soundman.hook.scopes.systemui.hidden.fakes;

/** 无参 `get()` 返回给定对象，并可抛出原始异常。 */
public final class FakeClassLoaderFactory {
    private final Object result;
    public RuntimeException throwOnGet;

    public FakeClassLoaderFactory(Object result) {
        this.result = result;
    }

    public Object get() {
        if (throwOnGet != null) {
            throw throwOnGet;
        }
        return result;
    }
}
