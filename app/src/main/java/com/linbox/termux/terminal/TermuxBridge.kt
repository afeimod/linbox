package com.linbox.termux.terminal

/**
 * LinBox Termux 桥接：LinBox 专有原生方法的 Kotlin 声明。
 *
 * 对应 C 实现：app/src/main/cpp/termux/linbox_bridge.c
 * （JNI 符号：Java_com_linbox_termux_terminal_TermuxBridge_*）
 *
 * 用途：Java/Kotlin 标准库没有 mkfifo 能力，而 LinBox 的 shell 命令桥
 * （`theme` / `start` 等函数 → App）依赖命名管道，故经 JNI 提供。
 */
object TermuxBridge {

    init {
        // libtermux.so 由 termux.c + linbox_bridge.c 共同构成，
        // 与 com.linbox.termux.terminal.JNI 共享同一个库。
        System.loadLibrary("termux")
    }

    /**
     * 创建 FIFO（幂等：已存在且为 FIFO 时返回成功）。
     * @return 0 成功；-1 失败（抛 RuntimeException）
     */
    @JvmStatic
    external fun createFifo(path: String): Int

    /** 判断路径是否为 FIFO。 */
    @JvmStatic
    external fun isFifo(path: String): Boolean
}
