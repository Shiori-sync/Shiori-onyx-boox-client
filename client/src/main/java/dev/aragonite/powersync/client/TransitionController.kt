package dev.aragonite.powersync.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Durable journals bracket native writes. A failed operation always retains its baseline. */
class TransitionController(context: Context) {
    val context: Context = context.applicationContext
    @JvmField val nativeIO = NativeBridge(this.context)
    @JvmField val state = PrivateState(this.context)

    fun interface Progress {
        fun update(message: Int)
    }

    var progress = Progress {}

    private fun marker() = File(Environment.getExternalStorageDirectory(), "onyx_test_server")

    private fun storage() {
        if (!Environment.isExternalStorageManager())
            throw UserError(R.string.error_grant_all_files_access_before_configuring_or)
    }

    private fun phase(journal: JSONObject, phase: String) {
        progress.update(
            when (phase) {
                "prepared" -> R.string.progress_saving
                "pause_requested",
                "paused" -> R.string.progress_pausing
                "login_requested",
                "authenticated" -> R.string.progress_signing_in
                "enable_requested" -> R.string.progress_enabling
                "restore_requested" -> R.string.progress_restoring
                else -> R.string.progress_connecting
            }
        )
        journal.put("phase", phase).put("updatedAt", System.currentTimeMillis())
        state.write("transition", journal)
    }

    fun localStatus(): JSONObject {
        val journal = state.read("transition")
        val profile = state.read("profile")
        val update = state.read("endpoint-update")
        val phase = journal?.optString("phase") ?: "not_configured"
        return JSONObject()
            .put("phase", phase)
            .put("hasBaseline", state.read("baseline") != null)
            .put("allFilesAccess", Environment.isExternalStorageManager())
            .put("applicationVerified", false)
            .put("serverURL", profile?.optString("serverURL") ?: "")
            .put(
                "enrollmentReady",
                profile != null && (profile.optBoolean("enrollmentReady") || journal == null),
            )
            .put(
                "endpointInterrupted",
                update != null &&
                    update.optString("phase") !in setOf("applied", "rolled_back") &&
                    phase != "restored",
            )
    }

    private fun installedEntryMatches(installed: JSONObject?, actual: JSONObject?): Boolean =
        NativeEndpointSchema.installedEntryMatches(
            installed,
            actual,
            state.read("profile")?.optJSONObject("cluster"),
            state.read("baseline")?.optJSONObject("cluster"),
        )

    fun status(): JSONObject {
        val status = localStatus()
        nativeIO.requireKsyncAvailable()
        val cluster = nativeIO.read(NativeBridge.CLUSTER)
        val account = nativeIO.account()
        val profile = state.read("profile")
        val installed = state.read("installed-cluster")
        val selected = cluster.getString("curClusterId")
        val entry = cluster.getJSONObject("clusterMap").optJSONObject(selected)
        return status
            .put("available", true)
            .put("clusterId", selected)
            .put("accountLoggedIn", account.optBoolean("loggedIn"))
            .put("flags", nativeIO.flags())
            .put(
                "identityMatches",
                profile != null &&
                    account.optBoolean("loggedIn") &&
                    profile.getString("nativeUid") == account.optString("uid") &&
                    selected == account.optString("clusterId") &&
                    profile.getJSONObject("cluster").getString("clusterId") == selected,
            )
            .put(
                "enrolledClusterSelected",
                profile != null &&
                    profile.getJSONObject("cluster").getString("clusterId") == selected,
            )
            .put("configurationMatches", installedEntryMatches(installed, entry))
    }

    fun enableSync() {
        storage()
        if (ConnectionState(status()).screen != ConnectionState.Screen.CONNECTED)
            throw UserError(R.string.error_connection_changed)
        progress.update(R.string.progress_enabling)
        enableFlags()
    }

    private fun pausedFlags() = JSONObject().put("notes", "false").put("reader", "false")

    private fun enableFlags() {
        nativeIO.flags(JSONObject().put("notes", "true").put("reader", "true"))
        val actual = nativeIO.flags()
        if (actual.optString("notes") != "true" || actual.optString("reader") != "true")
            throw UserError(R.string.error_sync_readback)
    }

    fun enroll(base: String, code: String): JSONObject {
        nativeIO.requireKsyncAvailable()
        val journal = state.read("transition")
        if (journal != null && journal.optString("phase") != "restored")
            throw UserError(R.string.error_return_to_the_previous_server_before_enrolling)
        val install = state.installationId()
        val nativeDevice = nativeIO.read(NativeBridge.SYSTEM).optString("deviceUniqueId", "")
        val id =
            nativeDevice.ifEmpty {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }
        if (id.isNullOrEmpty()) throw UserError(R.string.error_native_device_identity_unavailable)
        val originalAccount = nativeIO.account()
        val owner =
            if (originalAccount.optBoolean("loggedIn")) originalAccount.getString("uid") else ""
        val profile =
            EndpointProfile.validate(base, ServerClient.redeem(base, code, install, id, owner))
        if (owner.isNotEmpty() && owner != profile.getString("nativeUid"))
            throw UserError(R.string.error_server_did_not_preserve_your_boox_library)
        profile.put("enrollmentAccount", originalAccount).put("enrollmentReady", true)
        state.write("profile", profile)
        return JSONObject()
            .put("serverURL", base)
            .put("libraryId", profile.getString("libraryId"))
            .put("ready", true)
    }

    fun connect() {
        storage()
        nativeIO.requireKsyncAvailable()
        val profile =
            state.read("profile") ?: throw UserError(R.string.error_enroll_this_device_first)
        val previousJournal = state.read("transition")
        if (previousJournal != null && previousJournal.optString("phase") != "restored")
            throw UserError(R.string.error_a_transition_already_exists_use_return_to)
        val enrollmentAccount =
            profile.optJSONObject("enrollmentAccount")
                ?: throw UserError(R.string.error_enroll_again_to_preserve_your_existing_boox)
        if (NativeBridge.canonical(enrollmentAccount) != NativeBridge.canonical(nativeIO.account()))
            throw UserError(R.string.error_your_boox_account_changed_after_enrollment_enroll)
        val current = nativeIO.read(NativeBridge.CLUSTER)
        val id = profile.getJSONObject("cluster").getString("clusterId")
        if (current.getJSONObject("clusterMap").has(id))
            throw UserError(R.string.error_target_cluster_already_exists_inspect_instead_of)
        val markerBytes = if (marker().exists()) Files.readAllBytes(marker().toPath()) else null
        if (markerBytes != null && markerBytes.size > 1048576)
            throw UserError(R.string.error_native_server_marker_exceeds_bound)
        val markers =
            if (markerBytes == null || markerBytes.isEmpty()) JSONArray()
            else JSONArray(String(markerBytes, Charsets.UTF_8))
        for (i in 0 until markers.length()) if (
            markers.getJSONObject(i).optString("serverId") == id
        )
            throw UserError(R.string.error_marker_already_contains_this_cluster)
        val before = captureBaseline(profile, current, markerBytes)
        state.write("baseline", before)
        val journal =
            JSONObject()
                .put("phase", "prepared")
                .put("clusterId", id)
                .put("nativeUid", profile.getString("nativeUid"))
        state.write("transition", journal)
        profile.put("enrollmentReady", false)
        state.write("profile", profile)
        try {
            phase(journal, "pause_requested")
            nativeIO.flags(pausedFlags())
            phase(journal, "paused")
            val map = nativeIO.read(NativeBridge.CLUSTER)
            map.getJSONObject("clusterMap").put(id, profile.getJSONObject("cluster"))
            updateMapSize(map)
            state.write("installed-cluster", profile.getJSONObject("cluster"))
            phase(journal, "configure_requested")
            nativeIO.write(NativeBridge.CLUSTER, map)
            markers.put(EndpointProfile.marker(profile))
            val installedBytes = markers.toString().toByteArray(Charsets.UTF_8)
            state.write("installed-marker", JSONObject().put("sha256", hash(installedBytes)))
            writeMarker(installedBytes)
            phase(journal, "configured")
            phase(journal, "refresh_requested")
            nativeIO.updateClusters()
            val refreshed =
                nativeIO.read(NativeBridge.CLUSTER).getJSONObject("clusterMap").getJSONObject(id)
            if (
                !NativeEndpointSchema.hostsMatch(
                    profile.getJSONObject("cluster").getJSONObject("clusterHost"),
                    refreshed.getJSONObject("clusterHost"),
                    before.getJSONObject("cluster"),
                )
            )
                throw UserError(R.string.error_native_refresh_replaced_the_enrolled_endpoints)
            state.write("installed-cluster", refreshed)
            phase(journal, "refreshed")
            phase(journal, "switch_requested")
            nativeIO.switchCluster(id)
            phase(journal, "switched")
            finishConnection(before, profile, journal)
        } catch (error: Exception) {
            recordConnectionFailure(journal)
            throw error
        }
    }

    /** Capture preservation data before the first native write. */
    private fun captureBaseline(
        profile: JSONObject,
        current: JSONObject,
        markerBytes: ByteArray?,
    ): JSONObject {
        progress.update(R.string.progress_saving)
        val catalog = nativeIO.catalog()
        val before =
            JSONObject()
                .put("cluster", current)
                .put("flags", nativeIO.flags())
                .put("account", nativeIO.account())
                .put(
                    "accountCache",
                    NativeAccountBackup(nativeIO).capture(profile.getString("nativeUid")),
                )
                .put("fingerprint", nativeIO.fingerprint())
                .put("catalog", catalog)
                .put("catalogFiles", nativeIO.catalogFiles(catalog))
                .put(
                    "marker",
                    markerBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                        ?: JSONObject.NULL,
                )
        return before
    }

    /** Continue from the original baseline; never recapture an interrupted transition. */
    fun resumeConnection() {
        storage()
        nativeIO.requireKsyncAvailable()
        if (!ConnectionRecovery.canResume(status()))
            throw UserError(R.string.error_connection_changed)
        val before =
            state.read("baseline")
                ?: throw UserError(R.string.error_no_saved_previous_configuration)
        val profile =
            state.read("profile") ?: throw UserError(R.string.error_no_saved_previous_configuration)
        val journal =
            state.read("transition")
                ?: throw UserError(R.string.error_no_saved_previous_configuration)
        val id = profile.getJSONObject("cluster").getString("clusterId")
        if (
            id != journal.optString("clusterId") ||
                profile.getString("nativeUid") != journal.optString("nativeUid")
        )
            throw UserError(R.string.error_native_account_mapping_differs)
        val installedMarker = state.read("installed-marker")
        val bytes = if (marker().isFile) Files.readAllBytes(marker().toPath()) else null
        if (
            installedMarker == null ||
                bytes == null ||
                installedMarker.getString("sha256") != hash(bytes)
        )
            throw UserError(R.string.error_native_marker_changed_outside_powersync_preserve_it)
        try {
            nativeIO.flags(pausedFlags())
            finishConnection(before, profile, journal)
        } catch (error: Exception) {
            recordConnectionFailure(journal)
            throw error
        }
    }

    private fun recordConnectionFailure(journal: JSONObject) {
        journal.put("failure", "Transition interrupted; baseline preserved")
        phase(journal, "failed")
    }

    private fun finishConnection(before: JSONObject, profile: JSONObject, journal: JSONObject) {
        phase(journal, "catalog_restore_requested")
        nativeIO.repairMissingCatalog(before.getJSONArray("catalog"))
        if (before.getString("fingerprint") != nativeIO.fingerprint())
            throw UserError(
                R.string.error_native_library_changed_during_transition_synchronization_remains
            )
        var account = nativeIO.account()
        if (!account.optBoolean("loggedIn")) {
            NativeAccountBackup(nativeIO)
                .verifyBeforeLogin(
                    before.getJSONArray("accountCache"),
                    profile.getString("nativeUid"),
                )
            phase(journal, "login_requested")
            nativeIO.login(
                profile.getJSONObject("account").getString("account"),
                profile.getJSONObject("account").getString("code"),
                false,
            )
            account = nativeIO.account()
        }
        if (
            !account.optBoolean("loggedIn") ||
                profile.getString("nativeUid") != account.optString("uid") ||
                profile.getJSONObject("cluster").getString("clusterId") !=
                    account.optString("clusterId")
        )
            throw UserError(R.string.error_native_account_mapping_differs)
        nativeIO.repairMissingCatalog(before.getJSONArray("catalog"))
        if (before.getString("fingerprint") != nativeIO.fingerprint())
            throw UserError(R.string.error_native_library_changed_during_sign_in_synchronization)
        val missing =
            CatalogRecovery.newlyUnavailable(
                before.optJSONObject("catalogFiles"),
                nativeIO.catalogFiles(before.getJSONArray("catalog")),
            )
        journal.put("previouslyLocalBooksUnavailable", missing)
        if (missing > 0)
            Log.w(
                "PowerSyncCompatibility",
                "Previously local book files unavailable: $missing; catalog metadata preserved",
            )
        phase(journal, "authenticated")
        phase(journal, "enable_requested")
        enableFlags()
        phase(journal, "active")
    }

    private fun writeMarker(raw: ByteArray) {
        val file = AtomicFile(marker())
        val output = file.startWrite()
        try {
            output.write(raw)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun updateMapSize(map: JSONObject) {
        if (map.has("clusterMapSize"))
            map.put("clusterMapSize", map.getJSONObject("clusterMap").length())
    }

    private fun hash(bytes: ByteArray): String =
        NativeBridge.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun restore() {
        nativeIO.requireKsyncAvailable()
        storage()
        val before =
            state.read("baseline")
                ?: throw UserError(R.string.error_no_saved_previous_configuration)
        val journal =
            state.read("transition")
                ?: throw UserError(R.string.error_no_saved_previous_configuration)
        if (journal.optString("phase") == "restored") return
        val custom = journal.getString("clusterId")
        val original = before.getJSONObject("cluster").getString("curClusterId")
        val live = nativeIO.read(NativeBridge.CLUSTER)
        val selected = live.getString("curClusterId")
        if (selected != custom && selected != original)
            throw UserError(R.string.error_another_server_is_selected_inspect_before_restoration)
        val installed = state.read("installed-cluster")
        val entry = live.getJSONObject("clusterMap").optJSONObject(custom)
        if (
            entry != null &&
                installed != null &&
                !installedEntryMatches(
                    NativeEndpointSchema.expectedEntry(
                        installed,
                        entry,
                        before.getJSONObject("cluster"),
                    ),
                    entry,
                )
        )
            throw UserError(R.string.error_custom_endpoint_changed_outside_powersync_inspect_before)
        val installedMarker = state.read("installed-marker")
        if (installedMarker != null) {
            val currentMarker = if (marker().isFile) Files.readAllBytes(marker().toPath()) else null
            val previousMarker =
                if (before.isNull("marker")) null
                else Base64.decode(before.getString("marker"), Base64.DEFAULT)
            val originalMarker = java.util.Arrays.equals(currentMarker, previousMarker)
            val ownedMarker =
                currentMarker != null && installedMarker.getString("sha256") == hash(currentMarker)
            if (!originalMarker && !ownedMarker)
                throw UserError(R.string.error_native_marker_changed_outside_powersync_preserve_it)
        }
        phase(journal, "restore_requested")
        nativeIO.flags(pausedFlags())
        if (!live.getJSONObject("clusterMap").has(original)) {
            live
                .getJSONObject("clusterMap")
                .put(
                    original,
                    before.getJSONObject("cluster").getJSONObject("clusterMap").get(original),
                )
            nativeIO.write(NativeBridge.CLUSTER, live)
        }
        if (original != selected) nativeIO.switchCluster(original)
        val map = nativeIO.read(NativeBridge.CLUSTER)
        map.getJSONObject("clusterMap").remove(custom)
        map.put("curClusterId", original)
        updateMapSize(map)
        nativeIO.write(NativeBridge.CLUSTER, map)
        if (installedMarker != null) {
            if (before.isNull("marker")) {
                AtomicFile(marker()).delete()
                if (marker().exists())
                    throw UserError(R.string.error_cannot_remove_powersync_marker)
            } else writeMarker(Base64.decode(before.getString("marker"), Base64.DEFAULT))
        }
        nativeIO.repairMissingCatalog(before.getJSONArray("catalog"))
        val accountRestored =
            if (before.has("accountCache"))
                NativeAccountBackup(nativeIO)
                    .restore(
                        before.getJSONObject("account"),
                        before.getJSONArray("accountCache"),
                        journal.getString("nativeUid"),
                        custom,
                    )
            else
                nativeIO.restoreAccount(
                    before.getJSONObject("account"),
                    journal.getString("nativeUid"),
                )
        val refreshed = nativeIO.read(NativeBridge.CLUSTER)
        refreshed.getJSONObject("clusterMap").remove(custom)
        updateMapSize(refreshed)
        nativeIO.write(NativeBridge.CLUSTER, refreshed)
        nativeIO.repairMissingCatalog(before.getJSONArray("catalog"))
        nativeIO.flags(if (accountRestored) before.getJSONObject("flags") else pausedFlags())
        phase(journal, if (accountRestored) "restored" else "needs_signin")
    }

    /** Updates endpoint values in the selected cluster, retaining account and cluster identity. */
    fun endpointPlan(): JSONObject {
        storage()
        nativeIO.requireKsyncAvailable()
        val profile = state.read("profile")
        val journal = state.read("transition")
        if (profile == null || journal == null || journal.optString("phase") != "active")
            throw UserError(R.string.error_connect_to_your_enrolled_server_before_updating)
        val pending = state.read("endpoint-update")
        if (pending != null && pending.optString("phase") !in setOf("applied", "rolled_back"))
            throw UserError(R.string.error_a_previous_endpoint_update_was_interrupted_inspect)
        val id = profile.getJSONObject("cluster").getString("clusterId")
        val live = nativeIO.read(NativeBridge.CLUSTER)
        if (id != live.getString("curClusterId"))
            throw UserError(R.string.error_another_server_is_selected_inspect_before_updating)
        val account = nativeIO.account()
        if (
            !account.optBoolean("loggedIn") ||
                profile.getString("nativeUid") != account.optString("uid")
        )
            throw UserError(R.string.error_native_account_differs_from_your_enrolled_library)
        val entry = live.getJSONObject("clusterMap").optJSONObject(id)
        if (!installedEntryMatches(state.read("installed-cluster"), entry))
            throw UserError(
                R.string.error_custom_endpoint_changed_outside_powersync_inspect_before_2
            )
        val reply =
            ServerClient.request(
                profile.getString("serverURL") + "/api/v1/boox/device/endpoints",
                "GET",
                null,
                profile.getString("companionToken"),
            )
        if (
            profile.getString("libraryId") != reply.getString("libraryId") ||
                profile.getString("nativeUid") != reply.getString("nativeUid")
        )
            throw UserError(R.string.error_server_library_identity_differs)
        val next = reply.getJSONObject("cluster")
        if (id != next.getString("clusterId") || id != next.getString("lang"))
            throw UserError(R.string.error_server_changed_the_cluster_identity_enroll_again)
        val candidate =
            EndpointProfile.validate(
                profile.getString("serverURL"),
                JSONObject(profile.toString()).put("cluster", next),
            )
        val effectiveNext =
            NativeEndpointSchema.expectedEntry(
                next,
                requireNotNull(entry),
                state.read("baseline")?.optJSONObject("cluster"),
            )
        val changes = JSONArray()
        val updated = EndpointProfile.updatedEntry(entry, effectiveNext, changes)
        return JSONObject()
            .put("clusterId", id)
            .put("changes", changes)
            .put("entry", entry)
            .put("updated", updated)
            .put("profile", candidate)
    }

    fun applyEndpointUpdate(): String {
        val plan = endpointPlan()
        if (plan.getJSONArray("changes").length() == 0)
            return context.getString(R.string.endpoints_current)
        val id = plan.getString("clusterId")
        val profile = plan.getJSONObject("profile")
        val updated = plan.getJSONObject("updated")
        val markerBytes = if (marker().isFile) Files.readAllBytes(marker().toPath()) else null
        if (markerBytes == null || markerBytes.size > 1048576)
            throw UserError(R.string.error_native_server_marker_is_missing_or_too)
        val installedMarker = state.read("installed-marker")
        if (installedMarker == null || installedMarker.getString("sha256") != hash(markerBytes))
            throw UserError(R.string.error_native_marker_changed_outside_powersync_inspect_before)
        val nextMarker = EndpointProfile.replaceMarker(markerBytes, profile)
        val flags = nativeIO.flags()
        val journal =
            JSONObject()
                .put("phase", "prepared")
                .put("clusterId", id)
                .put("changes", plan.getJSONArray("changes"))
                .put("previousEntry", plan.getJSONObject("entry"))
                .put("previousMarker", Base64.encodeToString(markerBytes, Base64.NO_WRAP))
                .put("flags", flags)
        state.write("endpoint-update", journal)
        try {
            endpointPhase(journal, "pause_requested")
            nativeIO.flags(pausedFlags())
            endpointPhase(journal, "configure_requested")
            val map = nativeIO.read(NativeBridge.CLUSTER)
            map.getJSONObject("clusterMap").put(id, updated)
            nativeIO.write(NativeBridge.CLUSTER, map)
            writeMarker(nextMarker)
            endpointPhase(journal, "refresh_requested")
            nativeIO.updateClusters()
            val live = nativeIO.read(NativeBridge.CLUSTER)
            val refreshed = live.getJSONObject("clusterMap").getJSONObject(id)
            if (id != live.getString("curClusterId"))
                throw UserError(R.string.error_native_refresh_selected_another_server)
            if (
                !NativeEndpointSchema.hostsMatch(
                    updated.getJSONObject("clusterHost"),
                    refreshed.getJSONObject("clusterHost"),
                    state.read("baseline")!!.getJSONObject("cluster"),
                ) ||
                    NativeBridge.canonical(refreshed.getJSONObject("ossConfig")) !=
                        NativeBridge.canonical(updated.getJSONObject("ossConfig"))
            )
                throw UserError(R.string.error_native_refresh_did_not_keep_the_updated)
            val account = nativeIO.account()
            if (
                !account.optBoolean("loggedIn") ||
                    profile.getString("nativeUid") != account.optString("uid")
            )
                throw UserError(R.string.error_native_account_changed_during_the_update)
            state.write("installed-cluster", refreshed)
            state.write("installed-marker", JSONObject().put("sha256", hash(nextMarker)))
            state.write("profile", profile)
            nativeIO.flags(flags)
            endpointPhase(journal, "applied")
            for (action in
                listOf(
                    "SERVER_INFO_CHANGED_ACTION",
                    "ONYX_MATERIAL_HOST_BASEURL_CHANGED_ACTION",
                )) context.sendBroadcast(Intent(action))
        } catch (error: Exception) {
            rollbackEndpointUpdate(id, plan.getJSONObject("entry"), markerBytes, flags, journal)
            throw error
        }
        val count = plan.getJSONArray("changes").length()
        return context.resources.getQuantityString(R.plurals.endpoints_updated, count, count)
    }

    private fun rollbackEndpointUpdate(
        id: String,
        previousEntry: JSONObject,
        markerBytes: ByteArray,
        flags: JSONObject,
        journal: JSONObject,
    ) {
        try {
            val map = nativeIO.read(NativeBridge.CLUSTER)
            map.getJSONObject("clusterMap").put(id, previousEntry)
            nativeIO.write(NativeBridge.CLUSTER, map)
            writeMarker(markerBytes)
            try {
                nativeIO.updateClusters()
            } catch (_: Exception) {
                journal.put("rollbackRefresh", "unavailable")
            }
            nativeIO.flags(flags)
            journal.put("failure", "Update failed; previous endpoints restored")
            endpointPhase(journal, "rolled_back")
        } catch (_: Exception) {
            journal.put(
                "failure",
                "Update and rollback both failed; synchronization remains paused",
            )
            endpointPhase(journal, "failed")
        }
    }

    private fun endpointPhase(journal: JSONObject, phase: String) {
        journal.put("phase", phase).put("updatedAt", System.currentTimeMillis())
        state.write("endpoint-update", journal)
    }

    fun recoveryStatus(): JSONObject {
        val profile = state.read("profile") ?: throw UserError(R.string.error_enroll_a_server_first)
        return ServerClient.request(
            profile.getString("serverURL") + "/api/v1/boox/device/status",
            "GET",
            null,
            profile.getString("companionToken"),
        )
    }

    private fun audioProfile(): JSONObject {
        storage()
        nativeIO.requireKsyncAvailable()
        val profile = state.read("profile")
        val journal = state.read("transition")
        if (profile == null || journal == null || journal.optString("phase") != "active")
            throw UserError(R.string.error_connect_to_your_enrolled_server_before_recovering)
        val account = nativeIO.account()
        if (
            !account.optBoolean("loggedIn") ||
                profile.getString("nativeUid") != account.optString("uid") ||
                profile.getJSONObject("cluster").getString("clusterId") !=
                    nativeIO.read(NativeBridge.CLUSTER).getString("curClusterId")
        )
            throw UserError(R.string.error_native_account_or_selected_server_changed)
        return profile
    }

    fun audioRecoveries(): JSONArray {
        val profile = audioProfile()
        val all = JSONArray()
        var after = ""
        do {
            val reply =
                ServerClient.request(
                    profile.getString("serverURL") +
                        "/api/v1/boox/device/audio-recovery?after=$after",
                    "GET",
                    null,
                    profile.getString("companionToken"),
                )
            if (profile.getString("nativeUid") != reply.getString("nativeUid"))
                throw UserError(R.string.error_audio_library_identity_differs)
            val items = reply.getJSONArray("items")
            for (i in 0 until items.length()) all.put(items.getJSONObject(i))
            val next = reply.getString("next")
            if (next.isNotEmpty()) {
                UUID.fromString(next)
                if (next <= after) throw UserError(R.string.error_invalid_audio_recovery_cursor)
            }
            after = next
            if (all.length() > 1000)
                throw UserError(R.string.error_too_many_audio_repairs_review_this_library)
        } while (after.isNotEmpty())
        return all
    }

    fun audioPacket(id: String): AudioCacheRepair {
        UUID.fromString(id)
        val profile = audioProfile()
        val repair =
            AudioCacheRepair(
                ServerClient.request(
                    profile.getString("serverURL") +
                        "/api/v1/boox/device/audio-recovery?operation=$id",
                    "GET",
                    null,
                    profile.getString("companionToken"),
                    24 * 1024 * 1024,
                ),
                profile.getString("nativeUid"),
            )
        context.contentResolver
            .query(
                Uri.parse("content://com.onyx.android.sdk.note.ContentProvider/NoteModel"),
                arrayOf("uniqueId"),
                "uniqueId=? AND userId=? AND type=1 AND status=1",
                arrayOf(repair.document, repair.uid),
                null,
            )
            .use { cursor ->
                if (cursor == null || cursor.count != 1)
                    throw UserError(R.string.error_the_active_notebook_is_not_available_in)
            }
        return repair
    }

    fun inspectAudio(repair: AudioCacheRepair): String =
        repair.inspect(Environment.getExternalStorageDirectory().toPath())

    fun repairAudio(selected: AudioCacheRepair) {
        val current = audioPacket(selected.operation)
        if (current.sha != selected.sha || current.damagedSHA != selected.damagedSHA)
            throw UserError(R.string.error_server_repair_changed_review_it_again)
        current.apply(
            Environment.getExternalStorageDirectory().toPath(),
            File(context.filesDir, "audio-recovery-backups").toPath(),
        )
    }
}
