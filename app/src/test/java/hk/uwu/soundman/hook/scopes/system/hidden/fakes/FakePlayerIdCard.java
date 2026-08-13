package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/** 与 framework PlayerIdCard 一样持有 mIPlayer 字段。 */
public final class FakePlayerIdCard {
    public final Object mIPlayer;

    public FakePlayerIdCard(Object player) {
        this.mIPlayer = player;
    }
}
