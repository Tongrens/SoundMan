package hk.uwu.soundman.hook.scopes.system.hidden.fakes;

/** 故意缺少 getDeviceConnectionState，验证构造期方法缺失失败。 */
public final class FakeAudioSystemMissingMethod {
    public static final int DEVICE_STATE_AVAILABLE = 1;
    public static final int DEVICE_OUT_EARPIECE = 0x1;
    public static final int DEVICE_OUT_SPEAKER = 0x2;
    public static final int DEVICE_OUT_WIRED_HEADSET = 0x4;
    public static final int DEVICE_OUT_WIRED_HEADPHONE = 0x8;
    public static final int DEVICE_OUT_BLUETOOTH_SCO = 0x10;
    public static final int DEVICE_OUT_BLUETOOTH_A2DP = 0x80;
    public static final int DEVICE_OUT_USB_ACCESSORY = 0x2000;
    public static final int DEVICE_OUT_USB_DEVICE = 0x4000;
    public static final int DEVICE_OUT_USB_HEADSET = 0x4000000;
    public static final int DEVICE_OUT_BLE_HEADSET = 0x20000000;
    public static final int DEVICE_OUT_BLE_SPEAKER = 0x20000001;
    public static final int DEVICE_OUT_BLE_BROADCAST = 0x20000002;

    private FakeAudioSystemMissingMethod() {
    }

    public static int setUidDeviceAffinities(int uid, int[] deviceIds, String[] deviceAddresses) {
        return 0;
    }

    public static int removeUidDeviceAffinities(int uid) {
        return 0;
    }
}
