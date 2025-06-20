package com.tailscale.ipn

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.Notify
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import libtailscale.Libtailscale

private const val MAX_LOG_SIZE = 500 * 1024 // 500KB
private const val LOG_FILE = "app.log"
private const val LOG_FILE_OLD = "app.log.1"
private const val TAG = "AppExt"
private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
private val logMutex = Mutex()

fun App.logWithFile(tag: String, message: String) {
    // Call original log method first
    Log.d(tag, message)

    // Then append to our log file
    val logFile = File(filesDir, LOG_FILE)
    val oldLogFile = File(filesDir, LOG_FILE_OLD)

    applicationScope.launch {
        logMutex.withLock {
            try {
                // Check if current log file exceeds size limit
                if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                    // Rotate logs
                    oldLogFile.delete()
                    logFile.renameTo(oldLogFile)
                }

                // Append new log entry with timestamp
                val timestamp = System.currentTimeMillis()
                val formattedDate = dateFormat.format(Date(timestamp))
                logFile.appendText("$formattedDate cylonix: $tag: $message\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to log file: ${e.message}")
            }
        }
    }
}

// Utility functions
fun App.getLogContent(includeOld: Boolean = false): String {
    val logFile = File(filesDir, LOG_FILE)
    val oldLogFile = File(filesDir, LOG_FILE_OLD)

    return buildString {
        if (logFile.exists()) {
            append(logFile.readText())
        }
        if (includeOld && oldLogFile.exists()) {
            insert(0, oldLogFile.readText())
        }
    }
}

fun App.clearLogs() {
    val logFile = File(filesDir, LOG_FILE)
    val oldLogFile = File(filesDir, LOG_FILE_OLD)

    applicationScope.launch {
        logMutex.withLock {
            logFile.delete()
            oldLogFile.delete()
        }
    }
}

// Companion object to store the callback references
private object AppCallbacks {
    var onNotification: ((Notify) -> Unit)? = null
    var onIpnStateChange: ((Ipn.State) -> Unit)? = null
}

// Extension properties using delegation
var App.onNotification: ((Notify) -> Unit)?
    get() = AppCallbacks.onNotification
    set(value) {
        AppCallbacks.onNotification = value
    }

var App.onIpnStateChange: ((Ipn.State) -> Unit)?
    get() = AppCallbacks.onIpnStateChange
    set(value) {
        AppCallbacks.onIpnStateChange = value
    }

// Extension functions to handle callbacks
fun App.setIpnStateChangeCallback(callback: (Ipn.State) -> Unit) {
    onIpnStateChange = callback
}

fun App.setNotificationCallback(callback: (Notify) -> Unit) {
    onNotification = callback
}

fun App.onNotificationReceived(notification: Notify) {
    onNotification?.invoke(notification)
}

fun App.onIpnStateChanged(state: Ipn.State) {
    onIpnStateChange?.invoke(state)
}

fun App.sendCommand(cmd: String, args: String?): String {
    if (cmd == "watch_notifications") {
        restartNotifications()
        return "Success"
    }
    return Libtailscale.sendCommand(cmd, args ?: "")
}

fun App.restartNotifications() {
    Notifier.stop()
    Notifier.start(applicationScope)
    TSLog.d(TAG, "Notifications restarted")
}