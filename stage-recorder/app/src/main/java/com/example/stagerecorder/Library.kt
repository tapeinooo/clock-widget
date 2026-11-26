package com.example.stagerecorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 録ったもの。端末の「動画」「音楽」の StageRecorder フォルダに入る（ギャラリー等からも見える）。 */
data class Rec(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val dateMs: Long,
    val durationMs: Long,
    val size: Long,
)

object Library {
    const val VIDEO_DIR = "Movies/StageRecorder/"
    const val AUDIO_DIR = "Music/StageRecorder/"

    private val videoCollection get() = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    private val audioCollection get() = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    val audioMime: String get() = MimeTypeMap.getSingleton().getMimeTypeFromExtension("m4a") ?: "audio/mp4"

    fun newName(): String = "登壇_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun list(c: Context): List<Rec> =
        (query(c, videoCollection, VIDEO_DIR, true) + query(c, audioCollection, AUDIO_DIR, false))
            .sortedByDescending { it.dateMs }

    private fun query(c: Context, collection: Uri, dir: String, video: Boolean): List<Rec> {
        val out = mutableListOf<Rec>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
        )
        try {
            c.contentResolver.query(
                collection, proj,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(dir),
                null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    out += Rec(
                        uri = ContentUris.withAppendedId(collection, cur.getLong(0)),
                        name = cur.getString(1) ?: "",
                        isVideo = video,
                        dateMs = cur.getLong(2) * 1000,
                        durationMs = cur.getLong(3),
                        size = cur.getLong(4),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 新しい音声ファイルの置き場所を作る（書き終わるまでは他から見えない） */
    fun createPendingAudio(c: Context, name: String): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.m4a")
            put(MediaStore.MediaColumns.MIME_TYPE, audioMime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, AUDIO_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return c.contentResolver.insert(audioCollection, cv)
    }

    fun publish(c: Context, uri: Uri) {
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        c.contentResolver.update(uri, cv, null, null)
    }

    /** アプリ内の一時ファイルを、動画／音楽のフォルダへ写す */
    fun saveFile(c: Context, file: File, displayName: String, video: Boolean): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else audioMime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) VIDEO_DIR else AUDIO_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = c.contentResolver.insert(if (video) videoCollection else audioCollection, cv) ?: return null
        return try {
            c.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            publish(c, uri)
            uri
        } catch (e: Exception) {
            c.contentResolver.delete(uri, null, null)
            null
        }
    }

    fun rename(c: Context, uri: Uri, newName: String): Boolean = try {
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
        c.contentResolver.update(uri, cv, null, null) > 0
    } catch (_: Exception) {
        false
    }

    fun displayName(c: Context, uri: Uri): String? = try {
        c.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    fun baseName(name: String) = name.substringBeforeLast('.', name)
    fun ext(name: String) = if ('.' in name) "." + name.substringAfterLast('.') else ""
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1fGB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0fMB".format(b / (1L shl 20).toDouble())
    else -> "%dKB".format(b / 1024)
}
