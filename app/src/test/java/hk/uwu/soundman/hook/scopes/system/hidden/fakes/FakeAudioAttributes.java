package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/**
 * 假 AudioAttributes，只暴露 usage 与 volumeControlStream。
 */
public final class FakeAudioAttributes {
    private final int usage;
    private final int volumeControlStream;

    public FakeAudioAttributes(int usage, int volumeControlStream) {
        this.usage = usage;
        this.volumeControlStream = volumeControlStream;
    }

    public int getUsage() {
        return usage;
    }

    public int getVolumeControlStream() {
        return volumeControlStream;
    }
}
