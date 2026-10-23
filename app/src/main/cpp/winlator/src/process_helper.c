// cpu_set_t / CPU_ZERO / CPU_SET are GNU extensions.
#define _GNU_SOURCE

#include <jni.h>

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "winlator.h"

// libXlorie reads TMPDIR / XKB_CONFIG_ROOT through getenv() from native code, so the Java side needs
// a way to export them into the process environment before the X server starts.

// libXlorie's start() pins the calling thread to the upper half of the cores (termux-x11 runs the
// X server in its own process, so that is fine there). In winlator the same thread is the UI thread,
// so the mask has to be dropped again right after the server has been spawned.
JNIEXPORT void JNICALL Java_com_winlator_core_ProcessHelper_resetCpuAffinity(JNIEnv* env, jclass obj) {
    cpu_set_t mask;
    long numCpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (numCpus <= 0) return;

    CPU_ZERO(&mask);
    for (long i = 0; i < numCpus; i++) CPU_SET(i, &mask);
    sched_setaffinity(0, sizeof(cpu_set_t), &mask);
}

JNIEXPORT void JNICALL Java_com_winlator_core_ProcessHelper_setEnv(JNIEnv* env, jclass obj, jstring name, jstring value) {
    if (name == NULL) return;

    const char* nameChars = (*env)->GetStringUTFChars(env, name, NULL);
    if (nameChars == NULL) return;

    const char* valueChars = value == NULL ? NULL : (*env)->GetStringUTFChars(env, value, NULL);

    if (valueChars != NULL) {
        setenv(nameChars, valueChars, 1);
        (*env)->ReleaseStringUTFChars(env, value, valueChars);
    }
    else {
        unsetenv(nameChars);
    }

    (*env)->ReleaseStringUTFChars(env, name, nameChars);
}

// ---------------------------------------------------------------------------------------------
// X server stderr capture.
//
// libXlorie embeds the real Xorg dix_main, and dix reports every fatal startup problem through
// ErrorF()/FatalError(), i.e. on fd 2. libXlorie only forwards fd 2 to logcat when __progname is
// "com.termux.x11" (activity.cpp, JNI_OnLoad), so inside winlator those messages are written into a
// fd 2 nobody reads and simply vanish. On top of that cmdentrypoint.cpp replaces exit()/abort() with
// _exit(), so a FatalError("Unrecognized option: ...") makes the whole app disappear with no Java
// stack, no log and no tombstone - exactly the "crashes as soon as the container opens" symptom.
//
// Pointing fd 2 at a regular file is enough: the native crash handlers never touch fd 2, so the file
// outlives the process and the last line in it is the reason it died.
// ---------------------------------------------------------------------------------------------

#define XSERVER_LOG_TAG "XlorieNative"
#define XSERVER_LOG_MAX_BYTES (4L * 1024 * 1024)

static char g_xserverLogPath[512];
static int g_xserverLogFd = -1;

// fd 2 is a duplicate of g_xserverLogFd, so dropping our copy leaves the old capture intact until
// the next dup2 replaces it.
static void xserverLogClose(void) {
    if (g_xserverLogFd < 0) return;
    close(g_xserverLogFd);
    g_xserverLogFd = -1;
}

// Called by ProcessHelper.captureXServerStderr(path) right before the native server is started.
// Passing null restores nothing (there is no previous fd 2 to restore), it just stops appending.
JNIEXPORT void JNICALL Java_com_winlator_core_ProcessHelper_captureXServerStderr(JNIEnv* env, jclass obj, jstring path) {
    // fd 2 currently points at the previous log file; the dup2 below detaches it from our copy.
    xserverLogClose();

    if (path == NULL) return;

    const char* pathChars = (*env)->GetStringUTFChars(env, path, NULL);
    if (pathChars == NULL) return;

    snprintf(g_xserverLogPath, sizeof(g_xserverLogPath), "%s", pathChars);
    (*env)->ReleaseStringUTFChars(env, path, pathChars);

    int fd = open(g_xserverLogPath, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, XSERVER_LOG_TAG, "Cannot open %s: %s", g_xserverLogPath, strerror(errno));
        return;
    }

    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size > XSERVER_LOG_MAX_BYTES) {
        // Keep only the tail: that is where the next crash will be.
        (void) ftruncate(fd, 0);
    }

    // fd 2 is process wide and there is only one X server per process, so one dup2 covers every
    // thread dix spawns afterwards.
    if (dup2(fd, 2) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, XSERVER_LOG_TAG, "dup2(fd 2) failed: %s", strerror(errno));
        close(fd);
        return;
    }

    g_xserverLogFd = fd;
    dprintf(2, "\n===== libXlorie stderr capture -> %s =====\n", g_xserverLogPath);
    __android_log_print(ANDROID_LOG_INFO, XSERVER_LOG_TAG, "X server stderr -> %s", g_xserverLogPath);
}
