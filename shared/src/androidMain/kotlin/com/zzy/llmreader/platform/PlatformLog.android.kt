package com.zzy.llmreader.platform

import android.util.Log

actual fun platformLogI(tag: String, message: String) {
    Log.i(tag, message)
}

actual fun platformLogW(tag: String, message: String, throwable: Throwable?) {
    Log.w(tag, message, throwable)
}

actual fun platformLogE(tag: String, message: String, throwable: Throwable?) {
    Log.e(tag, message, throwable)
}
