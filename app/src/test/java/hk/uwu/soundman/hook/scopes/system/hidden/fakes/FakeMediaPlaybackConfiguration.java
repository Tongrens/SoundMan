package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/**
 * 具备 uid / playerState / AudioAttributes / 可选 piid 与 player proxy 的媒体探测假配置。
 */
public final class FakeMediaPlaybackConfiguration {
    private final int clientUid;
    private final int playerState;
    private final FakeAudioAttributes audioAttributes;
    private final int playerInterfaceId;
    private final Object playerProxy;

    public FakeMediaPlaybackConfiguration(
            int clientUid,
            int playerState,
            int usage,
            int volumeControlStream,
            int playerInterfaceId,
            Object playerProxy
    ) {
        this.clientUid = clientUid;
        this.playerState = playerState;
        this.audioAttributes = new FakeAudioAttributes(usage, volumeControlStream);
        this.playerInterfaceId = playerInterfaceId;
        this.playerProxy = playerProxy;
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

    public int getPlayerInterfaceId() {
        return playerInterfaceId;
    }

    public Object getPlayerProxy() {
        return playerProxy;
    }
}
