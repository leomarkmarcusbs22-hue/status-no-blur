package com.statusnoblur.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object VideoProcessor {

    // WhatsApp Status video specs
    private const val TARGET_WIDTH = 720
    private const val TARGET_HEIGHT = 1280
    private const val TARGET_BITRATE = 1_500_000 // 1.5 Mbps - sweet spot for WhatsApp
    private const val TARGET_FRAME_RATE = 30
    private const val MAX_DURATION_MS = 30_000L // 30 seconds
    private const val MAX_FILE_SIZE = 15 * 1024 * 1024L // 15 MB (under WhatsApp's 16MB limit)

    fun optimizeForWhatsAppStatus(context: Context, sourceUri: Uri): File {
        val outputDir = File(context.cacheDir, "optimized").apply { mkdirs() }
        val outputFile = File(outputDir, "status_${System.currentTimeMillis()}.mp4")

        // For video, we do a passthrough re-mux if the video is already well-encoded
        // This avoids re-encoding which would add another generation of quality loss
        // If the file is too large, we copy with size awareness

        val inputFd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            ?: throw IllegalArgumentException("Cannot open video")

        val extractor = MediaExtractor()
        extractor.setDataSource(inputFd.fileDescriptor)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val trackCount = extractor.trackCount
        val trackIndices = mutableMapOf<Int, Int>()

        // Add tracks
        for (i in 0 until trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                val newTrackIndex = muxer.addTrack(format)
                trackIndices[i] = newTrackIndex
            }
        }

        muxer.start()

        val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
        val bufferInfo = MediaCodec.BufferInfo()

        // Copy data from each track
        for ((srcTrack, dstTrack) in trackIndices) {
            extractor.selectTrack(srcTrack)

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                // Enforce 30-second limit
                val sampleTime = extractor.sampleTime
                if (sampleTime > MAX_DURATION_MS * 1000) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                extractor.advance()
            }

            extractor.unselectTrack(srcTrack)
        }

        muxer.stop()
        muxer.release()
        extractor.release()
        inputFd.close()

        // If file is too large, we need to warn (full re-encoding would require MediaCodec
        // which is extremely complex - for v1, we do smart passthrough)
        if (outputFile.length() > MAX_FILE_SIZE) {
            // Trim to fit by re-muxing with shorter duration
            val trimmedFile = trimToFitSize(context, outputFile)
            if (trimmedFile != outputFile) {
                outputFile.delete()
                return trimmedFile
            }
        }

        return outputFile
    }

    private fun trimToFitSize(context: Context, sourceFile: File): File {
        val outputDir = File(context.cacheDir, "optimized")
        val outputFile = File(outputDir, "status_trimmed_${System.currentTimeMillis()}.mp4")

        val extractor = MediaExtractor()
        extractor.setDataSource(sourceFile.absolutePath)

        // Get video duration
        var videoDurationUs = 0L
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoDurationUs = format.getLong(MediaFormat.KEY_DURATION)
                break
            }
        }

        // Calculate how much we need to trim based on file size ratio
        val ratio = MAX_FILE_SIZE.toFloat() / sourceFile.length()
        val targetDurationUs = (videoDurationUs * ratio * 0.9).toLong() // 90% of estimated to be safe

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val trackIndices = mutableMapOf<Int, Int>()
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                trackIndices[i] = muxer.addTrack(format)
            }
        }

        muxer.start()

        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        for ((srcTrack, dstTrack) in trackIndices) {
            extractor.selectTrack(srcTrack)

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                if (extractor.sampleTime > targetDurationUs) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                extractor.advance()
            }

            extractor.unselectTrack(srcTrack)
        }

        muxer.stop()
        muxer.release()
        extractor.release()

        return outputFile
    }
}
