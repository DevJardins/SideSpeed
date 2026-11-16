package io.github.devjardins.sidespeed.adb

sealed interface ShellEvent {
    data class Line(val text: String) : ShellEvent
    data class Exit(val code: Int) : ShellEvent
}
