package com.tailscale.ipn

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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
    notification.CylonixDirectFileReceived?.let { file ->
        try {
            notifyDirectModeFileReceived(file)
        } catch (e: Exception) {
            TSLog.e(TAG, "notifyDirectModeFileReceived failed: ${e.message}")
        }
    }
    onNotification?.invoke(notification)
}

// Uses the Cylonix HIGH-importance variant channel created in App.onCreate.
// The upstream "tailscale-files" channel is IMPORTANCE_DEFAULT which never
// triggers a heads-up banner (MIUI in particular hides it entirely), so
// Cylonix posts to its own HIGH channel for direct-mode arrivals. Each
// arrival gets its own notification ID so multiple files stack in the
// shade instead of replacing one another.
private val taildropNotificationId = AtomicInteger(2000)

fun App.notifyDirectModeFileReceived(file: Ipn.CylonixDirectFile) {
    if (file.path.isEmpty() && file.name.isEmpty()) {
        return
    }
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED) {
        return
    }
    // Use the package's launcher intent so this works for both upstream
    // tailscale-android and any host (e.g., cylonix) that ships a different
    // MainActivity class. Cylonix removes com.tailscale.ipn.MainActivity
    // from its manifest, so referencing that class directly would throw
    // ActivityNotFoundException at tap time.
    val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = openAppIntent?.let {
        PendingIntent.getActivity(
            this, file.path.hashCode(), it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    val displayName = if (file.name.isNotEmpty()) file.name else File(file.path).name
    val sizeText = if (file.size > 0) Formatter.formatShortFileSize(this, file.size) else ""
    // Resolve a user-facing location. MediaStore returns a content:// URI
    // that means nothing to humans; substitute the well-known display
    // path (Downloads/Cylonix/<name>) instead. For plain filesystem paths
    // (no SAF, pre-API-29 fallback) just show the parent directory.
    val locationText = when {
        file.path.startsWith("content://") ->
            com.tailscale.ipn.util.MediaStoreFileHelper.humanLocation(displayName)
        else -> File(file.path).parent ?: file.path
    }
    val body = buildString {
        append(locationText)
        if (sizeText.isNotEmpty()) {
            append("  •  ")
            append(sizeText)
        }
    }
    val builder = NotificationCompat.Builder(this, App.CYLONIX_TAILDROP_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("File received by Cylonix")
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText("$displayName\n$body"))
        .setSubText(displayName)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
    pendingIntent?.let { builder.setContentIntent(it) }
    NotificationManagerCompat.from(this)
        .notify(taildropNotificationId.getAndIncrement(), builder.build())
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

// __BEGIN_CYLONIX_ADD__
// Per-package wrappers around the upstream bulk updateUserSelectedPackages
// API. The cylonix flutter app drives split-tunnel one package at a time
// (via the excludeAppFromVPN method channel call); upstream replaced its
// per-package add/remove methods with a single bulk update around v1.95.
// These wrappers preserve the per-package surface by reading the existing
// list, mutating it, and writing it back.
fun App.addUserDisallowedPackageName(packageName: String) {
    if (packageName.isEmpty()) {
        TSLog.e(TAG, "addUserDisallowedPackageName called with empty packageName")
        return
    }
    val current = selectedPackageNames().toMutableSet()
    if (current.add(packageName)) {
        updateUserSelectedPackages(current.toList())
    }
}

fun App.removeUserDisallowedPackageName(packageName: String) {
    if (packageName.isEmpty()) {
        TSLog.e(TAG, "removeUserDisallowedPackageName called with empty packageName")
        return
    }
    val current = selectedPackageNames().toMutableSet()
    if (current.remove(packageName)) {
        updateUserSelectedPackages(current.toList())
    }
}
// __END_CYLONIX_ADD__