package com.termux.x11;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.Keep;

import java.nio.charset.StandardCharsets;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

/**
 * Display surface of the libXlorie X server.
 *
 * This is a trimmed port of termux-x11's LorieView: it keeps the exact native contract (the 30
 * methods registered by activity.cpp in JNI_OnLoad, the "activity" field, resetIme() and the
 * clipboard/sync callbacks) but drops termux-x11's preference, IME and input-strategy stack, because
 * winlator owns input handling (XServer.inject*) and window management.
 *
 * Rendering: native code draws the X root window into this Surface through ANativeWindow, so a plain
 * SurfaceView is enough - there is no GL context here.
 */
public class LorieView extends SurfaceView {
    private static final String TAG = "XlorieView";

    public static final int BUTTON_UNDEFINED = 0;
    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_MIDDLE = 2;
    public static final int BUTTON_RIGHT = 3;
    public static final int BUTTON_SCROLL = 4;

    /** Required by libXlorie.so: GetFieldID(LorieView, "activity", "Lcom/termux/x11/MainActivity;"). */
    public MainActivity activity;

    public interface Callback {
        /** Called when the X server event socket goes away. */
        void onConnectionChanged(boolean connected);
        /** Called from native code, on the main thread, whenever the drawn picture changes. */
        void onViewportChanged(Rect viewport);
    }

    private Callback callback;
    private long mNativeContext;
    private final ClipboardManager clipboard;

    private int screenWidth = 1280;
    private int screenHeight = 1024;
    private String screenName = "builtin";

    private final Rect viewport = new Rect();
    private volatile int inputSourceLeft, inputSourceTop, inputSourceWidth, inputSourceHeight;

    private boolean clipboardSyncEnabled;
    private long lastClipboardTimestamp;

    public LorieView(Context context) {
        super(context);
        activity = new MainActivity();
        clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(surfaceCallback);
        mNativeContext = nativeInit();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** Size of the X screen to advertise to the server; call before/after connecting, it is re-sent. */
    public void setScreenSize(int width, int height, String name) {
        screenWidth = Math.max(8, width);
        screenHeight = Math.max(8, height);
        if (name != null && !name.isEmpty()) screenName = name;
        if (mNativeContext != 0) {
            updateViewport();
            sendWindowChange();
        }
    }

    public Rect getViewport() {
        return new Rect(viewport);
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mNativeContext == 0) return;
            LorieView.this.surfaceChanged(mNativeContext, holder.getSurface());
            updateViewport();
            sendWindowChange();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (mNativeContext == 0) return;
            LorieView.this.surfaceChanged(mNativeContext, null);
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        if (mNativeContext != 0) {
            nativeDestroy(mNativeContext);
            mNativeContext = 0;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateViewport();
    }

    /**
     * Places the X screen inside this view preserving the aspect ratio (letterboxed) and reports the
     * rectangle to the renderer, which is what actually decides where pixels land.
     */
    public void updateViewport() {
        if (mNativeContext == 0) return;

        int surfaceW = getWidth();
        int surfaceH = getHeight();
        if (surfaceW <= 0 || surfaceH <= 0) return;

        float scale = Math.min((float) surfaceW / screenWidth, (float) surfaceH / screenHeight);
        int w = Math.max(1, Math.round(screenWidth * scale));
        int h = Math.max(1, Math.round(screenHeight * scale));
        int left = (surfaceW - w) / 2;
        int top = (surfaceH - h) / 2;

        viewport.set(left, top, left + w, top + h);
        inputSourceLeft = 0;
        inputSourceTop = 0;
        inputSourceWidth = screenWidth;
        inputSourceHeight = screenHeight;

        setViewport(mNativeContext, left, top, w, h, screenWidth, screenHeight, 0);
    }

    /** Pushes the X screen resolution to the server (only way to change it at runtime). */
    public void sendWindowChange() {
        if (mNativeContext == 0) return;
        int framerate = 60;
        if (getDisplay() != null) framerate = (int) getDisplay().getRefreshRate();
        sendWindowChange(mNativeContext, screenWidth, screenHeight, framerate, screenName);
    }

    // ---------------------------------------------------------------- connection

    public void connect(int fd) {
        connect(mNativeContext, fd);
    }

    public boolean connected() {
        return connected(mNativeContext);
    }

    public boolean requestConnection() {
        return requestConnection(mNativeContext);
    }

    // ---------------------------------------------------------------- display zoom

    // Set while a pinch gesture is in flight. libXlorie re-derives the pan offset from the anchor
    // on every frame, so the anchor only has to be captured once per gesture: afterwards each
    // update just moves the fraction, which is what makes the content follow the fingers.
    private boolean pinchActive;
    private float pinchAnchorSourceX, pinchAnchorSourceY;

    /**
     * Scales the X screen inside this view without touching the guest resolution.
     *
     * <p>The point of the X screen that sits under ({@code viewX}, {@code viewY}) is kept pinned
     * there, so the picture zooms around the fingers instead of around the middle of the screen.
     * Moving the pinch center also pans, because the pinned point is placed at the new fraction of
     * the viewport. This is the libXlorie renderer zoom, which is independent of Wine's own
     * resolution: nothing in the guest is resized and no window is recreated.
     *
     * @param zoom 1.0 for the unzoomed picture, up to 4.0 (libXlorie clamps it the same way)
     */
    public void setPinchZoom(float zoom, float viewX, float viewY) {
        if (mNativeContext == 0) return;

        final float clamped = Math.max(1f, Math.min(4f, zoom));
        final int viewportWidth = viewport.width(), viewportHeight = viewport.height();
        float fracX = viewportWidth > 0 ? (viewX - viewport.left) / (float) viewportWidth : 0.5f;
        float fracY = viewportHeight > 0 ? (viewY - viewport.top) / (float) viewportHeight : 0.5f;
        fracX = Math.max(0f, Math.min(1f, fracX));
        fracY = Math.max(0f, Math.min(1f, fracY));

        if (!pinchActive) {
            pinchActive = true;
            pinchAnchorSourceX = inputSourceLeft + fracX * inputSourceWidth;
            pinchAnchorSourceY = inputSourceTop + fracY * inputSourceHeight;
        }

        setRendererZoom(mNativeContext, Math.round(clamped * 100f));
        setZoomAnchor(mNativeContext, pinchAnchorSourceX, pinchAnchorSourceY, fracX, fracY);
    }

    /** Drops the display zoom and hands panning back to the cursor-tracking default. */
    public void resetPinchZoom() {
        pinchActive = false;
        if (mNativeContext == 0) return;
        clearZoomAnchor(mNativeContext);
        setRendererZoom(mNativeContext, 100);
    }

    // ---------------------------------------------------------------- input

    public void sendMouseEvent(float x, float y, int whichButton, boolean buttonDown, boolean relative) {
        sendMouseEvent(mNativeContext, x, y, whichButton, buttonDown, relative);
    }

    public void sendMouseWheelEvent(float deltaX, float deltaY) {
        sendMouseEvent(deltaX, deltaY, BUTTON_SCROLL, false, true);
    }

    public void sendTouchEvent(int action, int id, int x, int y) {
        sendTouchEvent(mNativeContext, action, id, x, y);
    }

    public boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown) {
        return sendKeyEvent(mNativeContext, scanCode, keyCode, keyDown);
    }

    public void sendTextEvent(byte[] text) {
        sendTextEvent(mNativeContext, text);
    }

    public void sendSync(int serial) {
        sendSync(mNativeContext, serial);
    }

    public void setFiltering(int filtering) {
        setFiltering(mNativeContext, filtering);
    }

    // ---------------------------------------------------------------- clipboard

    public void setClipboardSyncEnabled(boolean enabled) {
        clipboardSyncEnabled = enabled;
        if (mNativeContext != 0) setClipboardSyncEnabled(mNativeContext, enabled, enabled);
    }

    /** Native callback: the X server wants to put text into the Android clipboard. */
    @Keep
    @SuppressWarnings("unused")
    private void setClipboardText(String text) {
        if (clipboard == null || text == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("X11", text));
        lastClipboardTimestamp = System.currentTimeMillis() + 150;
    }

    /** Native callback: the X server wants the Android clipboard contents. A reply is mandatory. */
    @Keep
    @SuppressWarnings("unused")
    private void requestClipboard() {
        String text = "";
        if (clipboard != null) {
            try {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence value = clip.getItemAt(0).coerceToText(getContext());
                    if (value != null) text = value.toString();
                }
            }
            catch (Exception e) {
                Log.w(TAG, "requestClipboard failed", e);
            }
        }
        sendClipboardEvent(mNativeContext, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Native callback: a barrier previously sent with sendSync() has been applied. */
    @Keep
    @SuppressWarnings("unused")
    private void onSyncReply(int serial) {
    }

    /** Native callback: the server focused another X window, so pending IME state must be dropped. */
    @Keep
    @SuppressWarnings("unused")
    void resetIme() {
    }

    /** Native callback: the rectangle the renderer actually drew into. */
    @Keep
    @SuppressWarnings("unused")
    private void setRendererViewport(int left, int top, int width, int height,
                                     float sourceLeft, float sourceTop, float sourceWidth, float sourceHeight) {
        viewport.set(left, top, left + width, top + height);
        Callback cb = callback;
        if (cb != null) cb.onViewportChanged(new Rect(viewport));
    }

    // ---------------------------------------------------------------- native

    // The full 28-entry table registered by activity.cpp in JNI_OnLoad. RegisterNatives fails as a
    // whole if even one of these is missing from the class, so none of them may be dropped even when
    // winlator does not call it.
    @FastNative private native long nativeInit();
    @FastNative private native void nativeDestroy(long ptr);
    @FastNative private native void surfaceChanged(long ptr, Surface surface);
    @FastNative private native void setFiltering(long ptr, int filtering);
    @FastNative private native void setClipboardSyncEnabled(long ptr, boolean enabled, boolean ignored);
    @FastNative private native void sendClipboardAnnounce(long ptr);
    @FastNative private native void sendClipboardEvent(long ptr, byte[] text);
    @FastNative private native void sendWindowChange(long ptr, int width, int height, int framerate, String name);
    @FastNative private native void setViewport(long ptr, int x, int y, int width, int height, int expectedWidth, int expectedHeight, int hiddenBottom);
    @FastNative private native void setRendererZoom(long ptr, int percent);
    @FastNative private native void setZoomAnchor(long ptr, float sourceX, float sourceY, float fracX, float fracY);
    @FastNative private native void clearZoomAnchor(long ptr);
    @FastNative private native void setFollowCursorPan(long ptr, boolean enabled);
    @FastNative private native long getCursorPosition(long ptr);
    @FastNative private native void sendSync(long ptr, int serial);
    @FastNative private native void sendMouseEvent(long ptr, float x, float y, int whichButton, boolean buttonDown, boolean relative);
    @FastNative private native void sendTouchEvent(long ptr, int action, int id, int x, int y);
    @FastNative private native void sendStylusEvent(long ptr, float x, float y, int pressure, int tiltX, int tiltY, int orientation, int buttons, boolean eraser, boolean mouseMode);
    @FastNative private native void requestStylusEnabled(long ptr, boolean enabled);
    @FastNative private native void sendLockKeysState(long ptr, int state);
    @FastNative private native boolean sendKeyEvent(long ptr, int scanCode, int keyCode, boolean keyDown);
    @FastNative private native void sendTextEvent(long ptr, byte[] text);
    @FastNative private static native void connect(long ptr, int fd);
    @CriticalNative private static native boolean connected(long ptr);
    @FastNative private static native void startLogcat(long ptr, int fd);
    @CriticalNative private static native boolean requestConnection(long ptr);
    @CriticalNative public static native long getLastInputTimestamp();
    @CriticalNative public static native void markUserActivity();

    static {
        System.loadLibrary("Xlorie");
    }
}
