package com.ez.zalopatch;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;

public final class SettingsPropertyMirror {
    private static final String PREFIX = "debug.zp.";
    private static final int BLOB_CHUNK_SIZE = 80;
    private static final int MAX_BLOB_CHUNKS = 128;

    private SettingsPropertyMirror() {
    }

    public static String read(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String value = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, propertyName(key), "");
            return value == null || value.length() == 0 ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean writeBoolean(String key, boolean value) {
        return write(key, String.valueOf(value));
    }

    public static boolean writeInt(String key, int value) {
        return write(key, String.valueOf(value));
    }

    public static String readBlob(String key) {
        try {
            int count = Integer.parseInt(readProperty(propertyName(key) + ".n", "0"));
            if (count <= 0 || count > MAX_BLOB_CHUNKS) {
                return null;
            }
            StringBuilder encoded = new StringBuilder(count * BLOB_CHUNK_SIZE);
            for (int i = 0; i < count; i++) {
                String chunk = readProperty(propertyName(key) + "." + i, "");
                if (chunk.isEmpty()) {
                    return null;
                }
                encoded.append(chunk);
            }
            byte[] decoded = Base64.decode(encoded.toString(),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean writeBlob(String key, String value) {
        try {
            String encoded = Base64.encodeToString(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            int count = Math.max(1, (encoded.length() + BLOB_CHUNK_SIZE - 1) / BLOB_CHUNK_SIZE);
            if (count > MAX_BLOB_CHUNKS) {
                throw new IllegalArgumentException("notification rules too large");
            }
            String base = propertyName(key);
            if (!isSafePropertyName(base + ".n")) {
                return logValidationFailure(key, "name");
            }
            StringBuilder command = new StringBuilder("setprop ")
                    .append(base).append(".n 0");
            for (int i = 0; i < count; i++) {
                int start = i * BLOB_CHUNK_SIZE;
                int end = Math.min(encoded.length(), start + BLOB_CHUNK_SIZE);
                String name = base + "." + i;
                String chunk = encoded.substring(start, end);
                if (!isSafePropertyName(name) || !isSafeValue(chunk)) {
                    return logValidationFailure(key, "name/value");
                }
                command.append(" && setprop ").append(base).append('.').append(i).append(' ')
                        .append(chunk);
            }
            if (!isSafeValue(String.valueOf(count))) {
                return logValidationFailure(key, "value");
            }
            command.append(" && setprop ").append(base).append(".n ").append(count);
            return runRootCommand(key, command.toString());
        } catch (Throwable throwable) {
            Log.i("ZaloPatch", "Property mirror blob write failed key=" + key
                    + " " + throwable.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean write(String key, String value) {
        try {
            String propertyName = propertyName(key);
            if (!isSafePropertyName(propertyName) || !isSafeValue(value)) {
                return logValidationFailure(key, "name/value");
            }
            return runRootCommand(key, "setprop " + propertyName + " " + value);
        } catch (Throwable throwable) {
            Log.i("ZaloPatch", "Property mirror write failed key=" + key
                    + " " + throwable.getClass().getSimpleName());
            return false;
        }
    }

    // Invariant: every property name and value reaching this method has been validated against a
    // fixed shell-safe alphabet. Future callers must preserve that invariant.
    private static boolean runRootCommand(String key, String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", command
            });
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.i("ZaloPatch", "Property mirror write failed key=" + key + " exit=" + exitCode);
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            Log.i("ZaloPatch", "Property mirror write failed key=" + key
                    + " " + throwable.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isSafePropertyName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if ((character < 'a' || character > 'z')
                    && (character < '0' || character > '9')
                    && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeValue(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < 'A' || character > 'Z')
                    && (character < 'a' || character > 'z')
                    && (character < '0' || character > '9')
                    && character != '_'
                    && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean logValidationFailure(String key, String field) {
        Log.i("ZaloPatch", "Property mirror write failed key=" + key
                + " invalid " + field);
        return false;
    }

    private static String readProperty(String name, String fallback) throws Exception {
        Class<?> sp = Class.forName("android.os.SystemProperties");
        return (String) sp.getMethod("get", String.class, String.class)
                .invoke(null, name, fallback);
    }

    private static String propertyName(String key) {
        return PREFIX + Integer.toHexString(key.hashCode());
    }
}
