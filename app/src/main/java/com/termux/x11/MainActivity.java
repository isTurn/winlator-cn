package com.termux.x11;

/**
 * Minimal stand-in for termux-x11's MainActivity.
 *
 * libXlorie.so resolves this class by NAME from JNI (activity.cpp: nativeInit / JNI_OnLoad), so the
 * class, the "lastServerHeartbeat" long field, the clientConnectedStateChanged() method and the
 * nativeSetLaunchWindowingModeFreeform native method must exist with exactly these signatures.
 * It is deliberately NOT an android.app.Activity: winlator keeps XServerDisplayActivity as its UI.
 */
public class MainActivity {
    public static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";

    /** Written by native code on every EVENT_HEARTBEAT (SystemClock.uptimeMillis()). */
    public volatile long lastServerHeartbeat;

    /** Called by native when the LorieView event socket dies. */
    @SuppressWarnings("unused")
    public void clientConnectedStateChanged() {
        lastServerHeartbeat = 0;
    }

    @SuppressWarnings("unused")
    public static native void nativeSetLaunchWindowingModeFreeform(Object options);
}
