package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/**
 * 缺少 getPlayerState，用于验证媒体探测跳过该条。
 */
public final class FakeMediaPlaybackConfigurationWithoutPlayerState {
    public int getClientUid() {
        return 10100;
    }

    public boolean isActive() {
        return true;
    }

    public FakeAudioAttributes getAudioAttributes() {
        return new FakeAudioAttributes(1, 3);
    }
}
