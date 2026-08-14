package com.flupke.dsh.core

import android.content.Context
import java.io.File

/**
 * 捕获未处理异常，写入 app 私有目录 crash.log，并在下次启动时展示（可复制）。
 */
object CrashLog {

    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "crash.log")
        file = f
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val text = buildString {
                    append("=== ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date())).append(" ===\n")
                    append("thread: ").append(thread.name).append('\n')
                    append(throwable.stackTraceToString()).append('\n')
                }
                f.appendText(text)
            }
            // 交由系统默认处理，避免吞掉崩溃
            android.util.Log.e("dsh-android", "uncaught", throwable)
        }
    }

    fun read(): String? = file?.takeIf { it.exists() }?.readText()?.takeLast(4000)

    fun clear() {
        runCatching { file?.delete() }
    }
}
