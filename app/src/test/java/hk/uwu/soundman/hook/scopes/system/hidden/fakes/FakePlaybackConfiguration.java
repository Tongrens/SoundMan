package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/** 具备 uid / isActive / piid / player proxy 的完整假播放配置。 */
public final class FakePlaybackConfiguration {
    private final int clientUid;
    private final boolean active;
    private final int playerInterfaceId;
    private final Object playerProxy;

    public FakePlaybackConfiguration(int clientUid, boolean active, int playerInterfaceId, Object playerProxy) {
        this.clientUid = clientUid;
        this.active = active;
        this.playerInterfaceId = playerInterfaceId;
        this.playerProxy = playerProxy;
    }

    public int getClientUid() {
        return clientUid;
    }

    public boolean isActive() {
        return active;
    }

    public int getPlayerInterfaceId() {
        return playerInterfaceId;
    }

    public Object getPlayerProxy() {
        return playerProxy;
    }
}
