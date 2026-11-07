package ru.feryafox.shiori;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeEndpointSchemaTest {
    private JSONObject entry() throws Exception { return EndpointProfileTest.profile(null,null).getJSONObject("cluster"); }
    private JSONObject legacyEntry() throws Exception {
        JSONObject entry=entry();entry.getJSONObject("clusterHost").remove(NativeEndpointSchema.MATERIAL);return entry;
    }
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
    @Test public void firmwareUpgradeMayAddOnlyTheEnrolledTemplateHost() throws Exception {
        JSONObject installed=legacyEntry();
        assertTrue(NativeEndpointSchema.installedEntryMatches(installed,entry(),entry(),baseline(false)));
        assertFalse(installed.getJSONObject("clusterHost").has(NativeEndpointSchema.MATERIAL));
        assertTrue(NativeEndpointSchema.installedEntryMatches(installed,legacyEntry(),entry(),baseline(false)));
        assertTrue(NativeEndpointSchema.installedEntryMatches(entry(),entry(),null,null));
    }
    @Test public void firmwareUpgradeRequiresLegacyProofAndExactEnrolledHost() throws Exception {
        for(JSONObject base:new JSONObject[]{baseline(true),new JSONObject(),null})
            assertFalse(NativeEndpointSchema.installedEntryMatches(legacyEntry(),entry(),entry(),base));
        assertFalse(NativeEndpointSchema.installedEntryMatches(legacyEntry(),entry(),null,baseline(false)));
        assertFalse(NativeEndpointSchema.installedEntryMatches(legacyEntry(),entry(),legacyEntry(),baseline(false)));
        JSONObject wrong=entry();wrong.getJSONObject("clusterHost").put(NativeEndpointSchema.MATERIAL,"https://wrong.example");
        assertFalse(NativeEndpointSchema.installedEntryMatches(legacyEntry(),wrong,entry(),baseline(false)));
        assertFalse(NativeEndpointSchema.installedEntryMatches(entry(),legacyEntry(),entry(),baseline(false)));
    }
    @Test public void firmwareUpgradeDoesNotHideOtherConfigurationChanges() throws Exception {
        java.util.Iterator<String> keys=hosts().keys();
        while(keys.hasNext()) {
            String key=keys.next();JSONObject changed=entry();changed.getJSONObject("clusterHost").put(key,"https://wrong.example");
            assertFalse(key,NativeEndpointSchema.installedEntryMatches(legacyEntry(),changed,entry(),baseline(false)));
        }
        JSONObject changed=entry();changed.getJSONObject("ossConfig").put("ossNoteEndPoint","https://wrong.example");
        assertFalse(NativeEndpointSchema.installedEntryMatches(legacyEntry(),changed,entry(),baseline(false)));
        for(String key:new String[]{"region","clusterId","name","extra"}) {
            changed=entry().put(key,"changed");
            assertFalse(key,NativeEndpointSchema.installedEntryMatches(legacyEntry(),changed,entry(),baseline(false)));
        }
        changed=entry();changed.getJSONObject("clusterHost").put("extra","unexpected");
        assertFalse(NativeEndpointSchema.installedEntryMatches(legacyEntry(),changed,entry(),baseline(false)));
        assertFalse(NativeEndpointSchema.installedEntryMatches(null,entry(),entry(),baseline(false)));
        assertFalse(NativeEndpointSchema.installedEntryMatches(legacyEntry(),null,entry(),baseline(false)));
    }
}
