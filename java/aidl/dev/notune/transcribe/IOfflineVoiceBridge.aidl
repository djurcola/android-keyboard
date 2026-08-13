package dev.notune.transcribe;

import android.app.PendingIntent;
import dev.notune.transcribe.IOfflineVoiceBridgeCallback;

interface IOfflineVoiceBridge {
    String pair();
    PendingIntent requestForegroundStart(String capability);
    boolean isForegroundReady(String capability);
    void start(String capability, IOfflineVoiceBridgeCallback callback);
    void stop(String capability);
    void cancel(String capability);
    boolean isPaired(String capability);
}
