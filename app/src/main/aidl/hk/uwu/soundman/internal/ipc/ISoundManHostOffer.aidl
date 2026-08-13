package hk.uwu.soundman.internal.ipc;

import android.os.IBinder;

/** App-side oneway mailbox. Host drops the session Binder and returns immediately. */
oneway interface ISoundManHostOffer {
    void onHostOffered(in IBinder hostBinder, int protocolVersion);
}
