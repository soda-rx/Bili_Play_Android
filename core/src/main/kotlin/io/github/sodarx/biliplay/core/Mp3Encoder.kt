package io.github.sodarx.biliplay.core

import de.sciss.jump3r.mp3.*
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.Closeable
import java.io.OutputStream

/** Uses the public Jump3r/LAME engine API; no Java Sound or native binaries. */
class Mp3Encoder(
    sampleRate: Int,
    private val channels: Int,
    bitrate: Int,
    private val output: OutputStream,
) : Closeable {
    private val lame = Lame()
    private val flags: LameGlobalFlags
    private var finished = false
    private var closed = false

    init {
        require(channels in 1..2 && sampleRate in 8000..192000 && bitrate in listOf(192000, 320000))
        val analysis = GainAnalysis()
        val bits = BitStream()
        val presets = Presets()
        val qp = QuantizePVT()
        val quant = Quantize()
        val tag = VBRTag()
        val version = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val mpg = MPGLib()
        val decoder = Interface()
        val common = Common()
        lame.setModules(analysis, bits, presets, qp, quant, tag, version, id3, mpg)
        bits.setModules(analysis, mpg, version, tag)
        id3.setModules(bits, version)
        presets.setModules(lame)
        quant.setModules(bits, reservoir, qp, takehiro)
        qp.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bits)
        takehiro.setModules(qp)
        tag.setModules(lame, bits, version)
        mpg.setModules(decoder, common)
        decoder.setModules(tag, common)
        flags = lame.lame_init()
        flags.num_channels = channels
        flags.in_samplerate = sampleRate
        flags.out_samplerate = if (sampleRate >= 48000) 48000 else 44100
        flags.brate = bitrate / 1000
        flags.quality = 2
        flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        flags.VBR = VbrMode.vbr_off
        flags.bWriteVbrTag = false
        flags.write_id3tag_automatic = false
        flags.findReplayGain = false
        check(lame.lame_init_params(flags) >= 0) { "MP3 编码器初始化失败" }
    }

    fun encode(pcm: IntArray) {
        check(!finished && !closed)
        require(pcm.size % channels == 0)
        val frames = pcm.size / channels
        if (frames == 0) return
        val left = IntArray(frames) { pcm[it * channels] }
        val right = if (channels == 1) left else IntArray(frames) { pcm[it * 2 + 1] }
        val buffer = ByteArray((frames * 1.25 + 7200).toInt())
        val count = lame.lame_encode_buffer_int(flags, left, right, frames, buffer, 0, buffer.size)
        check(count >= 0) { "MP3 编码失败" }
        output.write(buffer, 0, count)
    }

    fun finish() {
        if (finished || closed) return
        val buffer = ByteArray(Lame.LAME_MAXMP3BUFFER)
        val count = lame.lame_encode_flush(flags, buffer, 0, buffer.size)
        check(count >= 0) { "MP3 编码结束失败" }
        output.write(buffer, 0, count)
        output.flush()
        finished = true
    }

    override fun close() {
        if (closed) return
        try {
            if (!finished) finish()
        } finally {
            lame.lame_close(flags)
            closed = true
        }
    }
}
