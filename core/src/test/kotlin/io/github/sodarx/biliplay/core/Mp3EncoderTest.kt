package io.github.sodarx.biliplay.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class Mp3EncoderTest {
    @Test
    fun generatedPcmRoundTripsThroughRealMp3AtBothBitrates() {
        for (bitrate in listOf(192000, 320000)) for (channels in 1..2) {
            val output = ByteArrayOutputStream()
            Mp3Encoder(44100, channels, bitrate, output).use { encoder ->
                repeat(44) { chunk ->
                    encoder.encode(
                        IntArray(1000 * channels) { sample ->
                            (sin(2 * Math.PI * 440 * (chunk * 1000 + sample / channels) / 44100) *
                                    Int.MAX_VALUE *
                                    .4)
                                .toInt()
                        }
                    )
                }
            }
            val bytes = output.toByteArray()
            assertTrue(bytes.size > 10000)
            val stream = Bitstream(ByteArrayInputStream(bytes))
            val decoder = Decoder()
            var samples = 0
            var loud = false
            while (true) {
                val header = stream.readFrame() ?: break
                val pcm = decoder.decodeFrame(header, stream) as SampleBuffer
                assertEquals(44100, pcm.sampleFrequency)
                assertEquals(channels, pcm.channelCount)
                samples += pcm.bufferLength / channels
                loud =
                    loud ||
                        pcm.buffer.take(pcm.bufferLength).any { kotlin.math.abs(it.toInt()) > 2000 }
                stream.closeFrame()
            }
            stream.close()
            assertTrue(samples in 42000..49000)
            assertTrue(loud)
        }
    }
}
