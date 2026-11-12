package com.abdfaith.videogen

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FrameExtractor {
    /** يحفظ آخر إطار من الفيديو كصورة JPEG ليكون بداية المشهد التالي. */
    suspend fun lastFrame(videoPath: String, out: File): Boolean = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(videoPath)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val timeUs = (durMs - 120L).coerceAtLeast(0L) * 1000L
            val bmp = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(-1L)
            if (bmp == null) {
                false
            } else {
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                true
            }
        } catch (e: Exception) {
            false
        } finally {
            r.release()
        }
    }
}

object VideoStitcher {
    /** يربط المقاطع بالترتيب في ملف MP4 واحد (H.264) على الجهاز. */
    @OptIn(UnstableApi::class)
    suspend fun stitch(context: Context, inputs: List<String>, output: File) {
        require(inputs.isNotEmpty()) { "لا توجد مقاطع للتجميع" }
        if (output.exists()) output.delete()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val items = inputs.map { path ->
                    EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(path))))
                        .setRemoveAudio(true)
                        .build()
                }
                val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer.start(composition, output.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }
    }
}
