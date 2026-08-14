package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/**
 * getAudioAttributes 返回 null，用于验证媒体探测跳过该条。
 */
public final class FakeMediaPlaybackConfigurationNullAttributes {
    public int getClientUid() {
        return 10100;
    }

    public boolean isActive() {
        return true;
    }

    public int getPlayerState() {
        return 2;
    }

    public FakeAudioAttributes getAudioAttributes() {
        return null;
    }
}
