package com.ez.zalopatch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class ZaloArtifactIdentity {
    static final String PACKAGE_NAME = "com.zing.zalo";

    final long versionCode;
    final String versionName;
    final long lastUpdateTime;
    final String sourceDir;
    final String baseApkSha256;
    final String signerSha256;
    final List<Split> splits;
    final String lightweightKey;
    final String generation;
    /**
     * True when the installed Zalo package was repackaged by LSPatch, detected by the
     * loader-injected {@code *.lspatch.documents} provider every LSPatch build carries. LSPatch
     * always resigns the APK with its own key, so {@link #signerSha256} never matches the
     * original Zalo signer on a patched install; that resign is what LSPatch does by design, not
     * evidence of a tampered artifact, so callers use this flag to relax the signer check instead
     * of treating every LSPatch install as untrusted.
     */
    final boolean lspatched;

    private ZaloArtifactIdentity(long versionCode, String versionName, long lastUpdateTime,
                                 String sourceDir, String baseApkSha256, String signerSha256,
                                 List<Split> splits, boolean lspatched) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.lastUpdateTime = lastUpdateTime;
        this.sourceDir = sourceDir;
        this.baseApkSha256 = baseApkSha256;
        this.signerSha256 = signerSha256;
        this.splits = Collections.unmodifiableList(new ArrayList<>(splits));
        this.lspatched = lspatched;
        this.lightweightKey = sha256(canonical(false));
        this.generation = baseApkSha256.isEmpty() ? "" : sha256(canonical(true));
    }

    static ZaloArtifactIdentity capture(Context context, boolean hashApks) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES)
                | PackageManager.GET_PROVIDERS;
        PackageInfo info = packageManager.getPackageInfo(PACKAGE_NAME, flags);
        ApplicationInfo applicationInfo = info.applicationInfo;
        if (applicationInfo == null || applicationInfo.sourceDir == null) {
            throw new IllegalStateException("Zalo base APK path unavailable");
        }
        long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
        String baseHash = hashApks ? sha256(new File(applicationInfo.sourceDir)) : "";
        List<Split> splits = splits(applicationInfo, hashApks);
        return new ZaloArtifactIdentity(code, info.versionName == null ? "" : info.versionName,
                info.lastUpdateTime, applicationInfo.sourceDir, baseHash,
                signerDigest(info), splits, isLspatched(info));
    }

    private static boolean isLspatched(PackageInfo info) {
        if (info.providers == null) {
            return false;
        }
        for (ProviderInfo provider : info.providers) {
            if (provider.authority != null && provider.authority.contains("lspatch.documents")) {
                return true;
            }
        }
        return false;
    }

    private static List<Split> splits(ApplicationInfo info, boolean hashApks) throws Exception {
        ArrayList<Split> result = new ArrayList<>();
        String[] names = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? info.splitNames : null;
        String[] paths = info.splitSourceDirs;
        if (paths == null) {
            return result;
        }
        for (int index = 0; index < paths.length; index++) {
            String name = names != null && index < names.length ? names[index] : "split-" + index;
            String hash = hashApks ? sha256(new File(paths[index])) : "";
            result.add(new Split(name, paths[index], hash));
        }
        Collections.sort(result, (left, right) -> left.name.compareTo(right.name));
        return result;
    }

    private static String signerDigest(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) {
            return "";
        }
        ArrayList<String> digests = new ArrayList<>();
        for (Signature signature : signatures) {
            digests.add(sha256(signature.toByteArray()));
        }
        Collections.sort(digests);
        StringBuilder joined = new StringBuilder();
        for (String digest : digests) {
            if (joined.length() > 0) joined.append(',');
            joined.append(digest);
        }
        return joined.toString();
    }

    private String canonical(boolean includeHashes) {
        StringBuilder value = new StringBuilder();
        value.append(PACKAGE_NAME).append('\n')
                .append(versionCode).append('\n')
                .append(lastUpdateTime).append('\n')
                .append(sourceDir).append('\n')
                .append(signerSha256).append('\n');
        if (includeHashes) {
            value.append(baseApkSha256).append('\n');
        }
        for (Split split : splits) {
            value.append(split.name).append('=').append(split.path);
            if (includeHashes) {
                value.append('=').append(split.sha256);
            }
            value.append('\n');
        }
        return value.toString();
    }

    static String sha256(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Artifact hashing cancelled");
                }
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        }
    }

    static String sha256(String value) {
        try {
            return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return result.toString();
    }

    static final class Split {
        final String name;
        final String path;
        final String sha256;

        Split(String name, String path, String sha256) {
            this.name = name;
            this.path = path;
            this.sha256 = sha256;
        }
    }
}
