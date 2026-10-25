package dev.aragonite.powersync.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Firmware observations for diagnostics, never an admission policy. */
final class NativeCompatibility {
    static final String UNREADABLE = "unreadable";

    private NativeCompatibility() {}

    /** ksync versionCode and versionName are reused across different builds; only the APK hash identifies one. */
    static String describe(String model, int sdk, String firmware, long ksync, String ksyncSha256) {
        return "model=" + field(model) + "; API=" + sdk + "; firmware=" + field(firmware)
            + "; ksync=" + ksync + "; ksyncSha256=" + field(ksyncSha256) + "; attempting native interfaces";
    }

    static String apkSha256(File apk) {
        try (InputStream in = new FileInputStream(apk)) {
            return sha256(in);
        } catch (IOException | SecurityException e) {
            return UNREADABLE;
        }
    }

    static String sha256(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[1 << 20];
        int n;
        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }

    private static String field(String value) {
        return value == null || value.isEmpty() ? "unknown" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
