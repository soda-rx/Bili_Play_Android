package io.github.sodarx.biliplay.download

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.sodarx.biliplay.core.Mp3Encoder
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object Mp3Transcoder {
    suspend fun transcode(input: File, output: File, bitrate: Int, progress: (Int) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var encoder: Mp3Encoder? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val index =
                (0 until extractor.trackCount).firstOrNull {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: error("文件没有音轨")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val duration =
                if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION)
                else 0L
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputDone = false
            var outputDone = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var lastOutput = System.currentTimeMillis()
            output.outputStream().buffered().use { stream ->
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    check(System.currentTimeMillis() - lastOutput < 60_000) { "音频解码超时" }
                    if (!inputDone) {
                        val slot = decoder.dequeueInputBuffer(10_000)
                        if (slot >= 0) {
                            val buffer = decoder.getInputBuffer(slot)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    slot,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val slot = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = decoder.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding =
                                if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                else AudioFormat.ENCODING_PCM_16BIT
                            check(channels in 1..2) { "暂不支持此声道配置" }
                        }
                        else ->
                            if (slot >= 0) {
                                try {
                                    if (info.size > 0) {
                                        lastOutput = System.currentTimeMillis()
                                        if (encoder == null)
                                            encoder =
                                                Mp3Encoder(sampleRate, channels, bitrate, stream)
                                        val buffer = decoder.getOutputBuffer(slot)!!
                                        buffer.position(info.offset)
                                        buffer.limit(info.offset + info.size)
                                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                                        val samples =
                                            when (pcmEncoding) {
                                                AudioFormat.ENCODING_PCM_16BIT ->
                                                    IntArray(info.size / 2) {
                                                        buffer.short.toInt() shl 16
                                                    }
                                                AudioFormat.ENCODING_PCM_FLOAT ->
                                                    IntArray(info.size / 4) {
                                                        (buffer.float.coerceIn(-1f, 1f) *
                                                                Int.MAX_VALUE)
                                                            .toInt()
                                                    }
                                                else -> error("解码器返回了不支持的 PCM 格式")
                                            }
                                        encoder!!.encode(samples)
                                        if (duration > 0)
                                            progress(
                                                (info.presentationTimeUs * 100 / duration)
                                                    .toInt()
                                                    .coerceIn(0, 99)
                                            )
                                    }
                                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                                        outputDone = true
                                } finally {
                                    decoder.releaseOutputBuffer(slot, false)
                                }
                            }
                    }
                }
                check(encoder != null) { "音轨为空" }
                encoder!!.finish()
                encoder!!.close()
                encoder = null
            }
        } finally {
            runCatching { encoder?.close() }
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
