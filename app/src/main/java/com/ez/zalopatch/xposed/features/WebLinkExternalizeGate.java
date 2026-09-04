package com.ez.zalopatch.xposed.features;

import java.util.Locale;
import java.net.URI;

/**
 * Decision core for opening a tapped chat/feed link outside Zalo. Pure Java so the gate logic is
 * unit-testable without Xposed or Android classes; the hook reads the Bundle and feeds this.
 *
 * <p>Gate order mirrors the hook contract: only links that carry {@code EXTRA_SOURCE_LINK} are
 * content links at all; mini-app and OA H5 opens stay in-app even when they carry it; and only
 * http/https URLs can be handed to the system.
 */
final class WebLinkExternalizeGate {
    static final String KEY_SOURCE_LINK = "EXTRA_SOURCE_LINK";
    static final String KEY_FROM_MINI_APP = "EXTRA_OPEN_FROM_MINI_APP";
    static final String KEY_OA_H5 = "extra_oa_id_open_h5";

    enum Decision {
        EXTERNAL,
        LEAVE_IN_APP
    }

    private WebLinkExternalizeGate() {
    }

    static Decision classify(boolean hasSourceLink, boolean fromMiniApp, boolean oaH5,
                             String url) {
        if (!hasSourceLink || fromMiniApp || oaH5) {
            return Decision.LEAVE_IN_APP;
        }
        // Zalo-owned web actions include payments and bank-card flows. They must keep
        // Zalo's authenticated in-app context even when ordinary content links go external.
        if (isZaloOwnedUrl(url)) {
            return Decision.LEAVE_IN_APP;
        }
        return isWebUrl(url) ? Decision.EXTERNAL : Decision.LEAVE_IN_APP;
    }

    static boolean isZaloOwnedUrl(String url) {
        if (!isWebUrl(url)) {
            return false;
        }
        try {
            String host = new URI(url.trim()).getHost();
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.US);
            return "zalo.me".equals(lower) || lower.endsWith(".zalo.me");
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isWebUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
