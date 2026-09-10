package com.zzy.llmreader.platform

/** Android 端打到 logcat，桌面端打到 stdout/stderr。 */
expect fun platformLogI(tag: String, message: String)

expect fun platformLogW(tag: String, message: String, throwable: Throwable? = null)

expect fun platformLogE(tag: String, message: String, throwable: Throwable? = null)

/** shared 内部统一的日志入口（避免直接依赖 android.util.Log）。 */
object PlatformLog {
    fun i(tag: String, message: String) = platformLogI(tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) =
        platformLogW(tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        platformLogE(tag, message, throwable)
}
