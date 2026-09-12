// ============================================================
// LinBox Termux Bridge (LinBox 专有新增，非 termux 上游代码)
// ------------------------------------------------------------
// 为 LinBox 桌面与 Termux 环境之间提供原语支持：
//   1. createFifo —— 创建命名管道（FIFO），供 shell 内的
//      `theme` / `start` 等函数把命令回传给 App 主进程。
//      Java 标准库没有 mkfifo API，故经由 JNI 提供。
//
// 注意：JNI 符号名与 com.linbox.termux.terminal.TermuxBridge
// 类绑定（Java_com_linbox_termux_terminal_TermuxBridge_*）。
// ============================================================
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>
#include <sys/types.h>

#define LINBOX_UNUSED(x) x __attribute__((__unused__))

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

/**
 * 创建 FIFO。若已存在同名的普通 FIFO 则返回 0（幂等）；
 * 若目标被普通文件占用则返回错误。
 * 返回值：0 = 成功 / 已存在；-1 = 失败（抛出 RuntimeException）。
 */
JNIEXPORT jint JNICALL Java_com_linbox_termux_terminal_TermuxBridge_createFifo(
        JNIEnv* env,
        jclass LINBOX_UNUSED(clazz),
        jstring path)
{
    char const* c_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (!c_path) return throw_runtime_exception(env, "GetStringUTFChars() failed for fifo path");

    struct stat st;
    jint result = 0;
    if (lstat(c_path, &st) == 0) {
        if (S_ISFIFO(st.st_mode)) {
            result = 0; // 已存在 FIFO —— 幂等成功
        } else {
            result = throw_runtime_exception(env, "Target exists and is not a FIFO");
        }
    } else {
        if (mkfifo(c_path, 0660) != 0) {
            if (errno == EEXIST) {
                // 竞态：检查它是否其实已是 FIFO
                if (lstat(c_path, &st) == 0 && S_ISFIFO(st.st_mode)) {
                    result = 0;
                } else {
                    result = throw_runtime_exception(env, "mkfifo() failed: target not a FIFO");
                }
            } else {
                result = throw_runtime_exception(env, "mkfifo() failed");
            }
        }
    }

    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return result;
}

/**
 * 判断给定路径是否为 FIFO（安装器自检用）。
 */
JNIEXPORT jboolean JNICALL Java_com_linbox_termux_terminal_TermuxBridge_isFifo(
        JNIEnv* env,
        jclass LINBOX_UNUSED(clazz),
        jstring path)
{
    char const* c_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (!c_path) return JNI_FALSE;

    struct stat st;
    jboolean result = (lstat(c_path, &st) == 0 && S_ISFIFO(st.st_mode)) ? JNI_TRUE : JNI_FALSE;

    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return result;
}
