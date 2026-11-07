package ru.feryafox.shiori

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import org.json.JSONObject

/** Process-owned work and results. The application context is the only retained context. */
class ShioriCoordinator private constructor(private val context: Context) {
    fun interface Observer {
        fun changed()
    }

    fun interface Task {
        fun run()
    }

    class Prompt(val kind: String, val data: Any?)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val checks = Executors.newSingleThreadExecutor()
    private val operation = SingleOperation(worker) { main.post(it) }
    private val controller = TransitionController(context)
    private var observer: Observer? = null
    var connection: ConnectionState? = null
        private set

    var message = ""
        private set

    var statusError = ""
        private set

    var endpointError = ""
        private set

    var progressResource = 0
        private set

    private var locale = ""
    var pendingServer = ""
        private set

    var draftServer = ""
    var draftCode = ""
    var hasDraft = false
        private set

    var needsFreshCode = false
        private set

    var checking = false
        private set

    var endpointChanges = 0
        private set

    private var lastCheck = -1L
    private var generation = 0L
    private var prompt: Prompt? = null

    init {
        controller.progress =
            TransitionController.Progress { resource ->
                main.post {
                    progressResource = resource
                    changed()
                }
            }
    }

    fun localeChanged(languageTags: String) {
        if (locale != languageTags) {
            locale = languageTags
            message = ""
            statusError = ""
            endpointError = ""
        }
    }

    fun observe(next: Observer) {
        observer = next
        changed()
    }

    fun detach(previous: Observer) {
        if (observer === previous) observer = null
    }

    fun busy(): Boolean = operation.busy()

    private fun text(id: Int): String = context.getString(id)

    private fun changed() {
        observer?.changed()
    }

    fun takePrompt(): Prompt? = prompt.also { prompt = null }

    fun retainPrompt(value: Prompt) {
        if (prompt == null) prompt = value
    }

    fun showMessage(value: String) {
        message = value
        changed()
    }

    private fun readStatus() {
        try {
            connection = ConnectionState(controller.status())
            statusError = ""
        } catch (error: Exception) {
            statusError = error(error)
            connection =
                try {
                    ConnectionState(controller.localStatus())
                } catch (_: Exception) {
                    ConnectionState(JSONObject())
                }
        }
        try {
            val pending = controller.state.read("pending-setup")
            hasDraft = pending?.optString("stage") == "draft"
            needsFreshCode =
                pending != null &&
                    !hasDraft &&
                    connection?.screen != ConnectionState.Screen.ENROLLED
            pendingServer = pending?.optString("server") ?: ""
        } catch (error: Exception) {
            statusError = error(error)
            hasDraft = false
            needsFreshCode = false
        }
    }

    private fun run(progressId: Int, checkAfter: Boolean = false, task: () -> Unit) {
        if (busy()) return
        generation++
        progressResource = progressId
        operation.start({
            try {
                task()
            } finally {
                readStatus()
            }
        }) { failure ->
            if (failure != null) {
                prompt = null
                message = error(failure)
            }
            progressResource = 0
            if (connection?.screen != ConnectionState.Screen.CONNECTED) endpointChanges = 0
            changed()
            if (checkAfter && observer != null) checkEndpoints(false)
        }
        changed()
    }

    fun refresh() = run(R.string.progress_checking, true) {}

    fun prepare(server: String, code: String) =
        run(R.string.progress_checking) {
            controller.nativeIO.requireKsyncAvailable()
            if (ConnectionState(controller.status()).screen != ConnectionState.Screen.SETUP)
                throw UserError(R.string.error_connection_changed)
            val base = SafeURL.base(server)
            val secret = code.trim()
            if (secret.isEmpty()) throw UserError(R.string.error_enter_code)
            controller.state.write(
                "pending-setup",
                JSONObject().put("stage", "draft").put("server", base).put("code", secret),
            )
            prompt =
                Prompt(
                    if (Environment.isExternalStorageManager()) "connect" else "permission",
                    base,
                )
            message = ""
        }

    fun continueSetup() =
        run(R.string.progress_checking) {
            val current = ConnectionState(controller.status())
            if (
                current.screen !in
                    listOf(ConnectionState.Screen.SETUP, ConnectionState.Screen.ENROLLED)
            )
                throw UserError(R.string.error_connection_changed)
            val pending = controller.state.read("pending-setup")
            val target =
                if (current.screen == ConnectionState.Screen.ENROLLED) current.server
                else pending?.optString("server") ?: ""
            if (target.isEmpty()) throw UserError(R.string.error_enter_code)
            prompt =
                Prompt(
                    if (Environment.isExternalStorageManager()) "connect" else "permission",
                    target,
                )
        }

    fun permissionReturned() =
        run(R.string.progress_checking, true) {
            if (!Environment.isExternalStorageManager()) return@run
            val current = ConnectionState(controller.status())
            val pending = controller.state.read("pending-setup")
            if (current.screen == ConnectionState.Screen.ENROLLED)
                prompt = Prompt("connect", current.server)
            else if (
                current.screen == ConnectionState.Screen.SETUP &&
                    pending?.optString("stage") == "draft"
            )
                prompt = Prompt("connect", pending.getString("server"))
        }

    fun cancelSetup() =
        run(R.string.progress_checking) {
            controller.state.delete("pending-setup")
            message = ""
        }

    fun changeSetup() =
        run(R.string.progress_checking) {
            val current = ConnectionState(controller.status())
            if (
                current.screen !in
                    listOf(ConnectionState.Screen.SETUP, ConnectionState.Screen.ENROLLED)
            )
                throw UserError(R.string.error_connection_changed)
            controller.state.delete("pending-setup")
            controller.state.delete("profile")
            message = ""
        }

    fun connect() =
        run(R.string.progress_enrolling, true) {
            if (!Environment.isExternalStorageManager())
                throw UserError(R.string.error_grant_all_files_access_before_configuring_or)
            try {
                setupFlow().confirm()
            } catch (_: SetupFlow.FreshCodeRequired) {
                throw UserError(R.string.error_fresh_code)
            }
            message = text(R.string.connection_complete)
        }

    private fun setupFlow(): SetupFlow =
        SetupFlow(
            object : SetupFlow.Store {
                override fun pending() = controller.state.read("pending-setup")

                override fun save(pending: JSONObject) =
                    controller.state.write("pending-setup", pending)

                override fun clear() = controller.state.delete("pending-setup")
            },
            object : SetupFlow.Connection {
                override fun enrolled() =
                    ConnectionState(controller.status()).screen == ConnectionState.Screen.ENROLLED

                override fun enroll(server: String, code: String) {
                    controller.enroll(server, code)
                }

                override fun connect() = controller.connect()
            },
        )

    fun resumeConnection() =
        run(R.string.progress_connecting, true) {
            controller.resumeConnection()
            message = text(R.string.connection_complete)
        }

    fun enableSync() =
        run(R.string.progress_enabling) {
            controller.enableSync()
            message = text(R.string.sync_enabled)
        }

    fun restore() =
        run(R.string.progress_restoring) {
            controller.restore()
            controller.state.delete("pending-setup")
            message = text(R.string.restore_complete)
        }

    fun checkEndpoints(manual: Boolean) {
        val current = connection ?: return
        if (
            busy() ||
                checking ||
                current.screen != ConnectionState.Screen.CONNECTED ||
                !current.hasAccess
        )
            return
        val now = SystemClock.elapsedRealtime()
        if (!manual && lastCheck >= 0 && now - lastCheck < ENDPOINT_CHECK_INTERVAL_MS) return
        lastCheck = now
        checking = true
        endpointError = ""
        val version = generation
        changed()
        checks.execute {
            var count = 0
            var failure = ""
            try {
                count = controller.endpointPlan().getJSONArray("changes").length()
            } catch (error: Exception) {
                failure = error(error)
            }
            val result = count
            val problem = failure
            main.post {
                checking = false
                if (
                    version == generation &&
                        !busy() &&
                        connection?.screen == ConnectionState.Screen.CONNECTED
                ) {
                    endpointChanges = result
                    endpointError = problem
                    if (manual)
                        message =
                            if (problem.isEmpty())
                                text(
                                    if (result == 0) R.string.endpoints_current
                                    else R.string.update_available
                                )
                            else problem
                }
                if (version != generation) lastCheck = -1
                changed()
            }
        }
    }

    fun updateEndpoints() =
        run(R.string.progress_updating) {
            message = controller.applyEndpointUpdate()
            endpointChanges = 0
        }

    fun recoveryStatus() =
        run(R.string.progress_checking_server) {
            message =
                controller
                    .recoveryStatus()
                    .optString("summary", text(R.string.recovery_status_default))
        }

    fun listAudio() =
        run(R.string.progress_checking_server) {
            val items = controller.audioRecoveries()
            if (items.length() == 0) message = text(R.string.audio_none)
            else prompt = Prompt("audio-list", items)
        }

    fun inspectAudio(id: String) =
        run(R.string.progress_checking) {
            val repair = controller.audioPacket(id)
            when (controller.inspectAudio(repair)) {
                "repairable" -> prompt = Prompt("audio-repair", repair)
                "healthy" -> message = text(R.string.audio_healthy)
                "missing" -> message = text(R.string.audio_missing)
                else -> message = text(R.string.audio_mismatch)
            }
        }

    fun repairAudio(repair: AudioCacheRepair) =
        run(R.string.status_working) {
            controller.repairAudio(repair)
            message = text(R.string.audio_recovered)
        }

    private fun error(error: Exception): String {
        var cause: Throwable? = error
        repeat(32) {
            val current = cause ?: return text(R.string.operation_failed)
            if (current is UserError) return current.text(context)
            cause = current.cause
        }
        return text(R.string.operation_failed)
    }

    companion object {
        private const val ENDPOINT_CHECK_INTERVAL_MS = 15 * 60 * 1000L

        @SuppressLint("StaticFieldLeak") private var instance: ShioriCoordinator? = null

        @JvmStatic
        @Synchronized
        fun get(context: Context): ShioriCoordinator =
            instance ?: ShioriCoordinator(context.applicationContext).also { instance = it }
    }
}
