package org.example.x2

import androidx.compose.runtime.mutableStateListOf
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogManager {
    val logs = mutableStateListOf<String>()

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message"
        println(logLine) // Also print to Android Logcat/standard output
        
        // Ensure UI updates on main thread, but list operation itself is simple
        // Limit total number of stored log lines to prevent memory build-up
        if (logs.size > 500) {
            logs.removeAt(0)
        }
        logs.add(logLine)
    }

    fun logException(prefix: String, e: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        e.printStackTrace(pw)
        log("$prefix: ${e.message}\n$sw")
    }

    fun clear() {
        logs.clear()
        log("로그가 초기화되었습니다.")
    }
}
