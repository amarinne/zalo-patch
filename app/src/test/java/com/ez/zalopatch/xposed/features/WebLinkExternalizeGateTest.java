package com.ez.zalopatch.xposed.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ez.zalopatch.xposed.features.WebLinkExternalizeGate.Decision;

import org.junit.Test;

public final class WebLinkExternalizeGateTest {
    @Test
    public void contentLinkGoesExternal() {
        assertEquals(Decision.EXTERNAL,
                WebLinkExternalizeGate.classify(true, false, false,
                        "https://example.com/page?utm_source=zalo"));
        assertEquals(Decision.EXTERNAL,
                WebLinkExternalizeGate.classify(true, false, false, "http://example.com/"));
    }

    @Test
    public void missingSourceLinkKeepsInternalPagesInApp() {
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(false, false, false,
                        "https://zalo.me/settings"));
    }

    @Test
    public void miniAppAndOaH5StayInAppEvenWithSourceLink() {
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, true, false,
                        "https://mini.zalo.me/app"));
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, false, true,
                        "https://oa.zalo.me/h5"));
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, true, true,
                        "https://mini.zalo.me/app"));
    }

    @Test
    public void zaloOwnedActionsStayInApp() {
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, false, false,
                        "https://bankcard-action.zalo.me/?action=transfer&content=*"));
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, false, false,
                        "https://zalo.me/some-action"));
        assertFalse(WebLinkExternalizeGate.isZaloOwnedUrl("https://evilzalo.me/action"));
    }

    @Test
    public void nonHttpSchemesNeverGoExternal() {
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, false, false, "javascript:alert(1)"));
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, false, false, "file:///sdcard/x.html"));
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, false, false, "intent://example.com"));
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, false, false, ""));
    }

    @Test
    public void nullUrlStaysInApp() {
        assertEquals(Decision.LEAVE_IN_APP,
                WebLinkExternalizeGate.classify(true, false, false, null));
    }

    @Test
    public void schemeMatchIsCaseInsensitiveAndToleratesPadding() {
        assertEquals(Decision.EXTERNAL,
                WebLinkExternalizeGate.classify(true, false, false,
                        "  HTTPS://Example.com/Page "));
    }

    @Test
    public void webUrlCheckMatchesClassifier() {
        assertTrue(WebLinkExternalizeGate.isWebUrl("http://a"));
        assertFalse(WebLinkExternalizeGate.isWebUrl("httpx://a"));
        assertFalse(WebLinkExternalizeGate.isWebUrl(null));
    }

    @Test
    public void explanationNamesEveryGateInputWithoutLeakingTheUrl() {
        String external = WebLinkExternalizeGate.explain(
                true, false, false, "https://example.com/private/path?token=secret");
        assertEquals("source_link=1 mini_app=0 oa_h5=0 scheme=https zalo_owned=0 "
                + "decision=EXTERNAL", external);
        assertFalse(external.contains("example.com"));
        assertFalse(external.contains("secret"));

        assertEquals("source_link=1 mini_app=1 oa_h5=0 scheme=https zalo_owned=1 "
                        + "decision=LEAVE_IN_APP",
                WebLinkExternalizeGate.explain(true, true, false, "https://zalo.me/page"));
        assertEquals("source_link=0 mini_app=0 oa_h5=1 scheme=intent zalo_owned=0 "
                        + "decision=LEAVE_IN_APP",
                WebLinkExternalizeGate.explain(false, false, true, "intent://example.com"));
        assertEquals("source_link=1 mini_app=0 oa_h5=0 scheme=none zalo_owned=0 "
                        + "decision=LEAVE_IN_APP",
                WebLinkExternalizeGate.explain(true, false, false, null));
    }

    @Test
    public void schemeIsCoarseAndBounded() {
        assertEquals("https", WebLinkExternalizeGate.scheme(" HTTPS://Example.com "));
        assertEquals("http", WebLinkExternalizeGate.scheme("http://a"));
        assertEquals("javascript", WebLinkExternalizeGate.scheme("javascript:alert(1)"));
        assertEquals("none", WebLinkExternalizeGate.scheme("no-scheme"));
        assertEquals("none", WebLinkExternalizeGate.scheme(null));
        assertEquals("abcdefghijklmnop",
                WebLinkExternalizeGate.scheme("abcdefghijklmnopqrstuvwxyz://x"));
    }
}
