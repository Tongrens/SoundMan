package hk.uwu.soundman.internal.ipc;

import android.os.Bundle;
import hk.uwu.soundman.internal.ipc.ISoundManClientCallback;

/** Authenticated system_server host API. Business commands are asynchronous. */
interface ISoundManHostService {
    int getProtocolVersion();
    void registerClient(int protocolVersion, ISoundManClientCallback callback);
    void unregisterClient(ISoundManClientCallback callback);
    oneway void requestSnapshot(String commandId);
    oneway void replaceRules(String commandId, long revision, in List<Bundle> rules);
    oneway void setVolume(String commandId, int uid, int percent);
    oneway void setRoute(String commandId, int uid, in Bundle target);
}
