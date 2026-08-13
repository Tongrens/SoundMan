package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/** 具备 setVolume(float) 的假 IPlayer，记录调用参数并支持抛出原始异常。 */
public final class FakePlayer {
    public float lastVolume = Float.NaN;
    public int pauseCount;
    public int startCount;
    public RuntimeException throwOnSetVolume;

    public void pause() {
        pauseCount += 1;
    }

    public void start() {
        startCount += 1;
    }

    public void setVolume(float volume) {
        if (throwOnSetVolume != null) {
            throw throwOnSetVolume;
        }
        lastVolume = volume;
    }
}
