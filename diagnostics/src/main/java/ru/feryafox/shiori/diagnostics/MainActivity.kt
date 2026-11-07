package ru.feryafox.shiori.diagnostics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import org.json.JSONObject

/** Disposable-fixture lab, kept separate from the Shiori client application. */
class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var output by mutableStateOf("Ready. No lab action is running in this window.")
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Color.Black,
                        onPrimary = Color.White,
                        surface = Color.White,
                        background = Color.White,
                    )
            ) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Shiori Diagnostics",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "Native BOOX experiment tools. Fixture buttons operate on disposable notes. Saved reports and server observations provide the test results."
                        )
                        Action("Probe native interfaces") {
                            execute("probe.json") { ProbeRunner(this@MainActivity).run() }
                        }
                        Action("Get recently read books from NeoReader") {
                            execute("reader-recent-books.json") {
                                RecentReaderBooks(this@MainActivity).read()
                            }
                        }
                        Action("Grant All Files Access") {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                )
                            )
                        }
                        Action("Create disposable text fixture") {
                            execute("fixture-create.json") {
                                FixtureRunner(this@MainActivity).create()
                            }
                        }
                        Action("Rename fixture in place") {
                            execute("fixture-rename.json") {
                                FixtureRunner(this@MainActivity).rename()
                            }
                        }
                        Action("Capture fixture metadata") {
                            execute("fixture-capture.json") {
                                FixtureRunner(this@MainActivity).capture()
                            }
                        }
                        Action("Export native fixture archive") {
                            execute("fixture-export.json") {
                                FixtureRunner(this@MainActivity).export()
                            }
                        }
                        Action("Write fixture text resource (close Notes first)") {
                            execute("fixture-write.json") {
                                FixtureRunner(this@MainActivity).writeTextResource()
                            }
                        }
                        Action("Open fixture in BOOX Notes") {
                            openFixture { FixtureRunner(this@MainActivity).open() }
                        }
                        SelectionContainer { Text(output) }
                    }
                }
            }
        }
        handleDebugIntent(intent)
    }

    @Composable
    private fun Action(label: String, action: () -> Unit) {
        OutlinedButton(onClick = action, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(label)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent) {
        val action = intent.getStringExtra("lab_action") ?: return
        if (!BuildConfig.DEBUG) return
        when (action) {
            "reader_recent_books" ->
                execute("reader-recent-books.json") { RecentReaderBooks(this).read() }
            "v2_work_insert" -> execute("v2-work-insert.json") { NativeWorkQueueLab(this).insert() }
            "v2_work_capture" ->
                execute("v2-work-capture.json") { NativeWorkQueueLab(this).capture() }
            "v2_work_remove" -> execute("v2-work-remove.json") { NativeWorkQueueLab(this).remove() }
            "library_inventory" ->
                execute("library-inventory.json") { NativeLibraryLab(this).inventory() }
            "reader_fixture_bookmark_revision" ->
                execute("reader-fixture-bookmark-revision.json") {
                    NativeReaderLab(this).refreshBookmark()
                }
            "reader_fixture_identity_earlier" ->
                execute("reader-fixture-identity-earlier.json") {
                    NativeReaderLab(this).reorderIdentity(false)
                }
            "reader_fixture_identity_restore" ->
                execute("reader-fixture-identity-restore.json") {
                    NativeReaderLab(this).reorderIdentity(true)
                }
            "reader_fixture_pull_full" ->
                execute("reader-fixture-pull-full.json") {
                    NativeReaderLab(this).pullFullUserData()
                }
            "reader_fixture_capture" ->
                execute("reader-fixture-capture.json") { NativeReaderLab(this).capture() }
            "reader_pdf_capture" ->
                execute("reader-pdf-capture.json") { NativeReaderPdfLab(this).capture() }
            "reader_pdf_export" ->
                execute("reader-pdf-export.json") { NativeReaderPdfLab(this).export() }
            "reader_pdf_export_managed" ->
                execute("reader-pdf-export.json") { NativeReaderPdfLab(this).export(true) }
            "reader_pdf_pull_full_source" ->
                execute("reader-pdf-sync.json") { NativeReaderPdfLab(this).pullFullSource() }
            "reader_history_enroll" ->
                execute("reader-history-enroll.json") { NativeHistoryReaderLab(this).enroll() }
            "reader_history_capture" ->
                execute("reader-history-capture.json") { NativeHistoryReaderLab(this).capture() }
            "reader_history_export" ->
                execute("reader-history-export.json") { NativeHistoryReaderLab(this).export() }
            "reader_history_query" ->
                execute("reader-history-query.json") { NativeHistoryReaderLab(this).queryFull() }
            "reader_history_start" ->
                execute("reader-history-start.json") {
                    NativeHistoryReaderLab(this).startLibrary(true)
                }
            "reader_history_pull" ->
                execute("reader-history-pull.json") {
                    NativeHistoryReaderLab(this).syncNative(false, false)
                }
            "reader_history_full" ->
                execute("reader-history-full.json") {
                    NativeHistoryReaderLab(this).pullFullSource()
                }
            "native_restored_wake" ->
                execute("native-restored-wake.json") { NativeCatalogRestoreLab.wake(this) }
            "catalog_restore_original" ->
                execute("catalog-restore-original.json") { NativeCatalogRestoreLab.restore(this) }
            "reader_config_refresh" ->
                execute("reader-config-refresh.json") {
                    NativeFreshReaderPdfLab(this).notifyConfigChange()
                }
            "reader_fresh_capture" ->
                execute("reader-fresh-pdf-capture.json") { NativeFreshReaderPdfLab(this).capture() }
            "reader_fresh_auto_sync" ->
                execute("reader-fresh-auto-sync.json") {
                    NativeFreshReaderPdfLab(this).enableAutoSync()
                }
            "reader_fresh_export" ->
                execute("reader-fresh-pdf-export.json") { NativeFreshReaderPdfLab(this).export() }
            "reader_fresh_push" ->
                execute("reader-fresh-pdf-sync.json") {
                    NativeFreshReaderPdfLab(this).syncNative(false, true)
                }
            "reader_fresh_pull" ->
                execute("reader-fresh-pdf-sync.json") {
                    NativeFreshReaderPdfLab(this).syncNative(false, false)
                }
            "reader_fresh_pull_full" ->
                execute("reader-fresh-pdf-sync.json") {
                    NativeFreshReaderPdfLab(this).pullFullSource()
                }
            "reader_fresh_publish_config" ->
                execute("reader-fresh-config-publication.json") {
                    NativeFreshReaderPdfLab(this).publishConfigConflict()
                }
            "reader_fresh_query" ->
                execute("reader-fresh-pdf-query.json") { NativeFreshReaderPdfLab(this).queryFull() }
            "reader_fresh_query_metadata" ->
                execute("reader-fresh-pdf-query-metadata.json") {
                    NativeFreshReaderPdfLab(this).queryMetadata()
                }
            "reader_fresh_restart" ->
                execute("reader-fresh-pdf-restart.json") {
                    NativeFreshReaderPdfLab(this).restartLibrary()
                }
            "reader_fresh_restart_job" ->
                execute("reader-fresh-restart-job.json") {
                    NativeFreshReaderPdfLab(this).restartOwnedDocJob()
                }
            "reader_fresh_requeue" ->
                execute("reader-fresh-requeue.json") {
                    NativeFreshReaderPdfLab(this).requeueCurrentUserData()
                }
            "reader_fresh_start" ->
                execute("reader-fresh-pdf-start.json") {
                    NativeFreshReaderPdfLab(this).startLibrary(true)
                }
            "reader_pdf_push_peer" ->
                execute("reader-pdf-sync.json") { NativeReaderPdfLab(this).syncNative(true, true) }
            "reader_pdf_pull_source" ->
                execute("reader-pdf-sync.json") {
                    NativeReaderPdfLab(this).syncNative(false, false)
                }
            "reader_pdf_sync_source" ->
                execute("reader-pdf-sync.json") { NativeReaderPdfLab(this).syncNative(false) }
            "reader_pdf_sync_peer" ->
                execute("reader-pdf-sync.json") { NativeReaderPdfLab(this).syncNative(true) }
            "reader_pdf_restart" ->
                execute("reader-pdf-restart.json") { NativeReaderPdfLab(this).restartLibrary() }
            "reader_pdf_query_metadata" ->
                execute("reader-pdf-query-metadata.json") {
                    NativeReaderPdfLab(this).queryMetadata()
                }
            "reader_pdf_query" ->
                execute("reader-pdf-query.json") { NativeReaderPdfLab(this).queryFull() }
            "reader_pdf_stage_peer_metadata" ->
                execute("reader-pdf-stage-peer-metadata.json") {
                    NativeReaderPdfLab(this).stagePeerMetadata()
                }
            "reader_pdf_start" ->
                execute("reader-pdf-start.json") { NativeReaderPdfLab(this).startLibrary(true) }
            "reader_pdf_enroll_peer" ->
                execute("reader-pdf-enroll-peer.json") { NativeReaderPdfLab(this).enrollPeer() }
            "reader_pdf_restore_owned" ->
                execute("reader-pdf-restore-owned.json") { NativeReaderPdfLab(this).restoreOwned() }
            "reader_pdf_publish_metadata" ->
                execute("reader-pdf-publish-metadata.json") {
                    NativeReaderPdfLab(this).publishMetadata()
                }
            "reader_pdf_upload" ->
                execute("reader-pdf-upload.json") { NativeReaderPdfLab(this).transfer(true) }
            "reader_pdf_download" ->
                execute("reader-pdf-download.json") { NativeReaderPdfLab(this).transfer(false) }
            "reader_library_guard" ->
                execute("reader-library-guard.json") { NativeReaderLab(this).libraryGuard() }
            "reader_fixture_publish" ->
                execute("reader-fixture-publish.json") { NativeReaderLab(this).request(false) }
            "reader_fixture_bookmarks" ->
                execute("reader-fixture-bookmarks.json") {
                    NativeReaderLab(this).request("bookmarks")
                }
            "reader_fixture_annotations" ->
                execute("reader-fixture-annotations.json") {
                    NativeReaderLab(this).request("annotations")
                }
            "reader_fixture_cloud_start" ->
                execute("reader-fixture-cloud-start.json") {
                    NativeReaderLab(this).startScopedCloudLibraryReplicator()
                }
            "reader_fixture_cloud_restart" ->
                execute("reader-fixture-cloud-restart.json") {
                    NativeReaderLab(this).startScopedCloudLibraryReplicator(true)
                }
            "reader_fixture_query_cloud_metadata" ->
                execute("reader-fixture-query-cloud-metadata.json") {
                    NativeReaderLab(this).pullFullUserData(true)
                }
            "reader_fixture_cloud_capture" ->
                execute("reader-fixture-cloud-capture.json") {
                    NativeReaderLab(this).captureCloudBook()
                }
            "reader_fixture_conflict_edit" ->
                execute("reader-fixture-conflict-edit.json") {
                    NativeReaderLab(this).editCloudConflict()
                }
            "reader_fixture_conflict_publish" ->
                execute("reader-fixture-conflict-publish.json") {
                    NativeReaderLab(this).publishCloudConflict()
                }
            "reader_fixture_cloud_comment" ->
                execute("reader-fixture-cloud-comment.json") {
                    NativeReaderLab(this).editCloudFixtureComment()
                }
            "reader_fixture_library_delta" ->
                execute("reader-fixture-library-delta.json") {
                    NativeReaderLab(this).syncBookFromLibrary(true)
                }
            "reader_fixture_library_sync" ->
                execute("reader-fixture-library-sync.json") {
                    NativeReaderLab(this).syncBookFromLibrary()
                }
            "reader_fixture_book_prepare" ->
                execute("reader-fixture-book-prepare.json") {
                    NativeReaderLab(this).prepareBookTransfer()
                }
            "reader_fixture_book_upload" ->
                execute("reader-fixture-book-upload.json") {
                    NativeReaderLab(this).transferBook(true)
                }
            "reader_fixture_book_download" ->
                execute("reader-fixture-book-download.json") {
                    NativeReaderLab(this).transferBook(false)
                }
            "reader_fixture_start" ->
                execute("reader-fixture-start.json") { NativeReaderLab(this).request(true) }
            "reader_fixture_restore" ->
                execute("reader-fixture-restore.json") {
                    NativeReaderLab(this).restoreFixtureFlags()
                }
            "library_export_one" ->
                execute("library-export-one.json") { NativeLibraryLab(this).exportOne() }
            "conflict_capture" ->
                execute("conflict-capture.json") { NativeConflictLab(this).capture() }
            "conflict_edit" ->
                execute("conflict-edit.json") { NativeConflictLab(this).change("edit") }
            "conflict_remove" ->
                execute("conflict-remove.json") { NativeConflictLab(this).change("remove") }
            "conflict_restore" ->
                execute("conflict-restore.json") { NativeConflictLab(this).change("restore") }
            "endpoint_status" ->
                execute("endpoint-status.json") { NativeEndpointLab(this).status() }
            "endpoint_provision" ->
                execute("endpoint-provision.json") { NativeEndpointLab(this).provision(false) }
            "endpoint_provision_marker" ->
                execute("endpoint-provision-marker.json") {
                    NativeEndpointLab(this).provision(true)
                }
            "endpoint_reassert" ->
                execute("endpoint-reassert.json") { NativeEndpointLab(this).reassert() }
            "endpoint_enable" ->
                execute("endpoint-enable.json") { NativeEndpointLab(this).flags(false) }
            "endpoint_flags_restore" ->
                execute("endpoint-flags-restore.json") { NativeEndpointLab(this).flags(true) }
            "endpoint_restore" ->
                execute("endpoint-restore.json") { NativeEndpointLab(this).restore() }
            "cluster_uid_probe" ->
                execute("cluster-uid-probe.json") { NativeSystemConfigProbe().run() }
            "editor_switch_foreground" ->
                execute("editor-switch-foreground.json") {
                    NativeEditorCloseLab(this).switchForeground()
                }
            "editor_switch_arm" ->
                execute("editor-switch-arm.json") { NativeEditorCloseLab(this).switchArm(true) }
            "editor_same_arm" ->
                execute("editor-same-arm.json") { NativeEditorCloseLab(this).switchArm(false) }
            "editor_quit_arm" ->
                execute("editor-quit-arm.json") { NativeEditorCloseLab(this).arm(true) }
            "editor_noop_arm" ->
                execute("editor-noop-arm.json") { NativeEditorCloseLab(this).arm(false) }
            "gateway_selected_enable" ->
                execute("gateway-selected-enable.json") { NativeGatewayLab(this).selectedEnable() }
            "richtext_recovery_apply" ->
                execute("richtext-recovery-apply.json") { NativeRichTextRecoveryLab(this).apply() }
            "gateway_tree_query" ->
                execute("gateway-tree-query.json") { NativeGatewayLab(this).querySelectedTree() }
            "gateway_restart_tree" ->
                execute("gateway-restart-tree.json") { NativeGatewayLab(this).restartTree() }
            "gateway_selected_capture" ->
                execute("gateway-selected-capture.json") {
                    NativeGatewayLab(this).selectedCapture()
                }
            "gateway_shared_open" ->
                execute("gateway-shared-open.json") {
                    NativeGatewayLab(this).selectedOpen().let { open ->
                        runOnUiThread { startActivity(open) }
                        JSONObject().put("requested", true).put("disposableFixtureOnly", true)
                    }
                }
            "gateway_shared_pull" ->
                execute("gateway-shared-pull.json") { NativeGatewayLab(this).syncShared(true) }
            "gateway_shared_export" ->
                execute("gateway-shared-export.json") { NativeGatewayLab(this).exportShared() }
            "gateway_shared_sync" ->
                execute("gateway-shared-sync.json") { NativeGatewayLab(this).syncShared() }
            "gateway_shared_capture" ->
                execute("gateway-shared-capture.json") { NativeGatewayLab(this).sharedCapture() }
            "gateway_shared_rename" ->
                execute("gateway-shared-rename.json") { NativeGatewayLab(this).renameShared() }
            "gateway_preservation" ->
                execute("gateway-preservation.json") { NativeGatewayLab(this).preservation() }
            "account_baseline" ->
                execute("account-baseline-result.json") { NativeAccountLab(this).baseline() }
            "account_lab_info" ->
                execute("account-lab-info.json") { NativeAccountLab(this).probeLabAccountInfo() }
            "account_restore" ->
                execute("account-restore.json") { NativeAccountLab(this).restoreOriginal() }
            "gateway_create" -> execute("gateway-create.json") { NativeGatewayLab(this).create() }
            "gateway_capture" ->
                execute("gateway-capture.json") { NativeGatewayLab(this).capture() }
            "gateway_enable" -> execute("gateway-enable.json") { NativeGatewayLab(this).enable() }
            "gateway_disable" ->
                execute("gateway-disable.json") { NativeGatewayLab(this).disable() }
            "gateway_rename" -> execute("gateway-rename.json") { NativeGatewayLab(this).rename() }
            "account_login_lab" -> execute("account-login.json") { NativeAccountLab(this).login() }
            "cluster_snapshot" ->
                execute("cluster-snapshot.json") { NativeClusterLab(this).snapshot() }
            "cluster_baseline" ->
                execute("cluster-baseline-result.json") { NativeClusterLab(this).baseline() }
            "cluster_update" -> execute("cluster-update.json") { NativeClusterLab(this).update() }
            "cluster_switch" ->
                execute("cluster-switch.json") { NativeClusterLab(this).change(false) }
            "cluster_restore" ->
                execute("cluster-restore.json") { NativeClusterLab(this).change(true) }
            "probe" -> execute("probe.json") { ProbeRunner(this).run() }
            "create_fixture" -> execute("fixture-create.json") { FixtureRunner(this).create() }
            "rename_fixture" -> execute("fixture-rename.json") { FixtureRunner(this).rename() }
            "capture_fixture" -> execute("fixture-capture.json") { FixtureRunner(this).capture() }
            "export_fixture" -> execute("fixture-export.json") { FixtureRunner(this).export() }
            "disable_fixture" ->
                execute("fixture-cloud.json") { FixtureRunner(this).disableNativeCloud() }
            "disable_ink" ->
                execute("ink-cloud.json") { FixtureRunner(this, "inkFixture").disableNativeCloud() }
            "write_fixture" ->
                execute("fixture-write.json") { FixtureRunner(this).writeTextResource() }
            "image_ink" -> openFixture { FixtureRunner(this, "inkFixture").insertImage() }
            "create_rich" -> openFixture { FixtureRunner(this, "richFixture").createInkIntent() }
            "capture_rich" ->
                execute("rich-metadata.json") { FixtureRunner(this, "richFixture").capture() }
            "export_rich" ->
                execute("rich-export.json") { FixtureRunner(this, "richFixture").export() }
            "stage_rich" ->
                execute("rich-stage.json") { FixtureRunner(this, "richFixture").stageInkPayload() }
            "image_rich" -> openFixture { FixtureRunner(this, "richFixture").insertImage() }
            "open_rich" -> openFixture { FixtureRunner(this, "richFixture").open() }
            "create_ink" -> openFixture { FixtureRunner(this, "inkFixture").createInkIntent() }
            "capture_ink" ->
                execute("ink-metadata.json") { FixtureRunner(this, "inkFixture").capture() }
            "export_ink" ->
                execute("ink-export.json") { FixtureRunner(this, "inkFixture").export() }
            "stage_ink_interrupt" ->
                execute("ink-stage.json") {
                    FixtureRunner(this, "inkFixture").stageInkPayload(true)
                }
            "stage_ink" ->
                execute("ink-stage.json") { FixtureRunner(this, "inkFixture").stageInkPayload() }
            "open_ink" -> openFixture { FixtureRunner(this, "inkFixture").open() }
            "open_fixture" -> openFixture { FixtureRunner(this).open() }
        }
    }

    private fun execute(report: String, task: () -> JSONObject) {
        busy = true
        show("Running…")
        worker.execute {
            try {
                val result = task()
                ReportFiles.write(this, report, result)
                show(
                    if (
                        result.optBoolean("requested") &&
                            result.has("completed") &&
                            !result.optBoolean("completed")
                    )
                        "Request sent to BOOX sync. This command returns before syncing finishes. Completion is checked separately in device reports and server traffic."
                    else result.toString(2)
                )
            } catch (error: Exception) {
                try {
                    ReportFiles.write(this, report, JSONObject().put("error", error.toString()))
                } catch (_: Exception) {}
                show(error.toString())
            } finally {
                runOnUiThread { busy = false }
            }
        }
    }

    private fun openFixture(task: () -> Intent) {
        busy = true
        worker.execute {
            try {
                val intent = task()
                runOnUiThread { startActivity(intent) }
            } catch (error: Exception) {
                show(error.toString())
            } finally {
                runOnUiThread { busy = false }
            }
        }
    }

    private fun show(text: String) {
        runOnUiThread { if (!isFinishing) output = text }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
