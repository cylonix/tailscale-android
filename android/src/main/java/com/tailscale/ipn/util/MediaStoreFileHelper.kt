// Copyright (c) EZBLOCK Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.tailscale.ipn.ui.util.InputStreamAdapter
import com.tailscale.ipn.ui.util.OutputStreamAdapter
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import libtailscale.Libtailscale
import org.json.JSONObject

// MediaStoreFileHelper is a Cylonix-only libtailscale.ShareFileHelper that
// writes incoming Taildrop files into the public Downloads/Cylonix folder
// via MediaStore. Used as the default direct-mode sink on Android 10+ when
// the user has not picked a SAF directory, so received files land somewhere
// visible to the system Files app instead of the daemon's private filesDir.
object MediaStoreFileHelper : libtailscale.ShareFileHelper {
  const val DISPLAY_SUBDIR = "Cylonix"
  private const val TAG = "MediaStoreFileHelper"
  private const val MIME_OCTET = "application/octet-stream"
  private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DISPLAY_SUBDIR"

  private var appContext: Context? = null
  private val uriByName = ConcurrentHashMap<String, Uri>()

  // isSupported reports whether MediaStore.Downloads can be used as the
  // Taildrop sink. The Downloads collection requires Android 10 (API 29).
  fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

  // init registers this helper with libtailscale so the Go taildrop
  // extension routes file ops through MediaStore. A no-op below API 29.
  @JvmStatic
  fun init(context: Context) {
    if (!isSupported()) {
      TSLog.d(TAG, "MediaStore.Downloads unavailable below API 29; skipping")
      return
    }
    appContext = context.applicationContext
    Libtailscale.setShareFileHelper(this)
    TSLog.d(TAG, "registered MediaStoreFileHelper for $relativePath")
  }

  // humanLocation returns a user-facing label for the file's saved
  // location, e.g. "Downloads/Cylonix/<name>". Used by the Cylonix
  // file-received notification.
  fun humanLocation(displayName: String): String =
      "${Environment.DIRECTORY_DOWNLOADS}/$DISPLAY_SUBDIR/$displayName"

  // RelocatedMedia describes a file moved out of Downloads/Cylonix into a
  // media collection: its new content URI and a user-facing location label
  // such as "Pictures/Cylonix/<name>".
  data class RelocatedMedia(val uri: String, val humanLocation: String)

  // mediaMimeType returns the image/* or video/* MIME type for the given
  // display name, or null when the extension is not a known photo/video
  // type. MimeTypeMap lacks some common types on older releases, so a
  // small fallback table covers them.
  private fun mediaMimeType(displayName: String): String? {
    val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
    if (ext.isEmpty()) return null
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: when (ext) {
          "heic" -> "image/heic"
          "heif" -> "image/heif"
          "webp" -> "image/webp"
          else -> null
        }
    return if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) mime
    else null
  }

  // relocateToMediaCollection moves a finished Downloads/Cylonix row into
  // the proper media collection (Pictures/Cylonix for images,
  // Movies/Cylonix for videos) so plain Taildrop photo/video drops show up
  // in gallery apps, matching AirDrop. Returns null — leaving the file in
  // Downloads/Cylonix — when the name is not a photo/video, the helper is
  // not active (SAF mode, pre-API-29), or the copy fails. Callers must
  // only pass plain drops, never peer-message attachments, whose
  // Downloads URI is referenced by chat bubbles.
  fun relocateToMediaCollection(displayName: String, uriString: String): RelocatedMedia? {
    val ctx = appContext ?: return null
    if (!isSupported()) return null
    val mime = mediaMimeType(displayName) ?: return null
    val isImage = mime.startsWith("image/")
    val collection =
        if (isImage) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val mediaDir =
        if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
    val resolver = ctx.contentResolver
    val src = Uri.parse(uriString)
    val dst = resolver.insert(
        collection,
        ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
          put(MediaStore.MediaColumns.MIME_TYPE, mime)
          put(MediaStore.MediaColumns.RELATIVE_PATH, "$mediaDir/$DISPLAY_SUBDIR")
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: return null
    try {
      resolver.openInputStream(src)?.use { input ->
        resolver.openOutputStream(dst, "w")?.use { output -> input.copyTo(output) }
            ?: throw IOException("openOutputStream failed for $dst")
      } ?: throw IOException("openInputStream failed for $src")
      resolver.update(
          dst, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
      TSLog.w(TAG, "relocate($displayName) to $mediaDir failed, keeping Downloads copy: ${e.message}")
      try {
        resolver.delete(dst, null, null)
      } catch (cleanup: Exception) {
        TSLog.w(TAG, "relocate cleanup failed: ${cleanup.message}")
      }
      return null
    }
    try {
      resolver.delete(src, null, null)
    } catch (e: Exception) {
      // The media-collection copy committed; a leftover Downloads row is
      // cosmetic, so don't fail the relocation over it.
      TSLog.w(TAG, "relocate($displayName): failed to delete Downloads copy: ${e.message}")
    }
    // Re-key the URI cache so later lookups resolve to the new row.
    uriByName.entries.firstOrNull { it.value == src }?.let { uriByName[it.key] = dst }
    // The system may have uniquified the display name on insert; report
    // the committed name and path.
    var finalName = displayName
    var finalRelativePath = "$mediaDir/$DISPLAY_SUBDIR"
    resolver.query(
        dst,
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
        null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        c.getString(0)?.let { finalName = it }
        c.getString(1)?.let { finalRelativePath = it.trimEnd('/') }
      }
    }
    TSLog.d(TAG, "relocated $displayName to $finalRelativePath/$finalName")
    return RelocatedMedia(dst.toString(), "$finalRelativePath/$finalName")
  }

  private fun ctx(): Context =
      appContext ?: throw IOException("MediaStoreFileHelper not initialized")

  private val downloadsCollection: Uri
    get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

  @Throws(IOException::class)
  override fun openFileWriter(fileName: String, offset: Long): libtailscale.OutputStream {
    val ctx = ctx()
    val resolver = ctx.contentResolver
    val uri = uriByName.getOrPut(fileName) {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, MIME_OCTET)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
      resolver.insert(downloadsCollection, values)
          ?: throw IOException("MediaStore insert failed for $fileName")
    }
    // MediaStore openOutputStream doesn't support arbitrary offsets; "wa"
    // appends, "wt" truncates. Taildrop opens partials at offset 0 in the
    // common case, so we only special-case append vs truncate.
    val mode = if (offset > 0L) "wa" else "wt"
    val out = resolver.openOutputStream(uri, mode)
        ?: throw IOException("openOutputStream failed for $uri")
    return OutputStreamAdapter(out)
  }

  @Throws(IOException::class)
  override fun getFileURI(fileName: String): String {
    val u = uriByName[fileName] ?: throw IOException("no URI for $fileName")
    return u.toString()
  }

  @Throws(IOException::class)
  override fun renameFile(oldPath: String, targetName: String): String {
    val ctx = ctx()
    val src = Uri.parse(oldPath)
    var finalName = targetName
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
      put(MediaStore.MediaColumns.IS_PENDING, 0)
    }
    val updated = try {
      ctx.contentResolver.update(src, values, null, null)
    } catch (e: Exception) {
      // Most likely a UNIQUE constraint violation when another file with
      // the same display name already lives in Downloads/Cylonix.
      TSLog.w(TAG, "rename($targetName) failed, retrying with suffix: ${e.message}")
      finalName = generateNewFilename(targetName)
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
      ctx.contentResolver.update(src, values, null, null)
    }
    if (updated <= 0) {
      throw IOException("MediaStore rename produced no rows for $src")
    }
    // Re-key the cache under the final display name so subsequent lookups
    // (getFileURI / getFileInfo) find the row.
    val partialName = uriByName.entries.firstOrNull { it.value == src }?.key
    if (partialName != null) {
      uriByName.remove(partialName)
    }
    uriByName[finalName] = src
    return src.toString()
  }

  @Throws(IOException::class)
  override fun deleteFile(uri: String) {
    ctx().contentResolver.delete(Uri.parse(uri), null, null)
    val parsed = Uri.parse(uri)
    val match = uriByName.entries.firstOrNull { it.value == parsed }?.key
    if (match != null) {
      uriByName.remove(match)
    }
  }

  @Throws(IOException::class)
  override fun openFileReader(name: String): libtailscale.InputStream {
    val u = uriByName[name] ?: throw IOException("no URI for $name")
    val stream = ctx().contentResolver.openInputStream(u)
        ?: throw IOException("openInputStream failed for $u")
    return InputStreamAdapter(stream)
  }

  // Direct mode does not enumerate the directory; staging-mode features
  // (WaitingFiles) are bypassed by the Go side when DirectFileMode is on,
  // so an empty list is sufficient.
  @Throws(IOException::class)
  override fun listFilesJSON(suffix: String): String = "[]"

  @Throws(IOException::class)
  override fun getFileInfo(fileName: String): String {
    val u = uriByName[fileName] ?: throw IOException("no URI for $fileName")
    val projection = arrayOf(
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
    )
    ctx().contentResolver.query(u, projection, null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        val name = c.getString(0) ?: fileName
        val size = c.getLong(1)
        // DATE_MODIFIED is seconds since epoch; libtailscale wants ms.
        val modTime = c.getLong(2) * 1000L
        return """{"name":${JSONObject.quote(name)},"size":$size,"modTime":$modTime}"""
      }
    }
    throw IOException("MediaStore row not found for $fileName")
  }

  private fun generateNewFilename(filename: String): String {
    val dot = filename.lastIndexOf('.')
    val base = if (dot != -1) filename.substring(0, dot) else filename
    val ext = if (dot != -1) filename.substring(dot) else ""
    return "$base-${UUID.randomUUID()}$ext"
  }
}
