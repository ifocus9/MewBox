package com.mewbox.tv.player.exo

import android.net.Uri
import androidx.annotation.Nullable
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import timber.log.Timber
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 移植自 MewPlayer / FongMi：
 * 在网盘或远程流媒体 HTTP 响应被异常截断、超时或抛出 "unexpected end of stream" / "Connection reset" 时，
 * 自动在未读断点处重新发起 Range 连接接续播放，避免播放器抛出致命错误中断播放。
 */
@UnstableApi
class HttpEofRecoveryDataSource(private val upstream: DataSource) : DataSource {

    companion object {
        private const val MAX_CONSECUTIVE_RECONNECTS = 5
        private const val MAX_OPEN_RETRIES = 3
        private val UNSET_LENGTH: Long = C.LENGTH_UNSET.toLong()

        fun isRecoverableEof(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                if (current is EOFException) {
                    return true
                }
                if (current is ProtocolException && messageContainsUnexpectedEnd(current.message)) {
                    return true
                }
                if (current is SocketException) {
                    return true
                }
                if (current is SocketTimeoutException) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        private fun messageContainsUnexpectedEnd(message: String?): Boolean {
            return message != null && (
                message.lowercase().contains("unexpected end of stream") ||
                message.lowercase().contains("unexpected eof")
            )
        }

        fun remainingLength(requestedLength: Long, bytesRead: Long): Long {
            if (requestedLength == UNSET_LENGTH) return UNSET_LENGTH
            return (requestedLength - bytesRead.coerceAtLeast(0L)).coerceAtLeast(0L)
        }
    }

    private var dataSpec: DataSpec? = null
    private var bytesRead: Long = 0L
    private var consecutiveReconnects: Int = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.bytesRead = 0L
        this.consecutiveReconnects = 0

        var attempt = 0
        while (true) {
            try {
                return upstream.open(dataSpec)
            } catch (error: IOException) {
                attempt++
                if (!isRecoverableEof(error) || attempt >= MAX_OPEN_RETRIES) {
                    throw error
                }
                Timber.w(error, "HttpEofRecoveryDataSource: 打开数据流失败，重试连接 (%d/%d)...", attempt, MAX_OPEN_RETRIES)
                try {
                    upstream.close()
                } catch (_: IOException) {}
                try {
                    Thread.sleep(100L * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            try {
                val read = upstream.read(buffer, offset, length)
                if (read > 0) {
                    bytesRead += read
                    consecutiveReconnects = 0
                }
                return read
            } catch (error: IOException) {
                if (!isRecoverableEof(error) || consecutiveReconnects >= MAX_CONSECUTIVE_RECONNECTS || dataSpec == null) {
                    throw error
                }
                consecutiveReconnects++
                try {
                    Thread.sleep(80L * consecutiveReconnects)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                reconnectAtCurrentPosition()
            }
        }
    }

    @Nullable
    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        dataSpec = null
        bytesRead = 0L
        consecutiveReconnects = 0
        upstream.close()
    }

    @Throws(IOException::class)
    private fun reconnectAtCurrentPosition() {
        try {
            upstream.close()
        } catch (_: IOException) {}

        val currentSpec = dataSpec ?: return
        val remaining: Long = if (currentSpec.length == UNSET_LENGTH) {
            UNSET_LENGTH
        } else {
            remainingLength(currentSpec.length, bytesRead)
        }
        val retrySpec = if (remaining == UNSET_LENGTH) {
            currentSpec.subrange(bytesRead)
        } else {
            currentSpec.subrange(bytesRead, remaining)
        }
        upstream.open(retrySpec)
        Timber.w("HttpEofRecoveryDataSource: 已自动恢复意外中断的网盘连接 offset=%d, remaining=%d, attempt=%d", retrySpec.position, remaining, consecutiveReconnects)
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return HttpEofRecoveryDataSource(upstreamFactory.createDataSource())
        }
    }
}
