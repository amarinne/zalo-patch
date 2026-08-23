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
}
