package com.winlator.xserver;

import com.winlator.XServerDisplayActivity;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.core.CursorLocker;
import com.winlator.renderer.GLRenderer;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.extensions.BigReqExtension;
import com.winlator.xserver.extensions.DRI3Extension;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.extensions.GLXExtension;
import com.winlator.xserver.extensions.GenericEventExtension;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XComposite;
import com.winlator.xserver.extensions.XInputExtension;
import com.termux.x11.LorieView;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    private static final boolean ENABLE_CURSOR_LOCKER = false;
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final XServerDisplayActivity activity;
    private final Extension[] extensions;
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private GLRenderer renderer;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;

    public XServer(XServerDisplayActivity activity, ScreenInfo screenInfo) {
        this.activity = activity;
        this.screenInfo = screenInfo;
        cursorLocker = ENABLE_CURSOR_LOCKER ? new CursorLocker(this) : null;
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        extensions = setupExtensions();
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        if (cursorLocker != null) cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (Extension extension : extensions) if (extension.getName().equals(name)) return extension;
        return null;
    }

    // ---- input bridge to the termux-x11 X server (libXlorie.so) -------------------------------
    // The Java X protocol implementation is no longer what wine talks to, so every injected event is
    // forwarded to the native server through the LorieView socket. The local Pointer/Keyboard state is
    // still maintained because the touchpad code reads pointer.getX()/getY() and the pinch math.

    private LorieView lorieView;

    public void setLorieView(LorieView lorieView) {
        this.lorieView = lorieView;
    }

    public LorieView getLorieView() {
        return lorieView;
    }

    public void injectPointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
            LorieView view = lorieView;
            if (view != null) view.sendMouseEvent(pointer.getX(), pointer.getY(), LorieView.BUTTON_UNDEFINED, false, false);
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
            if (cursorLocker == null) pointer.clampPosition();

            LorieView view = lorieView;
            if (view != null) view.sendMouseEvent(pointer.getX(), pointer.getY(), LorieView.BUTTON_UNDEFINED, false, false);
        }
    }

    // 鼠标键引用计数（借鉴触控优化版）：多个元素按住同一键时，只有最后一个释放才真正松开，
    // 避免单独的鼠标键按钮点击静默打断点击触摸板的按住状态
    private int lbtn;
    private int rbtn;

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        if (buttonCode == Pointer.Button.BUTTON_LEFT) lbtn++;
        else if (buttonCode == Pointer.Button.BUTTON_RIGHT) rbtn++;
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);

            LorieView view = lorieView;
            if (view != null) {
                // termux-x11 has no scroll buttons: the wheel is detail==4 carrying a relative
                // valuator that the native side divides by 120 (cmdentrypoint.cpp EVENT_MOUSE case 4),
                // so one notch has to be sent as a full 120 units here.
                if (buttonCode == Pointer.Button.BUTTON_SCROLL_UP) view.sendMouseWheelEvent(0, WHEEL_NOTCH);
                else if (buttonCode == Pointer.Button.BUTTON_SCROLL_DOWN) view.sendMouseWheelEvent(0, -WHEEL_NOTCH);
                else if (buttonCode == Pointer.Button.BUTTON_SCROLL_CLICK_LEFT) view.sendMouseWheelEvent(WHEEL_NOTCH, 0);
                else if (buttonCode == Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT) view.sendMouseWheelEvent(-WHEEL_NOTCH, 0);
                else view.sendMouseEvent(pointer.getX(), pointer.getY(), lorieButton(buttonCode), true, false);
            }
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        // 左/右键走引用计数：只有最后一个持有者释放时才真正松开；中键/滚轮不计数，直接释放
        if (buttonCode == Pointer.Button.BUTTON_LEFT) {
            if (lbtn == 0) return;
            if (lbtn > 1) {
                lbtn--;
                return;
            }
            lbtn = 0;
        }
        else if (buttonCode == Pointer.Button.BUTTON_RIGHT) {
            if (rbtn == 0) return;
            if (rbtn > 1) {
                rbtn--;
                return;
            }
            rbtn = 0;
        }
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);

            LorieView view = lorieView;
            if (view != null && buttonCode != Pointer.Button.BUTTON_SCROLL_UP &&
                buttonCode != Pointer.Button.BUTTON_SCROLL_DOWN &&
                buttonCode != Pointer.Button.BUTTON_SCROLL_CLICK_LEFT &&
                buttonCode != Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT) {
                view.sendMouseEvent(pointer.getX(), pointer.getY(), lorieButton(buttonCode), false, false);
            }
        }
    }

    /** Maps winlator's button enum onto termux-x11's pointer detail values (1=left, 2=middle, 3=right). */
    private static int lorieButton(Pointer.Button button) {
        switch (button) {
            case BUTTON_LEFT: return LorieView.BUTTON_LEFT;
            case BUTTON_MIDDLE: return LorieView.BUTTON_MIDDLE;
            case BUTTON_RIGHT: return LorieView.BUTTON_RIGHT;
            default: return LorieView.BUTTON_UNDEFINED;
        }
    }

    /** One wheel notch in the units termux-x11 expects (it divides by 120 before the X valuator). */
    private static final float WHEEL_NOTCH = 120f;

    private static final int XK_RETURN = 0xFF0D;
    private static final int XK_TAB = 0xFF09;
    private static final int XK_BACKSPACE = 0xFF08;
    private static final int XK_ESCAPE = 0xFF1B;
    private static final int XK_DELETE = 0xFFFF;
    private static final int XK_UNICODE_PREFIX = 0x01000000;
    private static final int LINUX_KEYCODE_OFFSET = 8;

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        LorieView view = lorieView;
        if (view == null) return;

        // A real character that is not reachable through the X keymap (CJK, symbols typed through the
        // IME) goes through the unicode channel; everything else is a plain keycode press, which keeps
        // modifier combinations such as Ctrl+V working.
        int codePoint = unicodeCodePoint(keysym);
        if (codePoint > 0) {
            view.sendTextEvent(new String(Character.toChars(codePoint)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }

        // lorie expects the linux input keycode, which is the X keycode minus the 8 offset of the
        // core protocol range; passing 0 as scanCode makes it fall back to the android_to_linux table.
        view.sendKeyEvent(xKeycode.id - LINUX_KEYCODE_OFFSET, androidKeyCodeForKeysym(keysym), true);
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        LorieView view = lorieView;
        if (view == null) return;
        view.sendKeyEvent(xKeycode.id - LINUX_KEYCODE_OFFSET, 0, false);
    }

    /** Returns the code point to type as text, or 0 when the press must stay a keycode press. */
    private static int unicodeCodePoint(int keysym) {
        if (keysym <= 0 || keysym >= 0xFF00) return 0;
        if (keysym >= XK_UNICODE_PREFIX && keysym <= XK_UNICODE_PREFIX + 0x10FFFF) return keysym - XK_UNICODE_PREFIX;
        if (keysym > 0x7F) return keysym; // latin-1 supplement, not present in the US keymap
        return 0;
    }

    private static int androidKeyCodeForKeysym(int keysym) {
        switch (keysym) {
            case XK_RETURN: return android.view.KeyEvent.KEYCODE_ENTER;
            case XK_TAB: return android.view.KeyEvent.KEYCODE_TAB;
            case XK_BACKSPACE: return android.view.KeyEvent.KEYCODE_DEL;
            case XK_ESCAPE: return android.view.KeyEvent.KEYCODE_ESCAPE;
            case XK_DELETE: return android.view.KeyEvent.KEYCODE_FORWARD_DEL;
            default: return 0;
        }
    }

    private Extension[] setupExtensions() {
        byte opcode = Extension.START_MAJOR_OPCODE;
        Extension[] extensions = new Extension[]{
            new BigReqExtension(this, opcode--),
            new MITSHMExtension(this, opcode--),
            new DRI3Extension(this, opcode--),
            new PresentExtension(this, opcode--),
            new SyncExtension(this, opcode--),
            new XComposite(this, opcode--),
            new GLXExtension(this, opcode--),
            new GenericEventExtension(this, opcode--),
            new XInputExtension(this, opcode--)
        };

        short nextEventId = 64;
        short nextErrorId = 128;
        for (Extension extension : extensions) {
            byte eventCount = extension.getEventCount();
            byte errorCount = extension.getErrorCount();
            if (eventCount > 0) {
                extension.setFirstEventId((byte)nextEventId);
                nextEventId += eventCount;
            }
            if (errorCount > 0) {
                extension.setFirstErrorId((byte)nextErrorId);
                nextErrorId += errorCount;
            }
        }
        return extensions;
    }

    public <T extends Extension> T getExtension(byte opcode) {
        int index = Extension.START_MAJOR_OPCODE - opcode;
        return (T)extensions[index];
    }

    public <T extends Extension> T getExtension(Class<T> extensionClass) {
        for (Extension extension : extensions) {
            if (extension.getClass() == extensionClass) return (T)extension;
        }
        return null;
    }

    public void debugPrint(String line) {
        DebugDialog debugDialog = activity.getDebugDialog();
        if (debugDialog != null) debugDialog.call("xserver:"+line);
    }
}
