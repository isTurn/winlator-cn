// cpu_set_t / CPU_ZERO / CPU_SET are GNU extensions.
#define _GNU_SOURCE

#include <jni.h>

#include <sched.h>
#include <stdlib.h>
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

    for (long i = 0; i < numCpus; i++) CPU_SET((int)i, &mask);

    (void) sched_setaffinity(0, sizeof(mask), &mask);
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
