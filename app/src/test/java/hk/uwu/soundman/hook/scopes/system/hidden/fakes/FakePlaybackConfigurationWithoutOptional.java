package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/** 只有公开 uid / isActive，缺少 piid 与 player proxy。 */
public final class FakePlaybackConfigurationWithoutOptional {
    private final int clientUid;
    private final boolean active;

    public FakePlaybackConfigurationWithoutOptional(int clientUid, boolean active) {
        this.clientUid = clientUid;
        this.active = active;
    }

    public int getClientUid() {
        return clientUid;
    }

    public boolean isActive() {
        return active;
    }
}
