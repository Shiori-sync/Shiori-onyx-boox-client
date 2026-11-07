package ru.feryafox.shiori.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Explicit binding with bounded lifetime. Bind/unbind run on the main thread. */
class RemoteBinding(context: Context, pkg: String, service: String) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val result = CompletableFuture<IBinder>()
    private var bound = false
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                result.complete(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                result.completeExceptionally(IllegalStateException("service disconnected"))
            }

            override fun onNullBinding(name: ComponentName) {
                result.completeExceptionally(IllegalStateException("null binding"))
            }

            override fun onBindingDied(name: ComponentName) {
                result.completeExceptionally(IllegalStateException("binding died"))
            }
        }

    init {
        main.post {
            try {
                bound =
                    this.context.bindService(
                        Intent().setComponent(ComponentName(pkg, service)),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                if (!bound)
                    result.completeExceptionally(
                        IllegalStateException("bindService returned false")
                    )
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }
    }

    fun await(): IBinder = result.get(15, TimeUnit.SECONDS)

    override fun close() {
        main.post {
            if (bound) {
                context.unbindService(connection)
                bound = false
            }
        }
    }
}
