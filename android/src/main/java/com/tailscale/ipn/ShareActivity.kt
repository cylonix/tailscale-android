// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.universalFit
import com.tailscale.ipn.ui.view.TaildropView
import com.tailscale.ipn.util.TSLog
// __BEGIN_CYLONIX_ADD__
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
// __END_CYLONIX_ADD__
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ShareActivity is the entry point for Taildrop share intents
class ShareActivity : ComponentActivity() {
  private val TAG = ShareActivity::class.simpleName

  private val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>> = MutableStateFlow(emptyList())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      AppTheme {
        Surface(color = MaterialTheme.colorScheme.inverseSurface) { // Background for the letterbox
          Surface(modifier = Modifier.universalFit()) {
            TaildropView(requestedTransfers, (application as App).applicationScope)
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    // Ensure our app instance is initialized
    App.get()
    lifecycleScope.launch { withContext(Dispatchers.IO) { loadFiles() } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    loadFiles()
  }

  // __BEGIN_CYLONIX_MOD__
  // Loads the files from the intent.
  //
  // Cylonix changes from upstream:
  //   * Accepts ACTION_SEND with text/plain — extracts EXTRA_TEXT and saves
  //     URLs as .webloc, plain text as .txt (to mirror the Apple share
  //     extension at apple/Sources/ShareExtension/ShareViewController.swift).
  //   * Copies every incoming content:// URI to a temp file under cacheDir
  //     before handing it to the rest of the share pipeline. The URI
  //     permission granted to ShareActivity is tied to this activity's
  //     lifetime, and direct screenshot share popups expose particularly
  //     short-lived URIs — by the time the user picks a peer and the
  //     LocalAPI client calls openInputStream, the upstream URI is often
  //     gone (or its query() returns no row). Copying eagerly to cacheDir
  //     and switching the OutgoingFile.uri to the file:// of the copy
  //     makes the send stable.
  //   * Hardens cursor handling — many content providers (screenshot
  //     popups, in particular) skip OpenableColumns or return null cursors.
  fun loadFiles() {
    if (intent == null) {
      TSLog.e(TAG, "Share failure - No intent found")
      return
    }

    val pendingFiles = mutableListOf<Ipn.OutgoingFile>()

    when (intent.action) {
      Intent.ACTION_SEND -> {
        getStreamExtra(intent)?.let { uri ->
          materializeUri(uri)?.let { pendingFiles.add(it) }
        }
        if (pendingFiles.isEmpty()) {
          intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
            materializeText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))?.let {
              pendingFiles.add(it)
            }
          }
        }
      }
      Intent.ACTION_SEND_MULTIPLE -> {
        getStreamListExtra(intent)?.filterNotNull()?.forEach { uri ->
          materializeUri(uri)?.let { pendingFiles.add(it) }
        }
      }
      else -> {
        TSLog.e(TAG, "No extras found in intent - nothing to share")
      }
    }

    if (pendingFiles.isEmpty()) {
      TSLog.e(TAG, "Share failure - no files extracted from intent")
    }

    requestedTransfers.set(pendingFiles)
  }

  private fun getStreamExtra(intent: Intent): Uri? =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
      }

  private fun getStreamListExtra(intent: Intent): List<Uri?>? =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
      }

  // materializeUri queries the URI for a display name and size, copies the
  // bytes into a stable cacheDir-backed file, and returns an OutgoingFile
  // whose uri points at the copy.
  private fun materializeUri(srcUri: Uri): Ipn.OutgoingFile? {
    val (displayName, declaredSize) = queryNameAndSize(srcUri)
    val safeName = displayName ?: generateFallbackName(srcUri)
    val staged = stageInputStream(srcUri, safeName) ?: return null
    val finalName = staged.name
    val finalSize = if (declaredSize > 0) declaredSize else staged.length()
    return Ipn.OutgoingFile(Name = finalName, DeclaredSize = finalSize).apply {
      uri = Uri.fromFile(staged)
    }
  }

  private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
    val resolver = contentResolver ?: return null to 0L
    return try {
      resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null to 0L
        val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameCol >= 0) cursor.getString(nameCol) else null
        val size =
            if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
        name to size
      } ?: (null to 0L)
    } catch (e: Exception) {
      TSLog.e(TAG, "queryNameAndSize failed for $uri: ${e.message}")
      null to 0L
    }
  }

  // stageInputStream copies the content of srcUri into cacheDir/share/<uuid>/<name>
  // and returns the local file. It also collision-renames if needed.
  private fun stageInputStream(srcUri: Uri, suggestedName: String): File? {
    val resolver = contentResolver ?: return null
    val dir = File(cacheDir, "share/${UUID.randomUUID()}")
    if (!dir.mkdirs() && !dir.isDirectory) {
      TSLog.e(TAG, "Failed to create stage dir $dir")
      return null
    }
    val dest = File(dir, sanitizeFileName(suggestedName))
    return try {
      resolver.openInputStream(srcUri).use { input ->
        if (input == null) {
          TSLog.e(TAG, "openInputStream returned null for $srcUri")
          return null
        }
        FileOutputStream(dest).use { out -> input.copyTo(out) }
      }
      dest
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to stage $srcUri: ${e.message}")
      dest.delete()
      dir.delete()
      null
    }
  }

  // materializeText writes EXTRA_TEXT to a temp file as either a .webloc
  // (URL, plist format matching Apple) or .txt and returns it as an
  // OutgoingFile.
  private fun materializeText(text: String, subject: String?): Ipn.OutgoingFile? {
    val trimmed = text.trim()
    val asUrl = parseHttpUrl(trimmed)
    val fileName: String
    val payload: ByteArray
    if (asUrl != null) {
      fileName = sanitizeFileName(weblocBaseName(asUrl, subject)) + ".webloc"
      payload = buildWeblocPlist(asUrl)
    } else {
      fileName = sanitizeFileName(textBaseName(trimmed, subject)) + ".txt"
      payload = trimmed.toByteArray(Charsets.UTF_8)
    }

    val dir = File(cacheDir, "share/${UUID.randomUUID()}")
    if (!dir.mkdirs() && !dir.isDirectory) {
      TSLog.e(TAG, "Failed to create stage dir $dir")
      return null
    }
    val dest = File(dir, fileName)
    return try {
      FileOutputStream(dest).use { it.write(payload) }
      Ipn.OutgoingFile(Name = dest.name, DeclaredSize = dest.length()).apply {
        uri = Uri.fromFile(dest)
      }
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to stage shared text: ${e.message}")
      dest.delete()
      dir.delete()
      null
    }
  }

  // parseHttpUrl returns the input as a URL only if it looks like an http(s)
  // resource. Anything else is treated as plain text.
  private fun parseHttpUrl(s: String): String? {
    if (s.isEmpty()) return null
    val first = s.substringBefore('\n').trim()
    if (!first.startsWith("http://", ignoreCase = true) &&
        !first.startsWith("https://", ignoreCase = true)) {
      return null
    }
    // Only treat as URL if there's no embedded whitespace — heuristic to
    // avoid catching messages that happen to start with a URL.
    if (first != s) return null
    return first
  }

  private fun weblocBaseName(url: String, subject: String?): String {
    subject?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val host =
        try {
          java.net.URI(url).host
        } catch (_: Exception) {
          null
        }
    return if (!host.isNullOrEmpty()) host else "Shared URL"
  }

  private fun textBaseName(text: String, subject: String?): String {
    subject?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(50) }
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (firstLine.isNotEmpty()) firstLine.take(50) else "Shared Text"
  }

  private fun sanitizeFileName(name: String): String {
    val illegal = "/\\?%*|\"<>:".toSet()
    val cleaned = name.map { if (it in illegal || it.isISOControl()) '-' else it }.joinToString("")
    val trimmed = cleaned.trim().trim('.')
    return if (trimmed.isEmpty()) "Shared" else trimmed.take(128)
  }

  private fun buildWeblocPlist(url: String): ByteArray {
    val escaped =
        url.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    val xml =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
<key>URL</key>
<string>$escaped</string>
</dict>
</plist>
"""
    return xml.toByteArray(Charsets.UTF_8)
  }
  // __END_CYLONIX_MOD__

  private fun generateFallbackName(uri: Uri): String {
    val randomId = Random.nextLong()
    val mimeType = contentResolver?.getType(uri)
    val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    return if (extension != null) "$randomId.$extension" else randomId.toString()
  }
}
