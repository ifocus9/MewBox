package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.TraceUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.Locale
import timber.log.Timber

/**
 * 兼容型 FFmpeg 音频扩展渲染器
 * 优先支持 DTS、DTS-HD、TrueHD 等系统 MediaCodec 无法硬解的高级音频流
 */
@UnstableApi
internal class CompatFfmpegAudioRenderer(
    private val context: Context? = null,
    eventHandler: Handler? = null,
    eventListener: AudioRendererEventListener? = null,
    audioSink: AudioSink,
    private val systemDecoderFallbackOnly: Boolean = false,
) : DecoderAudioRenderer<FfmpegAudioDecoder>(eventHandler, eventListener, audioSink) {

    constructor(
        eventHandler: Handler?,
        eventListener: AudioRendererEventListener?,
        audioSink: AudioSink,
    ) : this(
        context = null,
        eventHandler = eventHandler,
        eventListener = eventListener,
        audioSink = audioSink,
        systemDecoderFallbackOnly = false,
    )

    override fun getName(): String = "CompatFfmpegAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        val decodeFormat = normalizeDecodeFormat(format)
        val sampleMimeType = decodeFormat.sampleMimeType ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(sampleMimeType)) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        if (systemDecoderFallbackOnly && isSystemDecoderSupported(decodeFormat)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        if (!FfmpegLibrary.supportsFormat(sampleMimeType)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        if (getOutputChannelCount(decodeFormat) == Format.NO_VALUE) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        return if (decodeFormat.cryptoType == C.CRYPTO_TYPE_NONE) {
            C.FORMAT_HANDLED
        } else {
            C.FORMAT_UNSUPPORTED_DRM
        }
    }

    @Throws(FfmpegDecoderException::class)
    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): FfmpegAudioDecoder {
        TraceUtil.beginSection("createCompatFfmpegAudioDecoder")
        val decodeFormat = normalizeDecodeFormat(format)
        val initialInputBufferSize = if (decodeFormat.maxInputSize != Format.NO_VALUE) {
            decodeFormat.maxInputSize
        } else {
            DEFAULT_INPUT_BUFFER_SIZE
        }
        val outputChannelCount = getOutputChannelCount(decodeFormat)
        Timber.d(
            "ffmpeg output mime=%s sourceChannels=%d outputChannels=%d downmix=%s",
            decodeFormat.sampleMimeType,
            decodeFormat.channelCount,
            outputChannelCount,
            outputChannelCount == STEREO_CHANNEL_COUNT && decodeFormat.channelCount > STEREO_CHANNEL_COUNT,
        )
        if (MimeTypes.AUDIO_ALAC == decodeFormat.sampleMimeType) {
            val data = if (decodeFormat.initializationData.isEmpty()) null else decodeFormat.initializationData[0]
            Log.d(
                "ExoAlac",
                "init count=${decodeFormat.initializationData.size} length=${data?.size ?: 0} prefix=${hexPrefix(data)}",
            )
        }
        val decoder = FfmpegAudioDecoder(
            decodeFormat,
            NUM_BUFFERS,
            NUM_BUFFERS,
            initialInputBufferSize,
            shouldOutputFloat(decodeFormat, outputChannelCount),
            outputChannelCount,
        )
        TraceUtil.endSection()
        return decoder
    }

    override fun getOutputFormat(decoder: FfmpegAudioDecoder): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(decoder.channelCount)
            .setSampleRate(decoder.sampleRate)
            .setPcmEncoding(decoder.encoding)
            .build()
    }

    override fun supportsMixedMimeTypeAdaptation(): Int {
        return RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
    }

    private fun isSystemDecoderSupported(format: Format): Boolean {
        val sampleMimeType = format.sampleMimeType ?: return false
        if (context == null) return false
        return try {
            MediaCodecSelector.DEFAULT.getDecoderInfos(sampleMimeType, false, false).any { info ->
                info.isFormatSupported(format)
            }
        } catch (_: MediaCodecUtil.DecoderQueryException) {
            false
        }
    }

    private fun sinkSupportsFormat(format: Format, pcmEncoding: @C.PcmEncoding Int, channelCount: Int): Boolean {
        val pcmFormat = Util.getPcmFormat(pcmEncoding, channelCount, format.sampleRate)
        if (!sinkSupportsFormat(pcmFormat)) return false

        if (format.sampleRate <= 0 || channelCount <= 0) return true
        val channelConfig = Util.getAudioTrackChannelConfig(channelCount)
        if (channelConfig == AudioFormat.CHANNEL_INVALID) return false
        return try {
            AudioTrack.getMinBufferSize(format.sampleRate, channelConfig, pcmEncoding) != AudioTrack.ERROR_BAD_VALUE
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun getOutputChannelCount(format: Format): Int {
        val sourceSupported = sinkSupportsFormat(format, C.ENCODING_PCM_16BIT, format.channelCount) ||
            sinkSupportsFormat(format, C.ENCODING_PCM_FLOAT, format.channelCount)
        val stereoSupported = sinkSupportsFormat(format, C.ENCODING_PCM_16BIT, STEREO_CHANNEL_COUNT) ||
            sinkSupportsFormat(format, C.ENCODING_PCM_FLOAT, STEREO_CHANNEL_COUNT)
        return resolveOutputChannelCount(format.channelCount, sourceSupported, stereoSupported)
    }

    private fun shouldOutputFloat(format: Format, outputChannelCount: Int): Boolean {
        if (!sinkSupportsFormat(format, C.ENCODING_PCM_16BIT, outputChannelCount)) return true
        val floatSupport = getSinkFormatSupport(Util.getPcmFormat(C.ENCODING_PCM_FLOAT, outputChannelCount, format.sampleRate))
        return floatSupport == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY && format.sampleMimeType != MimeTypes.AUDIO_AC3
    }

    companion object {
        private const val NUM_BUFFERS = 16
        private const val DEFAULT_INPUT_BUFFER_SIZE = 5760
        const val STEREO_CHANNEL_COUNT = 2
        private const val MIME_AUDIO_MEDIA3_DTS_HD_MA_CORELESS = "audio/x-exoplayer-dtsma-coreless"
        private const val MIME_AUDIO_AV3A = "audio/av3a"

        @JvmStatic
        fun normalizeDecodeFormat(format: Format): Format {
            val sampleMimeType = format.sampleMimeType ?: return format
            if (sampleMimeType.startsWith("${MimeTypes.AUDIO_DTS_HD};") || sampleMimeType == MIME_AUDIO_MEDIA3_DTS_HD_MA_CORELESS) {
                return format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).build()
            }
            if (sampleMimeType == MimeTypes.AUDIO_AMR) {
                return format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AMR_NB).build()
            }
            if (sampleMimeType == MIME_AUDIO_AV3A ||
                (sampleMimeType == MimeTypes.AUDIO_MP4 &&
                    format.codecs?.lowercase(Locale.US)?.startsWith("av3a") == true)
            ) {
                return format.buildUpon().setSampleMimeType(MIME_AUDIO_AV3A).build()
            }
            if (sampleMimeType == MimeTypes.AUDIO_ALAC && format.initializationData.isNotEmpty()) {
                try {
                    val rawData = format.initializationData[0]
                    val initializationData = normalizeAlacInitializationData(rawData)
                    val config = CodecSpecificDataUtil.parseAlacAudioSpecificConfig(initializationData)
                    val builder = format.buildUpon()
                    if (format.sampleRate == Format.NO_VALUE && config.first != null && config.first > 0) {
                        builder.setSampleRate(config.first)
                    }
                    if (format.channelCount == Format.NO_VALUE && config.second != null && config.second > 0) {
                        builder.setChannelCount(config.second)
                    }
                    if (initializationData !== rawData) {
                        builder.setInitializationData(listOf(initializationData))
                    }
                    return builder.build()
                } catch (_: RuntimeException) {
                    // Keep the original format; the decoder will report malformed ALAC data.
                }
            }
            return format
        }

        @JvmStatic
        fun normalizeAlacInitializationData(initializationData: ByteArray): ByteArray {
            if (initializationData.size >= 12 &&
                readInt(initializationData, 0) == initializationData.size &&
                initializationData[4] == 'a'.code.toByte() &&
                initializationData[5] == 'l'.code.toByte() &&
                initializationData[6] == 'a'.code.toByte() &&
                initializationData[7] == 'c'.code.toByte()
            ) {
                return initializationData.copyOfRange(12, initializationData.size)
            }
            return initializationData
        }

        @JvmStatic
        fun resolveOutputChannelCount(sourceChannelCount: Int, sourceSupported: Boolean, stereoSupported: Boolean): Int {
            if (sourceSupported) return sourceChannelCount
            if (sourceChannelCount > STEREO_CHANNEL_COUNT && stereoSupported) return STEREO_CHANNEL_COUNT
            return Format.NO_VALUE
        }

        private fun readInt(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        }

        private fun hexPrefix(data: ByteArray?): String {
            if (data == null || data.isEmpty()) return ""
            val length = minOf(data.size, 36)
            val out = StringBuilder(length * 2)
            for (i in 0 until length) {
                val value = data[i].toInt() and 0xFF
                if (value < 0x10) out.append('0')
                out.append(value.toString(16))
            }
            return out.toString()
        }
    }
}
