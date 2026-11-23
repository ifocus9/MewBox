package com.mewbox.tv.player.exo.decoder

import android.media.AudioFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.CompatFfmpegAudioRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class CompatFfmpegAudioRendererTest {

    @Test
    fun supportedMultichannelPcm_isPreserved() {
        assertEquals(6, CompatFfmpegAudioRenderer.resolveOutputChannelCount(6, true, true))
    }

    @Test
    fun unsupportedMultichannelPcm_downmixesToStereo() {
        assertEquals(2, CompatFfmpegAudioRenderer.resolveOutputChannelCount(6, false, true))
    }

    @Test
    fun noSupportedPcmOutput_rejectsFormat() {
        assertEquals(Format.NO_VALUE, CompatFfmpegAudioRenderer.resolveOutputChannelCount(6, false, false))
    }

    @Test
    fun normalizeDecodeFormat_normalizesDtsHdProfile() {
        val format = Format.Builder()
            .setSampleMimeType("${MimeTypes.AUDIO_DTS_HD};profile=dtsma")
            .build()
        val normalized = CompatFfmpegAudioRenderer.normalizeDecodeFormat(format)
        assertEquals(MimeTypes.AUDIO_DTS_HD, normalized.sampleMimeType)
    }

    @Test
    fun normalizeDecodeFormat_normalizesCorelessDtsHdMa() {
        val format = Format.Builder()
            .setSampleMimeType("audio/x-exoplayer-dtsma-coreless")
            .build()
        val normalized = CompatFfmpegAudioRenderer.normalizeDecodeFormat(format)
        assertEquals(MimeTypes.AUDIO_DTS_HD, normalized.sampleMimeType)
    }

    @Test
    fun normalizeDecodeFormat_normalizesAmr() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AMR)
            .build()
        val normalized = CompatFfmpegAudioRenderer.normalizeDecodeFormat(format)
        assertEquals(MimeTypes.AUDIO_AMR_NB, normalized.sampleMimeType)
    }

    @Test
    fun normalizeDecodeFormat_normalizesAv3aMp4() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MP4)
            .setCodecs("av3a.02")
            .build()
        val normalized = CompatFfmpegAudioRenderer.normalizeDecodeFormat(format)
        assertEquals("audio/av3a", normalized.sampleMimeType)
    }

    @Test
    fun alacInitializationData_stripsFullAtomHeader() {
        val cookie = ByteArray(24)
        cookie[0] = 0
        cookie[1] = 0
        cookie[2] = 0x10
        cookie[3] = 0
        val atom = ByteArray(36)
        atom[3] = 36
        atom[4] = 'a'.code.toByte()
        atom[5] = 'l'.code.toByte()
        atom[6] = 'c'.code.toByte()
        atom[7] = 'k'.code.toByte() // test mismatch
        assertArrayEquals(atom, CompatFfmpegAudioRenderer.normalizeAlacInitializationData(atom))

        atom[6] = 'a'.code.toByte()
        atom[7] = 'c'.code.toByte() // 'alac'
        System.arraycopy(cookie, 0, atom, 12, cookie.size)
        assertArrayEquals(cookie, CompatFfmpegAudioRenderer.normalizeAlacInitializationData(atom))
    }

    @Test
    fun alacInitializationData_keepsMagicCookie() {
        val cookie = ByteArray(24)
        assertArrayEquals(cookie, CompatFfmpegAudioRenderer.normalizeAlacInitializationData(cookie))
    }

    @Test
    fun normalizeDecodeFormat_parsesAlacCookieAndFullAtom() {
        val cookie = ByteArray(24).apply {
            this[5] = 24
            this[9] = 6
            // 48_000 Hz in big-endian at bytes 20..23
            this[22] = 0xBB.toByte()
            this[23] = 0x80.toByte()
        }
        val fromCookie = CompatFfmpegAudioRenderer.normalizeDecodeFormat(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_ALAC)
                .setInitializationData(listOf(cookie))
                .build(),
        )
        assertEquals(48_000, fromCookie.sampleRate)
        assertEquals(6, fromCookie.channelCount)
        assertArrayEquals(cookie, fromCookie.initializationData[0])

        val fullAtom = ByteArray(36).apply {
            this[3] = 36
            this[4] = 'a'.code.toByte()
            this[5] = 'l'.code.toByte()
            this[6] = 'a'.code.toByte()
            this[7] = 'c'.code.toByte()
            System.arraycopy(cookie, 0, this, 12, cookie.size)
        }
        val fromAtom = CompatFfmpegAudioRenderer.normalizeDecodeFormat(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_ALAC)
                .setInitializationData(listOf(fullAtom))
                .build(),
        )
        assertEquals(48_000, fromAtom.sampleRate)
        assertEquals(6, fromAtom.channelCount)
        assertArrayEquals(cookie, fromAtom.initializationData[0])
    }

    @Test
    fun resolveAudioTrackChannelConfig_preservesSourceMaskOnlyForSourceProbe() {
        val sourceMask = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        assertEquals(
            sourceMask,
            CompatFfmpegAudioRenderer.resolveAudioTrackChannelConfig(
                sourceChannelCount = 8,
                sourceChannelMask = sourceMask,
                targetChannelCount = 8,
            ),
        )
        assertEquals(
            AudioFormat.CHANNEL_OUT_STEREO,
            CompatFfmpegAudioRenderer.resolveAudioTrackChannelConfig(
                sourceChannelCount = 8,
                sourceChannelMask = sourceMask,
                targetChannelCount = CompatFfmpegAudioRenderer.STEREO_CHANNEL_COUNT,
            ),
        )
        assertEquals(
            Util.getAudioTrackChannelConfig(6),
            CompatFfmpegAudioRenderer.resolveAudioTrackChannelConfig(
                sourceChannelCount = 6,
                sourceChannelMask = Format.NO_VALUE,
                targetChannelCount = 6,
            ),
        )
    }
}

