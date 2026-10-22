package dev.aragonite.powersync.client;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;

/** Older native ClusterHost has eight fields and cannot retain the template-hub field. */
final class NativeEndpointSchema {
    static final String MATERIAL = "onyxMaterialHostBaseUrl";
    private static final String[] REQUIRED = {"onyxHostBaseUrl", "onyxCloudDataHostBaseUrl",
        "onyxLogHostBaseUrl", "onyxContentHostBaseUrl", "onyxSend2BooxHostBaseUrl", "shopBookUrl",
        "onyxCouchbaseWSSHost", "onyxMessageReplicator"};

    // Use the saved pre-transition native schema, never a model/version allowlist or
    // the just-refreshed custom entry as proof that omission is supported.
    static boolean lacksMaterialHost(JSONObject baselineCluster) {
        JSONObject entries = baselineCluster == null ? null : baselineCluster.optJSONObject("clusterMap");
        if (entries == null || entries.length() == 0) return false;
        Iterator<String> keys = entries.keys();
        while (keys.hasNext()) {
            JSONObject entry = entries.optJSONObject(keys.next());
            JSONObject hosts = entry == null ? null : entry.optJSONObject("clusterHost");
            if (hosts == null || hosts.has(MATERIAL)) return false;
            for (String key : REQUIRED) if (!(hosts.opt(key) instanceof String)) return false;
        }
        return true;
    }

    static JSONObject expectedHosts(JSONObject expected, JSONObject actual, JSONObject baselineCluster) throws JSONException {
        JSONObject copy = new JSONObject(expected.toString());
        if (!actual.has(MATERIAL) && lacksMaterialHost(baselineCluster)) copy.remove(MATERIAL);
        return copy;
    }

    static boolean hostsMatch(JSONObject expected, JSONObject actual, JSONObject baselineCluster) throws JSONException {
        JSONObject comparable = expectedHosts(expected, actual, baselineCluster);
        if (comparable.length() != actual.length()) return false;
        Iterator<String> keys = comparable.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!(comparable.opt(key) instanceof String) || !comparable.getString(key).equals(actual.opt(key))) return false;
        }
        return true;
    }

    static JSONObject expectedEntry(JSONObject expected, JSONObject actual, JSONObject baselineCluster) throws JSONException {
        JSONObject copy = new JSONObject(expected.toString());
        copy.put("clusterHost", expectedHosts(expected.getJSONObject("clusterHost"), actual.getJSONObject("clusterHost"), baselineCluster));
        return copy;
    }

    /** A firmware upgrade can restore the enrolled template host from the native marker.
     * Compare without rewriting the saved installation or accepting any other drift. */
    static boolean installedEntryMatches(JSONObject installed, JSONObject actual, JSONObject enrolled,
                                         JSONObject baselineCluster) throws JSONException {
        if (installed == null || actual == null) return false;
        JSONObject expected = new JSONObject(installed.toString());
        JSONObject savedHosts = expected.optJSONObject("clusterHost");
        JSONObject liveHosts = actual.optJSONObject("clusterHost");
        JSONObject enrolledHosts = enrolled == null ? null : enrolled.optJSONObject("clusterHost");
        if (savedHosts != null && liveHosts != null && enrolledHosts != null
                && !savedHosts.has(MATERIAL) && liveHosts.has(MATERIAL)
                && lacksMaterialHost(baselineCluster)
                && enrolledHosts.opt(MATERIAL) instanceof String
                && enrolledHosts.getString(MATERIAL).equals(liveHosts.opt(MATERIAL))) {
            savedHosts.put(MATERIAL, enrolledHosts.getString(MATERIAL));
        }
        return NativeConfig.canonical(expected).equals(NativeConfig.canonical(actual));
    }
}
