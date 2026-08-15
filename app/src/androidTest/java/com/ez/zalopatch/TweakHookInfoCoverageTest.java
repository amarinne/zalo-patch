package com.ez.zalopatch;

import android.test.AndroidTestCase;

public final class TweakHookInfoCoverageTest extends AndroidTestCase {
    private static final String NO_RUNTIME_HOOK = "No runtime hook";

    public void testEveryTweakHasRuntimeHookMetadata() {
        SymbolSchema.Active schema = SymbolSchema.active(getContext());

        for (Tweaks.Item item : Tweaks.ITEMS) {
            TweakHookInfo.Info info = TweakHookInfo.forKey(item.key, schema);
            assertNotNull("Missing hook metadata for " + item.key, info);
            assertNotNull("Missing hook path for " + item.key, info.path);
            assertFalse("Empty hook path for " + item.key, info.path.isEmpty());
            assertFalse("Default hook path for " + item.key,
                    NO_RUNTIME_HOOK.equals(info.path));
        }
    }
}
