package com.zzy.llmreader.platform

actual fun platformLogI(tag: String, message: String) {
    println("[$tag] $message")
}

actual fun platformLogW(tag: String, message: String, throwable: Throwable?) {
    println("[$tag] WARN $message")
    throwable?.printStackTrace()
}

actual fun platformLogE(tag: String, message: String, throwable: Throwable?) {
    System.err.println("[$tag] ERROR $message")
    throwable?.printStackTrace()
}
