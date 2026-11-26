/*
 * Minimal PTY bridge for MobileIDE.
 *
 * Java owns the master fd (wrapped in streams); native code only creates the
 * session with forkpty(3), forwards window-size changes and reaps the child.
 * No shell string is ever built here: argv/envp arrive as arrays.
 */
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#define MOBILEIDE_MAX_INHERITED_FD 1024

static char **to_string_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) {
        return NULL;
    }
    jsize count = (*env)->GetArrayLength(env, array);
    char **result = (char **) calloc((size_t) count + 1, sizeof(char *));
    if (result == NULL) {
        return NULL;
    }
    for (jsize index = 0; index < count; index++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, index);
        const char *chars = item == NULL ? NULL : (*env)->GetStringUTFChars(env, item, NULL);
        result[index] = strdup(chars == NULL ? "" : chars);
        if (chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, item, chars);
        }
        if (item != NULL) {
            (*env)->DeleteLocalRef(env, item);
        }
    }
    result[count] = NULL;
    return result;
}

static void free_string_array(char **values) {
    if (values == NULL) {
        return;
    }
    for (int index = 0; values[index] != NULL; index++) {
        free(values[index]);
    }
    free(values);
}

static jintArray failure(JNIEnv *env, int code) {
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) {
        return NULL;
    }
    jint values[2];
    values[0] = -1;
    values[1] = code;
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_dev_mobileide_core_process_PtyNative_nativeOpen(
        JNIEnv *env, jobject thiz,
        jstring program, jobjectArray arguments, jobjectArray environment,
        jstring workingDirectory, jint rows, jint cols) {
    (void) thiz;

    const char *program_chars = (*env)->GetStringUTFChars(env, program, NULL);
    if (program_chars == NULL) {
        return failure(env, EINVAL);
    }
    const char *cwd_chars = (*env)->GetStringUTFChars(env, workingDirectory, NULL);

    char **argument_values = to_string_array(env, arguments);
    char **environment_values = to_string_array(env, environment);

    int argument_count = 0;
    while (argument_values != NULL && argument_values[argument_count] != NULL) {
        argument_count++;
    }
    char **argv = (char **) calloc((size_t) argument_count + 2, sizeof(char *));
    if (argv == NULL) {
        free_string_array(argument_values);
        free_string_array(environment_values);
        (*env)->ReleaseStringUTFChars(env, program, program_chars);
        if (cwd_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, workingDirectory, cwd_chars);
        }
        return failure(env, ENOMEM);
    }
    argv[0] = strdup(program_chars);
    for (int index = 0; index < argument_count; index++) {
        argv[index + 1] = argument_values[index];
    }
    argv[argument_count + 1] = NULL;

    struct winsize window;
    memset(&window, 0, sizeof(window));
    window.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    window.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &window);
    int saved_errno = errno;

    if (pid == 0) {
        /* Child: only async-signal-safe calls until execve. */
        for (int fd = 3; fd < MOBILEIDE_MAX_INHERITED_FD; fd++) {
            close(fd);
        }
        if (cwd_chars != NULL) {
            chdir(cwd_chars);
        }
        if (program_chars[0] == '/') {
            execve(program_chars, argv, environment_values);
        } else {
            execvp(program_chars, argv);
        }
        _exit(127);
    }

    free_string_array(argument_values);
    free_string_array(environment_values);
    free(argv);
    (*env)->ReleaseStringUTFChars(env, program, program_chars);
    if (cwd_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, workingDirectory, cwd_chars);
    }

    if (pid < 0) {
        return failure(env, saved_errno);
    }

    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) {
        close(master);
        kill(-pid, SIGKILL);
        return NULL;
    }
    jint values[2];
    values[0] = master;
    values[1] = (jint) pid;
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_mobileide_core_process_PtyNative_nativeResize(
        JNIEnv *env, jobject thiz, jint fd, jint pid, jint rows, jint cols) {
    (void) env;
    (void) thiz;
    struct winsize window;
    memset(&window, 0, sizeof(window));
    window.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    window.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    if (ioctl((int) fd, TIOCSWINSZ, &window) == 0) {
        if (pid > 0) {
            kill(-(pid_t) pid, SIGWINCH);
        }
    }
}

JNIEXPORT jint JNICALL
Java_dev_mobileide_core_process_PtyNative_nativeWait(JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;
    if (pid <= 0) {
        return -1;
    }
    int status = 0;
    pid_t result;
    do {
        result = waitpid((pid_t) pid, &status, 0);
    } while (result < 0 && errno == EINTR);

    if (result < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_dev_mobileide_core_process_PtyNative_nativeKill(
        JNIEnv *env, jobject thiz, jint pid, jint signal_number) {
    (void) env;
    (void) thiz;
    if (pid <= 0) {
        return;
    }
    /* forkpty's child is a session leader, so -pid reaches its whole group. */
    kill(-(pid_t) pid, (int) signal_number);
    kill((pid_t) pid, (int) signal_number);
}
