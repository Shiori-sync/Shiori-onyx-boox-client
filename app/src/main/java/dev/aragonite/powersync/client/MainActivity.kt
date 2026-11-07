package dev.aragonite.powersync.client

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray

/** Compose observes process-owned work; recomposition never starts native operations. */
class MainActivity : ComponentActivity(), ShioriCoordinator.Observer {
    private lateinit var coordinator: ShioriCoordinator
    private var revision by mutableIntStateOf(0)
    private var page by mutableStateOf("home")
    private var awaitingPermission = false
    private var foreground = false
    private var prompt by mutableStateOf<ShioriCoordinator.Prompt?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = ShioriCoordinator.get(this)
        page = savedInstanceState?.getString("page", "home") ?: "home"
        awaitingPermission = savedInstanceState?.getBoolean("permission") ?: false
        setContent {
            // High contrast and a fixed light palette for the BOOX e-ink displays.
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Color.Black,
                        onPrimary = Color.White,
                        background = Color.White,
                        surface = Color.White,
                        onBackground = Color.Black,
                        onSurface = Color.Black,
                    )
            ) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        coordinator.localeChanged(resources.configuration.locales.toLanguageTags())
        if (awaitingPermission) {
            awaitingPermission = false
            coordinator.permissionReturned()
        } else coordinator.refresh()
        coordinator.observe(this)
    }

    override fun onStop() {
        foreground = false
        coordinator.detach(this)
        prompt?.let(coordinator::retainPrompt)
        prompt = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putBoolean("permission", awaitingPermission)
        super.onSaveInstanceState(outState)
    }

    override fun changed() {
        if (!foreground) return
        revision++
        val editing =
            page == "home" &&
                coordinator.connection?.screen == ConnectionState.Screen.SETUP &&
                !coordinator.hasDraft
        if (editing) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!coordinator.busy() && prompt == null) coordinator.takePrompt()?.let(::showPrompt)
    }

    private fun navigate(target: String) {
        page = target
        changed()
    }

    private fun showPrompt(value: ShioriCoordinator.Prompt) {
        if (value.kind == "permission") requestAccess() else prompt = value
    }

    @Composable
    private fun Screen() {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        val state = coordinator.connection
        BackHandler(enabled = page != "home") { navigate("home") }
        key(page) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                )
                if (state == null) Text(stringResource(R.string.progress_checking))
                else {
                    val heading =
                        when (state.screen) {
                            ConnectionState.Screen.SETUP -> R.string.setup_heading
                            ConnectionState.Screen.ENROLLED -> R.string.enrolled_heading
                            ConnectionState.Screen.CONNECTED -> R.string.connected_heading
                            ConnectionState.Screen.ATTENTION -> R.string.attention_heading
                        }
                    Text(
                        if (state.screen == ConnectionState.Screen.SETUP || state.server.isEmpty())
                            stringResource(heading)
                        else
                            stringResource(
                                R.string.heading_server,
                                stringResource(heading),
                                state.server,
                            ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                val message =
                    if (coordinator.busy())
                        stringResource(
                            coordinator.progressResource.takeIf { it != 0 }
                                ?: R.string.status_working
                        )
                    else coordinator.message
                if (message.isNotEmpty()) SelectionContainer { Text(message) }
                if (state != null) {
                    if (page != "home") Action(R.string.back_home) { navigate("home") }
                    when (page) {
                        "manage" -> Manage(state)
                        "troubleshoot" -> Troubleshoot(state)
                        "details" -> Details(state)
                        else -> Home(state)
                    }
                }
            }
        }
        prompt?.let { Confirmation(it) }
    }

    @Composable
    private fun Copy(resource: Int) {
        Text(stringResource(resource))
    }

    @Composable
    private fun Action(resource: Int, action: () -> Unit) {
        OutlinedButton(
            onClick = { if (!coordinator.busy()) action() },
            enabled = !coordinator.busy(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(resource))
        }
    }

    @Composable
    private fun Home(state: ConnectionState) {
        when (state.screen) {
            ConnectionState.Screen.SETUP -> {
                Copy(R.string.setup_explanation)
                if (coordinator.hasDraft) {
                    Text(coordinator.pendingServer)
                    Action(R.string.continue_setup, coordinator::continueSetup)
                    Action(R.string.change_details, coordinator::changeSetup)
                } else SetupInputs()
            }
            ConnectionState.Screen.ENROLLED -> {
                Copy(R.string.enrolled_explanation)
                Action(R.string.continue_setup, coordinator::continueSetup)
                Action(R.string.change_details, coordinator::changeSetup)
            }
            ConnectionState.Screen.CONNECTED -> {
                Text(
                    stringResource(
                        R.string.sync_summary,
                        flag(state, "notes"),
                        flag(state, "reader"),
                    )
                )
                Copy(R.string.sync_completion_note)
                if (state.canEnable()) Action(R.string.enable_sync, coordinator::enableSync)
                if (!state.hasAccess) Action(R.string.grant_access, ::requestAccess)
                Action(R.string.open_alexandria) { openServer(state.server) }
                if (coordinator.endpointChanges > 0)
                    Action(R.string.update_available) {
                        showPrompt(ShioriCoordinator.Prompt("endpoints", null))
                    }
                Action(R.string.manage_connection) { navigate("manage") }
            }
            ConnectionState.Screen.ATTENTION -> {
                Copy(
                    if (state.snapshot.optBoolean("endpointInterrupted"))
                        R.string.endpoint_interrupted
                    else R.string.attention_explanation
                )
                if (coordinator.statusError.isNotEmpty()) Text(coordinator.statusError)
                if (!state.hasAccess) Action(R.string.grant_access, ::requestAccess)
                if (state.canResume) {
                    Copy(R.string.resume_connection_explanation)
                    Action(R.string.resume_connection, coordinator::resumeConnection)
                }
                if (state.canRestore) RestoreAction()
                Action(R.string.button_refresh_status, coordinator::refresh)
            }
        }
        Action(R.string.troubleshooting) { navigate("troubleshoot") }
    }

    @Composable
    private fun SetupInputs() {
        var server by rememberSaveable {
            mutableStateOf(coordinator.draftServer.ifEmpty { coordinator.pendingServer })
        }
        // Registration codes deliberately never enter saved instance state.
        var code by remember { mutableStateOf(coordinator.draftCode) }
        if (coordinator.needsFreshCode) Copy(R.string.error_fresh_code)
        OutlinedTextField(
            value = server,
            onValueChange = {
                server = it
                coordinator.draftServer = it
            },
            label = { Copy(R.string.server_label) },
            placeholder = { Copy(R.string.hint_server) },
            singleLine = true,
            enabled = !coordinator.busy(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it
                coordinator.draftCode = it
            },
            label = { Copy(R.string.code_label) },
            placeholder = { Copy(R.string.hint_code) },
            singleLine = true,
            enabled = !coordinator.busy(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Action(R.string.connect_alexandria) {
            val secret = code
            code = ""
            coordinator.draftCode = ""
            coordinator.prepare(server, secret)
        }
    }

    @Composable
    private fun RestoreAction() {
        Action(R.string.button_return) { showPrompt(ShioriCoordinator.Prompt("restore", null)) }
    }

    @Composable
    private fun Manage(state: ConnectionState) {
        Copy(R.string.manage_explanation)
        if (!state.hasAccess) Action(R.string.grant_access, ::requestAccess)
        if (state.canRestore) RestoreAction()
        Action(R.string.technical_details) { navigate("details") }
    }

    @Composable
    private fun Troubleshoot(state: ConnectionState) {
        if (!state.hasAccess) Action(R.string.grant_access, ::requestAccess)
        Action(R.string.button_refresh_status, coordinator::refresh)
        if (state.screen == ConnectionState.Screen.CONNECTED) {
            if (state.hasAccess) {
                Action(R.string.check_updates) { coordinator.checkEndpoints(true) }
                if (coordinator.checking) Copy(R.string.progress_checking_server)
                if (coordinator.endpointError.isNotEmpty()) Text(coordinator.endpointError)
                if (coordinator.endpointChanges > 0)
                    Action(R.string.update_available) {
                        showPrompt(ShioriCoordinator.Prompt("endpoints", null))
                    }
                Action(R.string.button_recover_audio, coordinator::listAudio)
            }
            Action(R.string.button_recovery_status, coordinator::recoveryStatus)
        }
        Action(R.string.button_open_settings) {
            startActivity(
                packageManager.getLaunchIntentForPackage("com.onyx")
                    ?: Intent(Settings.ACTION_SETTINGS)
            )
        }
        Action(R.string.technical_details) { navigate("details") }
        if (state.canRestore) RestoreAction()
    }

    private fun flag(state: ConnectionState, key: String): String =
        getString(
            when (state.snapshot.optJSONObject("flags")?.optString(key)) {
                "true" -> R.string.sync_on
                "false" -> R.string.sync_paused
                else -> R.string.state_unknown
            }
        )

    @Composable
    private fun Details(state: ConnectionState) {
        val s = state.snapshot
        SelectionContainer {
            Text(
                stringResource(
                    R.string.status_description,
                    s.optString("clusterId", getString(R.string.state_unknown)),
                    getString(
                        if (s.optBoolean("accountLoggedIn")) R.string.account_signed_in
                        else R.string.account_signed_out
                    ),
                    flag(state, "notes"),
                    flag(state, "reader"),
                    s.optString("phase"),
                    getString(if (s.optBoolean("hasBaseline")) R.string.yes else R.string.no),
                )
            )
        }
        if (coordinator.statusError.isNotEmpty()) Text(coordinator.statusError)
        if (coordinator.endpointError.isNotEmpty()) Text(coordinator.endpointError)
    }

    private fun requestAccess() {
        awaitingPermission = true
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun openServer(server: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SafeURL.base(server))))
        } catch (_: Exception) {
            coordinator.showMessage(getString(R.string.open_browser_failed))
        }
    }

    private fun cancelPrompt(value: ShioriCoordinator.Prompt) {
        prompt = null
        if (value.kind == "connect") coordinator.cancelSetup()
    }

    @Composable
    private fun Confirmation(value: ShioriCoordinator.Prompt) {
        val title =
            when (value.kind) {
                "connect" -> R.string.dialog_connect_title
                "restore" -> R.string.dialog_return_title
                "endpoints" -> R.string.update_available
                "audio-list" -> R.string.dialog_audio_choose_title
                "audio-repair" -> R.string.dialog_audio_recover_title
                else -> return
            }
        val message =
            when (value.kind) {
                "connect" -> getString(R.string.confirm_connection, value.data)
                "restore" -> getString(R.string.dialog_return_message)
                "endpoints" -> getString(R.string.update_confirmation)
                "audio-repair" -> getString(R.string.dialog_audio_recover_message)
                else -> ""
            }
        val confirm =
            when (value.kind) {
                "connect" -> R.string.action_connect
                "restore" -> R.string.action_restore
                "endpoints" -> R.string.action_update
                else -> R.string.action_recover
            }
        AlertDialog(
            onDismissRequest = { cancelPrompt(value) },
            title = { Copy(title) },
            text = {
                if (value.kind == "audio-list")
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        val items = value.data as JSONArray
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            TextButton(
                                onClick = {
                                    prompt = null
                                    coordinator.inspectAudio(item.optString("operationId"))
                                }
                            ) {
                                Text(
                                    stringResource(
                                        R.string.audio_label,
                                        item.optString("title"),
                                        item.optString("fileName"),
                                    )
                                )
                            }
                        }
                    }
                else Text(message)
            },
            confirmButton = {
                if (value.kind != "audio-list")
                    TextButton(
                        enabled = !coordinator.busy(),
                        onClick = {
                            prompt = null
                            when (value.kind) {
                                "connect" -> coordinator.connect()
                                "restore" -> coordinator.restore()
                                "endpoints" -> coordinator.updateEndpoints()
                                "audio-repair" ->
                                    coordinator.repairAudio(value.data as AudioCacheRepair)
                            }
                        },
                    ) {
                        Copy(confirm)
                    }
            },
            dismissButton = {
                TextButton(onClick = { cancelPrompt(value) }) { Copy(R.string.action_cancel) }
            },
        )
    }
}
