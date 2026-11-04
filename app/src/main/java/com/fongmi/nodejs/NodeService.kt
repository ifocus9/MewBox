package com.fongmi.nodejs

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
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

    private val binder = Binder()
    private val started = AtomicBoolean()

    companion object {
        const val ACTION_START = "com.fongmi.nodejs.START"
        const val EXTRA_INDEX = "index"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_DATA = "data"
        const val EXTRA_BRIDGE_PORT = "bridgePort"
        const val EXTRA_TOKEN = "token"
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
        start(intent)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        start(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    fun start(intent: Intent?) {
        if (intent == null || intent.action != ACTION_START || !started.compareAndSet(false, true)) return
        val index = intent.getStringExtra(EXTRA_INDEX)
        val config = intent.getStringExtra(EXTRA_CONFIG)
        val data = intent.getStringExtra(EXTRA_DATA)
        val bridgePort = intent.getIntExtra(EXTRA_BRIDGE_PORT, 0)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        Thread({ start(index, config, data, bridgePort, token) }, "nodejs-runtime").start()
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

    private fun reportError(port: Int, token: String?, message: String) {
        if (port <= 0) return
        try {
            CLIENT.newCall(errorRequest(port, token, message)).execute().close()
        } catch (_: Exception) {
        }
    }

    @Throws(JSONException::class)
    private fun errorRequest(port: Int, token: String?, message: String): Request {
        val options = JSONObject().put("token", token).put("message", message)
        val body = JSONObject().put("action", "nodeError").put("opt", options)
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        return Request.Builder()
            .url("http://127.0.0.1:$port/msg")
            .header(HEADER_TOKEN, token ?: "")
            .post(bytes.toRequestBody(JSON))
            .build()
    }
}

