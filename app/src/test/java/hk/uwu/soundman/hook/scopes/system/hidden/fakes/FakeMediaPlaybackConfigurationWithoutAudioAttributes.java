package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/**
 * 缺少 getAudioAttributes，用于验证媒体探测跳过该条。
 */
public final class FakeMediaPlaybackConfigurationWithoutAudioAttributes {
    public int getClientUid() {
        return 10100;
    }

    public boolean isActive() {
        return true;
    }

    public int getPlayerState() {
        return 2;
    }
}
