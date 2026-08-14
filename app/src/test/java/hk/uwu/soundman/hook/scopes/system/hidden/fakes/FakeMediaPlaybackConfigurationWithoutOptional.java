package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/**
 * 媒体探测必需字段齐全，缺少可选 piid / player proxy。
 */
public final class FakeMediaPlaybackConfigurationWithoutOptional {
    private final int clientUid;
    private final int playerState;
    private final FakeAudioAttributes audioAttributes;

    public FakeMediaPlaybackConfigurationWithoutOptional(int clientUid, int playerState, int usage, int volumeControlStream) {
        this.clientUid = clientUid;
        this.playerState = playerState;
        this.audioAttributes = new FakeAudioAttributes(usage, volumeControlStream);
    }

    public int getClientUid() {
        return clientUid;
    }

    public boolean isActive() {
        return playerState == 2;
    }

    public int getPlayerState() {
        return playerState;
    }

    public FakeAudioAttributes getAudioAttributes() {
        return audioAttributes;
    }
}
