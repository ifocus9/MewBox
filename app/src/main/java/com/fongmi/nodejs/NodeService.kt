package com.fongmi.nodejs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

@Keep
class NodeService : Service() {

    private val foregroundActive = AtomicBoolean(false)
    @Volatile private var lastAddress: String? = null
    @Volatile private var lastVersion: String = ""
    @Volatile private var lastArch: String = ""

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TRANSACTION_QUERY_STATE) {
                data.enforceInterface(BINDER_DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(if (foregroundActive.get()) 1 else 0)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
    private val started = AtomicBoolean()

    companion object {
        const val ACTION_START = "com.fongmi.nodejs.START"
        const val ACTION_START_FOREGROUND = "com.fongmi.nodejs.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.fongmi.nodejs.STOP_FOREGROUND"
        const val ACTION_FOREGROUND_STATE_CHANGED = "com.fongmi.nodejs.FOREGROUND_STATE_CHANGED"
        const val EXTRA_INDEX = "index"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_DATA = "data"
        const val EXTRA_BRIDGE_PORT = "bridgePort"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_VERSION = "version"
        const val EXTRA_ARCH = "arch"
        const val EXTRA_FOREGROUND_ACTIVE = "foregroundActive"
        const val BINDER_DESCRIPTOR = "com.fongmi.nodejs.NodeService"
        const val TRANSACTION_QUERY_STATE = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val NOTIFICATION_CHANNEL_ID = "mewbox_node_t4_share"
        private const val NOTIFICATION_ID = 9988
        private const val HEADER_TOKEN = "X-CatVod-Token"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val CLIENT = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()

        @JvmStatic
        private external fun startNodeWithArguments(arguments: Array<String>): Int
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (!started.get() && lastAddress.isNullOrBlank()) {
                stopSelf()
                return START_NOT_STICKY
            }
            return if (foregroundActive.get()) START_STICKY else START_NOT_STICKY
        }
        return when (intent.action) {
            ACTION_START_FOREGROUND -> {
                intent.getStringExtra(EXTRA_ADDRESS)?.takeIf { it.isNotBlank() }?.let { lastAddress = it }
                intent.getStringExtra(EXTRA_VERSION)?.takeIf { it.isNotBlank() }?.let { lastVersion = it }
                intent.getStringExtra(EXTRA_ARCH)?.takeIf { it.isNotBlank() }?.let { lastArch = it }
                enterForeground()
                foregroundActive.set(true)
                notifyForegroundStateChanged(true)
                START_STICKY
            }
            ACTION_STOP_FOREGROUND -> {
                foregroundActive.set(false)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notifyForegroundStateChanged(false)
                stopSelf()
                START_NOT_STICKY
            }
            ACTION_START -> {
                start(intent)
                if (foregroundActive.get()) START_STICKY else START_NOT_STICKY
            }
            else -> if (foregroundActive.get()) START_STICKY else START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        start(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        if (foregroundActive.getAndSet(false)) {
            notifyForegroundStateChanged(false)
        }
        Process.killProcess(Process.myPid())
    }

    fun start(intent: Intent?) {
        if (intent == null || intent.action != ACTION_START) return
        val index = intent.getStringExtra(EXTRA_INDEX)
        val config = intent.getStringExtra(EXTRA_CONFIG)
        val data = intent.getStringExtra(EXTRA_DATA)
        val bridgePort = intent.getIntExtra(EXTRA_BRIDGE_PORT, 0)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (!started.compareAndSet(false, true)) {
            if (!lastAddress.isNullOrBlank()) {
                Thread({ reportExistingServerIfAlive(bridgePort, token) }, "nodejs-reconnect").start()
            }
            return
        }
        Thread({ start(index, config, data, bridgePort, token) }, "nodejs-runtime").start()
    }

    private fun enterForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null && manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "T4 后台服务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                manager.createNotificationChannel(channel)
            }
        }
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildForegroundNotification(), fgType)
    }

    private fun buildForegroundNotification(): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val stopIntent = Intent(this, NodeService::class.java).setAction(ACTION_STOP_FOREGROUND)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val t4Url = lastAddress?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { "$it/t4/config" }.orEmpty()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MewBox T4 服务运行中")
            .setContentText(t4Url.ifBlank { "本地节点服务持续运行中" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply {
                if (contentIntent != null) setContentIntent(contentIntent)
            }
            .addAction(0, "停止", stopPendingIntent)
            .build()
    }

    private fun notifyForegroundStateChanged(active: Boolean) {
        sendBroadcast(
            Intent(ACTION_FOREGROUND_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_FOREGROUND_ACTIVE, active),
        )
    }

    private fun reportExistingServerIfAlive(bridgePort: Int, token: String?) {
        val addr = lastAddress ?: return
        if (isServerPortAlive(addr)) {
            reportStarted(bridgePort, token, addr)
        } else {
            reportError(bridgePort, token, "Node server port unreachable")
            Process.killProcess(Process.myPid())
        }
    }

    private fun isServerPortAlive(address: String): Boolean {
        return try {
            val uri = URI(address)
            val host = uri.host?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
            val port = uri.port
            if (port <= 0) return false
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 500)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun start(index: String?, config: String?, data: String?, bridgePort: Int, token: String?) {
        val message = try {
            val indexFile = requirePrivateFile(index)
            val configFile = requirePrivateFile(config)
            val dataDir = requirePrivateDirectory(data)
            val code = startRuntime(indexFile, configFile, dataDir, bridgePort, token)
            "Node exited with code $code"
        } catch (e: Throwable) {
            e.message ?: e.javaClass.simpleName
        }
        reportError(bridgePort, token, message)
        Process.killProcess(Process.myPid())
    }

    private fun startRuntime(
        indexFile: File,
        configFile: File,
        dataDir: File,
        bridgePort: Int,
        token: String?,
    ): Int {
        val bootstrap = extractBootstrap()
        try {
            System.loadLibrary("c++_shared")
        } catch (_: Throwable) {
        }
        System.loadLibrary("node")
        System.loadLibrary("nodebridge")
        val nativeLib = applicationInfo.nativeLibraryDir + "/libnodebridge.so"
        val arguments = arrayOf(
            "node",
            bootstrap.absolutePath,
            indexFile.absolutePath,
            configFile.absolutePath,
            dataDir.absolutePath,
            bridgePort.toString(),
            token.orEmpty(),
            nativeLib,
        )
        return startNodeWithArguments(arguments)
    }

    private fun requirePrivateFile(path: String?): File {
        val file = requirePrivatePath(path)
        if (!file.isFile) throw IOException("Node bundle file is missing: $path")
        return file
    }

    private fun requirePrivateDirectory(path: String?): File {
        val file = requirePrivatePath(path)
        if (!file.isDirectory && !file.mkdirs() && !file.isDirectory) {
            throw IOException("Unable to create Node data directory: $path")
        }
        return file
    }

    private fun requirePrivatePath(path: String?): File {
        if (path.isNullOrEmpty()) throw IOException("Node path is empty")
        val root = File(filesDir, "nodejs").canonicalFile
        val file = File(path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) {
            throw IOException("Node path is outside app storage: $path")
        }
        return file
    }

    private fun extractBootstrap(): File {
        val target = File(filesDir, "nodejs/runtime/bootstrap-v2.js")
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        assets.open("nodejs/bootstrap.js").use { input ->
            FileOutputStream(target).use { output ->
                val buf = ByteArray(8192)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                }
                output.flush()
            }
        }
        return target
    }

    private fun reportStarted(port: Int, token: String?, address: String) {
        if (port <= 0) return
        try {
            val options = JSONObject()
                .put("address", address)
                .put("token", token.orEmpty())
                .put("pid", Process.myPid())
                .put("version", lastVersion)
                .put("arch", lastArch)
            CLIENT.newCall(bridgeRequest(port, token, "serverStarted", options)).execute().close()
        } catch (_: Exception) {
        }
    }

    private fun reportError(port: Int, token: String?, message: String) {
        if (port <= 0) return
        try {
            val options = JSONObject().put("token", token).put("message", message)
            CLIENT.newCall(bridgeRequest(port, token, "nodeError", options)).execute().close()
        } catch (_: Exception) {
        }
    }

    @Throws(JSONException::class)
    private fun bridgeRequest(port: Int, token: String?, action: String, options: JSONObject): Request {
        val body = JSONObject().put("action", action).put("opt", options)
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        return Request.Builder()
            .url("http://127.0.0.1:$port/msg")
            .header(HEADER_TOKEN, token ?: "")
            .post(bytes.toRequestBody(JSON))
            .build()
    }
}

