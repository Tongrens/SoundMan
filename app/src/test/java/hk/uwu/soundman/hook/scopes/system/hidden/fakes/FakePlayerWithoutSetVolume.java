package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/** 故意没有 setVolume(float)，验证方法缺失失败。 */
public final class FakePlayerWithoutSetVolume {
    public void setVolume(int volume) {
        throw new AssertionError("boxed/int overload must not be selected");
    }
}
