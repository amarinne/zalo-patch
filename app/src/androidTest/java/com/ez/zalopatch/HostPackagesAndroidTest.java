package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.test.AndroidTestCase;

/** Uses the installed test target as a second artifact without changing any Zalo installation. */
public final class HostPackagesAndroidTest extends AndroidTestCase {
    public void testScopedStorageKeepsPrimaryAndClonesSeparate() {
        Context base = getContext();
        Context first = HostPackages.scoped(base, "vn.example.clone1");
        Context second = HostPackages.scoped(base, "vn.example.clone2");
        String fileName = "host_scope_test";
        SharedPreferences primary = base.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        SharedPreferences one = first.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        SharedPreferences two = second.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        try {
            assertTrue(one.edit().putString("value", "first").commit());
            assertTrue(two.edit().putString("value", "second").commit());
            assertFalse(primary.contains("value"));
            assertEquals("first", one.getString("value", ""));
            assertEquals("second", two.getString("value", ""));
            assertFalse(first.getFilesDir().equals(second.getFilesDir()));
            assertFalse(first.getFilesDir().equals(base.getFilesDir()));
            assertSame(first, first.getApplicationContext());
            assertSame(base, HostPackages.base(first));
        } finally {
            first.deleteSharedPreferences(fileName + ".host." + ZaloArtifactIdentity.sha256("vn.example.clone1"));
            second.deleteSharedPreferences(fileName + ".host." + ZaloArtifactIdentity.sha256("vn.example.clone2"));
            // These test-only directories are empty; retain anything another operation created.
            first.getFilesDir().delete();
            second.getFilesDir().delete();
        }
    }

    public void testArtifactAndProfileUseTheRequestedInstallation() throws Exception {
        Context clone = HostPackages.scoped(getContext(), getContext().getPackageName());
        assertEquals(getContext().getPackageName(), HostPackages.target(clone));
        assertEquals(BuildConfig.VERSION_CODE, SymbolSchema.installedZaloVersionCode(clone));
        ZaloArtifactIdentity identity = ZaloArtifactIdentity.capture(clone, false);
        assertEquals(getContext().getPackageName(), identity.packageName);
        assertEquals(BuildConfig.VERSION_CODE, identity.versionCode);
        SymbolSchema.Active profile = SymbolSchema.active(clone);
        assertFalse(profile.valid);
        assertEquals(BuildConfig.VERSION_CODE, profile.installedVersionCode);
        assertFalse(ZaloArtifactState.currentCompatibility(clone).compatible);
    }
}
