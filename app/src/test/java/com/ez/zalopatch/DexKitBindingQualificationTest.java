package com.ez.zalopatch;

import org.json.JSONObject;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Qualifies route identity independently from the inherited structural shape. */
public final class DexKitBindingQualificationTest {
    private static final String OWNER = "symbols.fixture.owner";
    private static final String METHOD = "symbols.fixture.method";
    private static JSONObject symbols(String owner, String method) throws Exception {
        return new JSONObject().put("fixture", new JSONObject()
                .put("owner", owner).put("method", method));
    }
    @Test public void neighbourShapeDoesNotAuthorizeRoute() throws Exception {
        assertFalse(DexKitOverlay.qualifiesBindings(null, symbols("host.Owner", "run"),
                new HashMap<>(), OWNER, METHOD));
    }
    @Test public void discoveredIdentityMustMatchEveryComposedBinding() throws Exception {
        Map<String, String> found = new HashMap<>();
        found.put(OWNER, "host.Owner");
        found.put(METHOD, "run");
        assertTrue(DexKitOverlay.qualifiesBindings(null, symbols("host.Owner", "run"),
                found, OWNER, METHOD));
        assertFalse(DexKitOverlay.qualifiesBindings(null, symbols("host.Owner", "other"),
                found, OWNER, METHOD));
        found.remove(METHOD);
        assertFalse(DexKitOverlay.qualifiesBindings(null, symbols("host.Owner", "run"),
                found, OWNER, METHOD));
    }
    @Test public void exactBindingsMustBeComplete() throws Exception {
        JSONObject exact = symbols("host.Owner", "run");
        assertTrue(DexKitOverlay.qualifiesBindings(exact, exact, null, OWNER, METHOD));
        assertFalse(DexKitOverlay.qualifiesBindings(symbols("host.Owner", ""), exact,
                null, OWNER, METHOD));
        assertFalse(DexKitOverlay.qualifiesBindings(exact, null, null, OWNER, METHOD));
        assertFalse(DexKitOverlay.qualifiesBindings(exact, exact, null));
    }
}
