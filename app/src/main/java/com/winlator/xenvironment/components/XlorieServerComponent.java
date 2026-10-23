package com.winlator.xenvironment.components;

import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.termux.x11.CmdEntryPoint;
import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineRegistryEditor;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;
import com.winlator.xserver.XServer;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Boots termux-x11's real Xorg server (libXlorie.so) inside the winlator process and replaces
 * winlator's previous pure-Java X server.
 *
 * The native server is an ordinary Xorg dix_main: wine keeps connecting to the same unix socket
 * (rootfs/tmp/.X11-unix/X0), but now it talks to a full Xorg implementation with real extensions
 * (GLX, Present, DRI3, XInput2, RandR, Composite, ...) instead of the Java reimplementation.
 *
 * Two things the native side insists on (see cmdentrypoint.cpp in termux-x11):
 *  - TMPDIR must be set in the *process* environment, and dirname(TMPDIR) is treated as the rootfs
 *    root: <root>/usr/share/X11/xkb must exist or start() reports "$XKB_CONFIG_ROOT is not set".
 *  - The X socket lives at "$TMPDIR/.X11-unix/X0", which is exactly UnixSocketConfig.XSERVER_PATH
 *    relative to the rootfs.
 */
public class XlorieServerComponent extends EnvironmentComponent {
    private static final String TAG = "XlorieServer";

    /** termux-x11's xkeyboard-config data, extracted into <rootfs>/usr/share/X11/xkb on first use. */
    private static final String XKB_ASSET = "xkb.tzst";
    private static final String XKB_DIR = "/usr/share/X11/xkb";
    private static final String XKB_STAMP = "/usr/share/X11/xkb/.winlator-xkb";
    // Bumped whenever xkb.tzst gains or loses component directories. Version 2 added geometry/,
    // without which xkbcomp cannot build the keymap ("Can't find file "pc" for geometry include").
    private static final String XKB_STAMP_VERSION = "2";

    /**
     * Extra GStreamer plugins for the codecs the bundled FFmpeg build left out. The rootfs ships
     * GStreamer 1.25.1 plus a statically linked libgstlibav.so, but that FFmpeg was configured
     * without AC-3/E-AC-3, Vorbis, Opus, FLAC or any ADPCM decoder, and without an MPEG-TS
     * demuxer, so winegstreamer cannot play those streams at all. The distro plugins below are
     * dropped in next to the existing ones; nothing already present is overwritten, so a plugin
     * that fails to load simply leaves the previous behaviour intact.
     */
    private static final String GST_ASSET = "gst_codecs.tzst";
    private static final String GST_STAMP = "/usr/lib/gstreamer-1.0/.winlator-gst-codecs";
    private static final String GST_STAMP_VERSION = "1";

    private static final String WFM_ASSET = "wfm.tzst";
    private static final String WFM_PATH = "/home/xuser/.wine/drive_c/windows/wfm.exe";
    private static final String WFM_STAMP = "/home/xuser/.wine/drive_c/windows/.winlator-wfm";
    /** Bumped whenever wfm.tzst changes, so containers created earlier pick the new build up too. */
    private static final String WFM_STAMP_VERSION = "1.5-2.9";

    /**
     * Per-container payload unpacked straight into the wineprefix drive_c: the CJK fonts Windows
     * has and wine does not, plus the VC++ 2015-2022 / d3dx9 / OpenAL runtimes most games import.
     * These are per container because drive_c is per container; /home/xuser is a symlink to the
     * active container (ContainerManager.activateContainer), so the paths below resolve to
     * whichever container is starting right now.
     */
    private static final String ADDONS_ASSET = "container_addons.tzst";
    private static final String ADDONS_DRIVE_C = "/home/xuser/.wine/drive_c";
    private static final String ADDONS_STAMP = ADDONS_DRIVE_C + "/.winlator-addons";
    private static final String ADDONS_STAMP_VERSION = "1";

    private static final String WINEPREFIX_DIR = "/home/xuser/.wine";

    /**
     * There is exactly one native X server per process: dix_main is a singleton, the lock file is
     * process-wide and the X socket is a single path. Restarting the activity (new container, same
     * process) must therefore reuse the running server instead of starting a second one.
     */
    private static final Object LOCK = new Object();
    private static CmdEntryPoint sServer;

    private final XServer xServer;
    private CmdEntryPoint entryPoint;

    public XlorieServerComponent(XServer xServer) {
        this.xServer = xServer;
    }

    public CmdEntryPoint getEntryPoint() {
        return entryPoint;
    }

    /**
     * True once a native X server has been booted in this process. Callers that clear the rootfs tmp
     * directory have to consult this: the running server's listen socket lives there and unlinking it
     * would leave the fd bound to an unreachable path, so the next wine session could not connect.
     */
    public static boolean isServerRunning() {
        synchronized (LOCK) {
            return sServer != null;
        }
    }

    /** Name of the X socket directory that must survive a tmp cleanup while the server is running. */
    public static final String X11_SOCKET_DIR = ".X11-unix";

    /**
     * Empties the rootfs tmp directory, but keeps the X socket directory and the display lock file
     * when a native server already owns them. setupXEnvironment() clears tmp on every start; after
     * an activity recreate (external launch into another container, same process) that would unlink
     * .X11-unix/X0 under the running server, whose fd then stays bound to a path wine cannot reach.
     * xserver.patch makes the lock file follow TMPDIR (/.X<display>-lock), so it is guarded as well.
     */
    public static void clearTmpDir(File tmpDir) {
        if (tmpDir == null) return;
        File[] files = tmpDir.listFiles();
        if (files == null) return;

        final boolean keepServerFiles = isServerRunning();
        for (File file : files) {
            if (keepServerFiles) {
                String name = file.getName();
                if (name.equals(X11_SOCKET_DIR) || (name.startsWith(".X") && name.endsWith("-lock"))) continue;
            }
            FileUtils.delete(file);
        }
    }

    @Override
    public void start() {
        if (entryPoint != null) return;

        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();
        File tmpDir = rootFS.getTmpDir();
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
            throw new RuntimeException("Failed to create "+tmpDir);
        }
        FileUtils.chmod(tmpDir, 0771);

        installXkbData(rootDir);
        installMediaPlugins(rootDir);
        installWfm(rootDir);
        installContainerAddons(rootDir);

        // The native server reads these through getenv() in its own process, so they have to be set
        // with setenv() rather than in the guest program's env var list.
        ProcessHelper.setEnv("TMPDIR", tmpDir.getPath());
        ProcessHelper.setEnv("XKB_CONFIG_ROOT", rootDir.getPath()+XKB_DIR);
        Log.d(TAG, "TMPDIR="+tmpDir+" XKB_CONFIG_ROOT="+rootDir+XKB_DIR);

        // libXlorie decodes IME text with mbrtowc(); bionic's default "C" locale stops at ASCII and
        // silently drops every CJK character. The locale is process wide, so fixing it here also
        // fixes the copy inside libXlorie.
        final int mbCurMax = ProcessHelper.setUtf8Locale();
        if (mbCurMax > 1) Log.d(TAG, "UTF-8 locale installed, MB_CUR_MAX="+mbCurMax);
        else Log.w(TAG, "Could not install a UTF-8 locale, MB_CUR_MAX="+mbCurMax+"; only ASCII text can be typed");

        // The argv handed to dix_main. Every single token is parsed by dix's ProcessCommandLine,
        // and an unknown one is a hard FatalError -> exit() -> here even _exit() because
        // cmdentrypoint.cpp overrides exit(), i.e. the whole app disappears with no Java stack.
        //   ":0"       position argument, sets the display number (lock file .X0-lock, socket X0).
        //   -ac        disable access control, wine does not authenticate.
        //   -nolisten  THE LEADING DASH IS MANDATORY. os/utils.c only recognises "-nolisten";
        //              "nolisten" falls through to FatalError("Unrecognized option: nolisten").
        //   tcp        consumed by -nolisten as the transport name.
        //   -noreset   keep clients alive across the usual server reset.
        // NOTE: no -screen and no -logfile here, neither exists in this ddx and both FatalError.
        // termux-x11 sizes the root window through EVENT_SCREEN_SIZE instead
        // (LorieView.sendWindowChange), defaulting to 1280x1024.
        //
        // Neither -disable-gpu-present nor -legacy-drawing changed the "submenu items only appear
        // after hovering" report, and forcing a full repaint from the Java side did not either, so
        // the missing pixels are not in the framebuffer to begin with: the present/upload path and
        // the damage tracking are both innocent. Both switches are therefore left out again to keep
        // the GPU fast paths for D3D and for the texture upload. The remaining suspect is Wine's own
        // start menu painting/clipping, which is why the X server is started with plain defaults.
        final String[] args = {":0", "-ac", "-nolisten", "tcp", "-noreset"};
        startServerThread(args);
        waitForSocket(tmpDir);
        Log.d(TAG, "Xlorie X server started, screen "+xServer.screenInfo);
    }

    /**
     * dix_main can only run once per process and native's start() grabs AChoreographer for the
     * calling thread ("This must be called on an ALooper thread", NDK choreographer.h). The
     * choreographer is what drives the whole frame loop (every vsync queues lorieRedraw), so the
     * server has to live on a thread that owns a Looper and keeps pumping it. Hence a dedicated
     * thread instead of the shared background executor: Looper.prepare() -> start() -> loop().
     *
     * The native server is a process-wide singleton (static state, one dix_main, one lock file),
     * so re-entering here - e.g. after the activity was recreated for another container - reuses
     * the already running server and only opens a fresh X connection for the new LorieView.
     */
    private void startServerThread(final String[] args) {
        synchronized (LOCK) {
            if (sServer != null) {
                entryPoint = sServer;
                Log.d(TAG, "Reusing the already running Xlorie X server");
                return;
            }

            final CmdEntryPoint server = new CmdEntryPoint();
            final CountDownLatch ready = new CountDownLatch(1);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean started = new AtomicBoolean(false);

            Thread thread = new Thread(() -> {
                Looper.prepare();
                try {
                    started.set(server.start(args));
                }
                catch (Throwable e) {
                    failure.set(e);
                }
                finally {
                    // start() pins the calling thread to the upper half of the cores.
                    ProcessHelper.resetCpuAffinity();
                }
                ready.countDown();
                // Pump the Looper this thread's AChoreographer posts its vsync callbacks to.
                if (started.get()) Looper.loop();
            }, "XlorieServer");
            thread.setDaemon(true);
            thread.start();

            try {
                if (!ready.await(30, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Timed out starting the Xlorie X server");
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while starting the Xlorie X server", e);
            }

            Throwable error = failure.get();
            if (error != null) {
                throw new RuntimeException("Xlorie X server crashed on startup", error);
            }
            if (!started.get()) {
                throw new RuntimeException("Failed to start the Xlorie X server (see logcat for the missing TMPDIR/XKB_CONFIG_ROOT)");
            }

            sServer = server;
            entryPoint = server;
        }
    }

    /** Gives the X server its event socket. Called on the UI thread once the LorieView exists. */
    public int openXConnection() {
        if (entryPoint == null) return -1;
        ParcelFileDescriptor fd = entryPoint.getXConnection();
        return fd != null ? fd.detachFd() : -1;
    }

    @Override
    public void stop() {
        // dix_main cannot be shut down cleanly from Java; the process teardown takes care of it.
        entryPoint = null;
    }

    /**
     * dix_main runs on its own pthread and returns to Java long before Xtrans has bound the unix
     * socket, so wine would race against it. Block until the listener exists (or give up loudly).
     */
    private void waitForSocket(File tmpDir) {
        File socket = new File(new File(tmpDir, ".X11-unix"), "X0");
        for (int i = 0; i < 150; i++) {
            if (socket.exists()) {
                Log.d(TAG, "X socket ready: "+socket);
                return;
            }
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.e(TAG, "X socket "+socket+" did not appear within 15s; wine will probably fail to connect");
    }

    /**
     * Installs the bundled WFM file manager into the container's wineprefix.
     *
     * Wine's own explorer is what winlator uses as its shell, and WFM replaces the file dialogs and
     * the desktop window, so the build that ships with the app has to win over whatever an older
     * container already has on disk. The stamp keeps this a one-off per container instead of
     * rewriting a 540 KB binary on every launch.
     */
    private void installWfm(File rootDir) {
        File stamp = new File(rootDir, WFM_STAMP);
        if (WFM_STAMP_VERSION.equals(readStampFile(stamp))) return;

        File target = new File(rootDir, WFM_PATH);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "Could not create " + parent + ", skipping the bundled WFM");
            return;
        }

        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, environment.getContext(), WFM_ASSET, rootDir)) {
            // Not fatal: the container keeps the wfm.exe that rootfs_patches.tzst provided.
            Log.w(TAG, "Failed to extract " + WFM_ASSET);
            return;
        }

        Log.d(TAG, "Installed bundled WFM " + WFM_STAMP_VERSION + " at " + target);
        FileUtils.writeString(stamp, WFM_STAMP_VERSION);
    }

    /**
     * Unpacks the fonts and runtimes into the current container's drive_c and registers them.
     *
     * The fonts need registry entries under ...\CurrentVersion\Fonts, exactly like the Wine core
     * fonts that WineUtils writes, otherwise GDI never enumerates them.
     */
    private void installContainerAddons(File rootDir) {
        File driveC = new File(rootDir, ADDONS_DRIVE_C);
        File stamp = new File(rootDir, ADDONS_STAMP);
        if (ADDONS_STAMP_VERSION.equals(readStampFile(stamp))) return;

        if (!driveC.isDirectory()) {
            Log.w(TAG, "No wineprefix at "+driveC+", skipping the bundled container addons");
            return;
        }

        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, environment.getContext(), ADDONS_ASSET, driveC)) {
            Log.w(TAG, "Failed to extract "+ADDONS_ASSET);
            return;
        }

        registerBundledFonts(rootDir);

        FileUtils.writeString(stamp, ADDONS_STAMP_VERSION);
        Log.d(TAG, "Installed bundled container addons into "+driveC);
    }

    private void registerBundledFonts(File rootDir) {
        File systemRegFile = new File(rootDir, WINEPREFIX_DIR+"/system.reg");
        if (!systemRegFile.isFile()) return;

        final String[][] fonts = {
            {"Microsoft YaHei & Microsoft YaHei UI (TrueType)", "msyh.ttc"},
            {"Microsoft YaHei Bold & Microsoft YaHei UI Bold (TrueType)", "msyhbd.ttc"},
            {"SimHei (TrueType)", "simhei.ttf"},
            {"KaiTi (TrueType)", "simkai.ttf"},
            {"FangSong (TrueType)", "simfang.ttf"}
        };

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setStringValues("Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", fonts);
            registryEditor.setStringValues("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", fonts);
        }
        catch (Exception e) {
            Log.w(TAG, "Could not register the bundled CJK fonts", e);
        }
    }

    private void installXkbData(File rootDir) {
        File xkbDir = new File(rootDir, XKB_DIR);
        // xkbcomp needs every component directory, and a missing one is fatal: without
        // geometry/pc it reports `Can't find file "pc" for geometry include`, the keymap fails
        // to compile, and dix dies with "Failed to activate virtual core keyboard". The stamp
        // therefore records the asset generation, so an old extraction is replaced on upgrade.
        if (xkbDir.isDirectory() && XKB_STAMP_VERSION.equals(readStamp(rootDir))) return;

        FileUtils.delete(xkbDir);
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, environment.getContext(), XKB_ASSET, xkbDir)) {
            throw new RuntimeException("Failed to extract "+XKB_ASSET);
        }
        FileUtils.writeString(new File(rootDir, XKB_STAMP), XKB_STAMP_VERSION);
        Log.d(TAG, "Extracted "+XKB_ASSET+" to "+xkbDir);
    }

    /**
     * Adds the missing GStreamer decoder plugins to the rootfs. Failure is not fatal: wine simply
     * keeps reporting "no suitable decoder" for those streams, exactly like before this ran.
     */
    private void installMediaPlugins(File rootDir) {
        File stamp = new File(rootDir, GST_STAMP);
        if (GST_STAMP_VERSION.equals(readStampFile(stamp))) return;

        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, environment.getContext(), GST_ASSET, rootDir)) {
            Log.w(TAG, "Failed to extract "+GST_ASSET+"; AC3/Vorbis/Opus/FLAC/ADPCM playback stays unavailable");
            return;
        }
        FileUtils.writeString(stamp, GST_STAMP_VERSION);
        Log.d(TAG, "Extracted "+GST_ASSET+" into "+rootDir);
    }

    private static String readStamp(File rootDir) {
        return readStampFile(new File(rootDir, XKB_STAMP));
    }

    private static String readStampFile(File stamp) {
        if (!stamp.isFile()) return null;
        // FileUtils.readString(File) would NPE on an unreadable file, so read the bytes first.
        byte[] data = FileUtils.read(stamp);
        if (data == null) return null;
        return new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
    }
}
