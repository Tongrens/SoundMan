package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/** 缺少 getClientUid，用于验证必需方法失败。 */
public final class FakePlaybackConfigurationWithoutClientUid {
    public boolean isActive() {
        return true;
    }
}
