package hk.uwu.soundman.internal.ipc;

import android.os.Bundle;

/** Client callback for all versioned SoundMan host events. */
oneway interface ISoundManClientCallback {
    void onEvent(String event, in Bundle payload);
}
