package dev.notune.transcribe;

interface IOfflineVoiceBridgeCallback {
    void onState(int state);
    void onResult(String text);
    void onError(int code, String userMessage);
}
