package io.github.devjardins.sidespeed.adb

class AdbConnectionLost : Exception {
    constructor(cause: Throwable? = null) : super(cause)
    constructor(message: String, cause: Throwable? = null) : super(message, cause)
}
