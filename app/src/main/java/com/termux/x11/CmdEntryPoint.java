package com.termux.x11;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import dalvik.annotation.optimization.CriticalNative;

/**
 * Entry point of the libXlorie X server.
 *
 * In termux-x11 this class is started from a shell loader inside its own app_process; here winlator
 * starts it in-process from a Looper thread (see XlorieServerComponent). The native side only needs
 * the five natives below plus the three callback methods sendBroadcast()/sendBroadcastDelayed()/
 * setDocumentTag(String) (cmdentrypoint.cpp looks them up with GetMethodID).
 */
public class CmdEntryPoint {
    private static final String TAG = "XlorieServer";

    // JNI_OnLoad of libXlorie.so registers the natives of CmdEntryPoint, LorieView and MainActivity.
    // Loading the library from this class (and not relying on LorieView being constructed first)
    // makes the order irrelevant: start() runs on a background thread that may well execute before
    // the UI, and a native method called without a preceding load would throw UnsatisfiedLinkError.
    static {
        System.loadLibrary("Xlorie");
    }

    public static final String ACTION_START = "com.termux.x11.CmdEntryPoint.ACTION_START";
    public static final String EXTRA_DOCUMENT_TAG = "document_tag";

    /** Last document tag reported by the X server ("builtin" for the default display). */
    private static volatile String documentTag;

    /** Native start(): boots dix_main on its own thread, args are the usual X server arguments. */
    public native boolean start(String[] args);

    public native ParcelFileDescriptor getXConnection();

    public native ParcelFileDescriptor getLogcatOutput();

    public native void reportFatalError(String message);

    @CriticalNative
    public static native boolean connected();

    // Native callbacks: the X server knocks on TCP to tell Java a client connected.
    // winlator drives the display itself, so nothing has to be broadcast here.
    @SuppressWarnings("unused")
    public void sendBroadcast() {
        Log.d(TAG, "X server reports a new client connection");
    }

    @SuppressWarnings("unused")
    public void sendBroadcastDelayed() {
    }

    @SuppressWarnings("unused")
    public void setDocumentTag(String tag) {
        documentTag = tag;
    }

    public static String getDocumentTag() {
        return documentTag;
    }
}
