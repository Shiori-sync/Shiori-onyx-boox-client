package dev.aragonite.powersync.client;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeEndpointSchemaTest {
    private JSONObject hosts() throws Exception { return EndpointProfileTest.profile(null,null).getJSONObject("cluster").getJSONObject("clusterHost"); }
    private JSONObject baseline(boolean material) throws Exception {
        JSONObject host = hosts(); if (!material) host.remove(NativeEndpointSchema.MATERIAL);
        return new JSONObject().put("clusterMap",new JSONObject().put("stock",new JSONObject().put("clusterHost",host)));
    }
    @Test public void legacyRefreshCanOmitOnlyUnsupportedTemplateHost() throws Exception {
        JSONObject expected=hosts(), actual=hosts();actual.remove(NativeEndpointSchema.MATERIAL);
        assertTrue(NativeEndpointSchema.hostsMatch(expected,actual,baseline(false)));
        assertTrue(expected.has(NativeEndpointSchema.MATERIAL));
        assertTrue(NativeEndpointSchema.hostsMatch(expected,expected,baseline(false)));
    }
    @Test public void supportedOrUnknownSchemaNeverAllowsOmission() throws Exception {
        JSONObject actual=hosts();actual.remove(NativeEndpointSchema.MATERIAL);
        for(JSONObject base:new JSONObject[]{baseline(true),new JSONObject(),new JSONObject().put("clusterMap",new JSONObject()),null})
            assertFalse(NativeEndpointSchema.hostsMatch(hosts(),actual,base));
        JSONObject mixed=baseline(false);mixed.getJSONObject("clusterMap").put("new",new JSONObject().put("clusterHost",hosts()));
        assertFalse(NativeEndpointSchema.hostsMatch(hosts(),actual,mixed));
    }
    @Test public void alteredOrMissingSyncHostsAndExtraFieldsRemainRejected() throws Exception {
        java.util.Iterator<String> keys=hosts().keys();
        while(keys.hasNext()) {
            String key=keys.next();JSONObject changed=hosts();changed.put(key,"https://wrong.example");
            assertFalse(key,NativeEndpointSchema.hostsMatch(hosts(),changed,baseline(false)));
            if(!NativeEndpointSchema.MATERIAL.equals(key)) {
                JSONObject missing=hosts();missing.remove(key);missing.remove(NativeEndpointSchema.MATERIAL);
                assertFalse(key,NativeEndpointSchema.hostsMatch(hosts(),missing,baseline(false)));
            }
        }
        assertFalse(NativeEndpointSchema.hostsMatch(hosts(),hosts().put("unknown","https://wrong.example"),baseline(false)));
    }
    @Test public void restoreNormalizationPreservesAllOtherFields() throws Exception {
        JSONObject expected=EndpointProfileTest.profile(null,null).getJSONObject("cluster");
        JSONObject actual=new JSONObject(expected.toString());actual.getJSONObject("clusterHost").remove(NativeEndpointSchema.MATERIAL);
        JSONObject normalized=NativeEndpointSchema.expectedEntry(expected,actual,baseline(false));
        assertEquals(actual.toString(),normalized.toString());
        assertTrue(expected.getJSONObject("clusterHost").has(NativeEndpointSchema.MATERIAL));
        actual.put("region","changed");assertNotEquals(actual.toString(),normalized.toString());
        actual.getJSONObject("ossConfig").put("ossNoteEndPoint","https://wrong.example");assertNotEquals(actual.toString(),normalized.toString());
    }
    @Test public void endpointPlanDoesNotRepeatedlyOfferUnsupportedField() throws Exception {
        JSONObject expected=EndpointProfileTest.profile(null,null).getJSONObject("cluster");
        JSONObject actual=new JSONObject(expected.toString());actual.getJSONObject("clusterHost").remove(NativeEndpointSchema.MATERIAL);
        org.json.JSONArray changes=new org.json.JSONArray();
        EndpointProfile.updatedEntry(actual,NativeEndpointSchema.expectedEntry(expected,actual,baseline(false)),changes);
        assertEquals(0,changes.length());
        assertTrue(EndpointProfile.marker(new JSONObject().put("cluster",expected)).getJSONObject("host").has(NativeEndpointSchema.MATERIAL));
    }
}
