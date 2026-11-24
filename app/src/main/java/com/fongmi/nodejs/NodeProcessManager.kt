package com.fongmi.nodejs

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `:nodejs` 独立进程生命周期管理器：
 * 负责 `NodeService` 的跨进程绑定、启动就绪等待、`DeathRecipient` 死亡监听与优雅停止。
 */
internal class NodeProcessManager(context: Context) {

    private val context: Context = context.applicationContext ?: context

    private val _addressFlow = MutableStateFlow("")
    val addressFlow: StateFlow<String> = _addressFlow.asStateFlow()

    private val _foregroundActiveFlow = MutableStateFlow(false)
    val foregroundActiveFlow: StateFlow<Boolean> = _foregroundActiveFlow.asStateFlow()

    @Volatile var address: String? = null
        private set
    @Volatile var token: String? = null
        private set
    @Volatile var generation: Long = 0
        private set
    @Volatile var startedAt: Long = 0
        private set
    @Volatile var nodePid: Int = 0
        private set
    @Volatile var nodeVersion: String = ""
        private set
    @Volatile var nodeArch: String = ""
        private set

    @Volatile private var startLatch: CountDownLatch? = null
    @Volatile private var startError: Exception? = null
    @Volatile private var connection: NodeConnection? = null

    private val foregroundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NodeService.ACTION_FOREGROUND_STATE_CHANGED) return
            val active = intent.getBooleanExtra(NodeService.EXTRA_FOREGROUND_ACTIVE, false)
            _foregroundActiveFlow.value = active
        }
    }

    init {
        ContextCompat.registerReceiver(
            this.context,
            foregroundReceiver,
            IntentFilter(NodeService.ACTION_FOREGROUND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun isRunning(): Boolean = !address.isNullOrEmpty() && connection?.isAlive() == true

    fun isForegroundActive(): Boolean = _foregroundActiveFlow.value

    fun startForegroundShare() {
        val intent = Intent(context, NodeService::class.java)
            .setAction(NodeService.ACTION_START_FOREGROUND)
            .putExtra(NodeService.EXTRA_ADDRESS, address.orEmpty())
            .putExtra(NodeService.EXTRA_VERSION, nodeVersion)
            .putExtra(NodeService.EXTRA_ARCH, nodeArch)
        ContextCompat.startForegroundService(context, intent)
        _foregroundActiveFlow.value = true
    }

    fun stopForegroundShare() {
        _foregroundActiveFlow.value = false
        try {
            context.startService(
                Intent(context, NodeService::class.java).setAction(NodeService.ACTION_STOP_FOREGROUND),
            )
        } catch (e: IllegalStateException) {
            Timber.w(e, "停止 T4 前台服务时后台启动受限")
        }
    }

    fun beginSession(newToken: String): Long {
        generation++
        token = newToken
        return generation
    }

    @Throws(Exception::class)
    fun bindAndAwait(
        prepared: NodeBundle,
        bridgePort: Int,
        sessionToken: String,
        checkCanceled: () -> Unit,
    ) {
        address = null
        _addressFlow.value = ""
        startError = null
        val latch = CountDownLatch(1)
        startLatch = latch

        val data = prepared.data
        val intent = Intent(context, NodeService::class.java)
            .setAction(NodeService.ACTION_START)
            .putExtra(NodeService.EXTRA_INDEX, prepared.index.absolutePath)
            .putExtra(NodeService.EXTRA_CONFIG, prepared.config.absolutePath)
            .putExtra(NodeService.EXTRA_DATA, data.absolutePath)
            .putExtra(NodeService.EXTRA_BRIDGE_PORT, bridgePort)
            .putExtra(NodeService.EXTRA_TOKEN, sessionToken)

        val next = NodeConnection(generation)
        connection = next
        next.bound = context.bindService(intent, next, Context.BIND_AUTO_CREATE)
        if (!next.bound) throw IOException("Unable to bind Node service")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS)
        while (!latch.await(100, TimeUnit.MILLISECONDS)) {
            checkCanceled()
            if (System.nanoTime() >= deadline) throw IOException("Node startup timed out")
        }
        checkCanceled()
        startError?.let { throw it }
        if (address.isNullOrEmpty()) throw IOException("Node server did not report an address")
        startedAt = System.currentTimeMillis()
        Timber.i("Node.js 运行时已启动: %s (PID: %d)", address, nodePid)
    }

    fun stopService() {
        _foregroundActiveFlow.value = false
        val current = connection
        if (current != null) {
            synchronized(current) {
                connection = null
            }
            current.stop()
            return
        }
        try {
            context.stopService(Intent(context, NodeService::class.java))
        } catch (_: Exception) {
        }
    }

    fun resetState() {
        address = null
        _addressFlow.value = ""
        _foregroundActiveFlow.value = false
        token = null
        startLatch = null
        startError = null
        generation++
        startedAt = 0
        nodePid = 0
        nodeVersion = ""
        nodeArch = ""
    }

    fun invalidateAddressAndBumpGeneration() {
        address = null
        _addressFlow.value = ""
        generation++
    }

    fun onStarted(opt: JsonObject) {
        val current = connection
        if (current == null || !tokenMatches(opt)) return
        synchronized(current) {
            if (connection !== current || current.stopped.count == 0L || !tokenMatches(opt)) return
            val value = opt["address"]?.jsonPrimitive?.content.orEmpty()
            if (value.startsWith("http://") || value.startsWith("https://")) {
                val incomingPid = opt["pid"]?.jsonPrimitive?.intOrNull ?: 0
                if (nodePid != 0 && incomingPid != 0 && incomingPid != nodePid) {
                    // 系统自动重建 :nodejs 进程时 PID 会变化，推进 generation 使爬虫按需重新 /init，并同步当前连接的代际
                    generation++
                    current.connGeneration = generation
                }
                val resolvedAddress = value.replace("://localhost:", "://127.0.0.1:")
                address = resolvedAddress
                _addressFlow.value = resolvedAddress
                nodePid = incomingPid
                nodeVersion = opt["version"]?.jsonPrimitive?.content.orEmpty()
                nodeArch = opt["arch"]?.jsonPrimitive?.content.orEmpty()
                startLatch?.countDown()
            }
        }
    }

    fun onError(opt: JsonObject) {
        if (!tokenMatches(opt)) return
        val message = opt["message"]?.jsonPrimitive?.content.orEmpty()
        startError = if (message.isEmpty()) null else IOException(message)
        startLatch?.countDown()
    }

    private fun tokenMatches(opt: JsonObject): Boolean {
        val curToken = token
        return !curToken.isNullOrEmpty() && curToken == opt["token"]?.jsonPrimitive?.content.orEmpty()
    }

    private fun onServiceLost(message: String) {
        address = null
        _addressFlow.value = ""
        _foregroundActiveFlow.value = false
        generation++
        if (startError == null) startError = IOException(message)
        startLatch?.countDown()
    }

    private inner class NodeConnection(@Volatile var connGeneration: Long) : ServiceConnection, IBinder.DeathRecipient {
        val connected = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        @Volatile var binder: IBinder? = null
        @Volatile var bound: Boolean = false

        /**
         * 校验远端 `:nodejs` 进程仍存活：
         * 后台被系统回收后，切回前台时 `onStart()` 可能抢在 `binderDied` / `onServiceDisconnected` 回调之前执行，
         * 通过 `isBinderAlive && pingBinder()` 可立即感知远端进程死亡并触发重启。
         */
        fun isAlive(): Boolean {
            if (!bound || stopped.count == 0L) return false
            val target = binder ?: return true
            return try {
                target.isBinderAlive && target.pingBinder()
            } catch (_: Exception) {
                false
            }
        }

        @Synchronized
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            try {
                service?.linkToDeath(this, 0)
                if (service != null) {
                    queryRemoteState(service)
                }
            } catch (_: RemoteException) {
                stopped.countDown()
                notifyLost("Node service died while binding")
            } finally {
                connected.countDown()
            }
        }

        private fun queryRemoteState(service: IBinder) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(NodeService.BINDER_DESCRIPTOR)
                if (service.transact(NodeService.TRANSACTION_QUERY_STATE, data, reply, 0)) {
                    reply.readException()
                    val active = reply.readInt() != 0
                    _foregroundActiveFlow.value = active
                }
            } catch (_: Exception) {
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected.countDown()
            stopped.countDown()
            notifyLost("Node service disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            connected.countDown()
            stopped.countDown()
            notifyLost("Node service binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            connected.countDown()
            stopped.countDown()
            notifyLost("Node service returned no binder")
        }

        @Synchronized
        override fun binderDied() {
            stopped.countDown()
            notifyLost("Node service process died")
        }

        private fun notifyLost(message: String) {
            if (connection !== this || generation != connGeneration) return
            unbind()
            onServiceLost(message)
        }

        @Synchronized
        fun unbind() {
            if (!bound) return
            bound = false
            try {
                context.unbindService(this)
            } catch (_: Exception) {
            }
        }

        fun stop(): Boolean {
            awaitConnection()
            val target = binder
            unbind()
            requestStop()
            awaitStopped(target)
            binder = null
            return target != null && !target.isBinderAlive
        }

        private fun requestStop() {
            try {
                context.stopService(Intent(context, NodeService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun awaitConnection() {
            if (bound && binder == null) {
                try {
                    connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private fun awaitStopped(target: IBinder?) {
            if (target != null && target.isBinderAlive) {
                try {
                    stopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    companion object {
        private const val START_TIMEOUT_SECONDS = 45L
        private const val STOP_TIMEOUT_SECONDS = 5L
        private const val BIND_TIMEOUT_SECONDS = 1L
    }
}
