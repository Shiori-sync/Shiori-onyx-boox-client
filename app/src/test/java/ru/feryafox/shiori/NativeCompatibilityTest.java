package ru.feryafox.shiori;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class NativeCompatibilityTest {
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void go6ReportsActualFirmwareWithoutQualificationGate() {
        assertEquals("model=Go6_2; API=33; firmware=4.2.2-rel_0924; ksync=32198; ksyncSha256=" + ABC_SHA256
                + "; attempting native interfaces",
            NativeCompatibility.describe("Go6_2", 33, "4.2.2-rel_0924", 32198, ABC_SHA256));
    }

    @Test public void unknownDevicesAndFirmwareUpdatesRemainObservations() {
        for (String model : new String[]{null, "", "FutureBOOX", "TabUltraCPro", "Palma2_Pro_C", "Go103_2Lumi", "NoteMiniC"}) {
            for (int sdk : new int[]{33, 34, 35, 36}) {
                for (long ksync : new long[]{32173, 32174, 32198, 99999}) {
                    String report = NativeCompatibility.describe(model, sdk, null, ksync, NativeCompatibility.UNREADABLE);
                    assertTrue(report.contains("API=" + sdk));
                    assertTrue(report.contains("ksync=" + ksync));
                    assertTrue(report.contains("ksyncSha256=unreadable"));
                    assertTrue(report.endsWith("attempting native interfaces"));
                }
            }
        }
    }

    @Test public void sameVersionCodeDifferentBuildsAreDistinguishable() {
        // Go6_2 4.2.2 and NoteMiniC 4.3 both ship ksync 32198 with different code.
        assertNotEquals(NativeCompatibility.describe("X", 36, "f", 32198, "aa"),
            NativeCompatibility.describe("X", 36, "f", 32198, "bb"));
    }

    @Test public void diagnosticStaysOnOneLineWithMissingOrMalformedBuildFields() {
        String report = NativeCompatibility.describe(null, 33, "build\r\nnext", 32198, null);
        assertTrue(report.contains("model=unknown"));
        assertTrue(report.contains("ksyncSha256=unknown"));
        assertFalse(report.contains("\r"));
        assertFalse(report.contains("\n"));
    }

    @Test public void hashesStreamsAndFiles() throws Exception {
        assertEquals(ABC_SHA256, NativeCompatibility.sha256(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII))));
        File apk = temp.newFile("ksync.apk");
        Files.write(apk.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals(ABC_SHA256, NativeCompatibility.apkSha256(apk));
    }

    @Test public void unreadableApkIsReportedNotThrown() {
        assertEquals(NativeCompatibility.UNREADABLE, NativeCompatibility.apkSha256(new File(temp.getRoot(), "missing.apk")));
    }
}
