package com.ez.zalopatch;

import org.junit.Test;
import java.util.Collections;
import java.util.Set;
import static org.junit.Assert.*;

public final class HostPackagesTest {
    @Test public void clonesRequireExplicitTrustAndKeepTheOriginalEnabled() {
        Set<String> trusted = HostPackages.parse("vn.example.zalo2\ncom.zing.zalo.clone");
        assertEquals("vn.example.zalo2", HostPackages.selectCaller(trusted,
                new String[]{"vn.example.zalo2"}));
        assertNull(HostPackages.selectCaller(Collections.emptySet(),
                new String[]{"vn.example.zalo2"}));
        assertEquals(HostPackages.ZALO, HostPackages.selectCaller(Collections.emptySet(),
                new String[]{HostPackages.ZALO}));
        assertNull(HostPackages.selectCaller(trusted, new String[]{"com.example.unrelated"}));
        assertNull(HostPackages.selectCaller(trusted, null));
        assertNull(HostPackages.selectCaller(trusted, new String[0]));
    }

    @Test public void sharedUidCannotChooseBetweenAdmittedInstallations() {
        assertNull(HostPackages.selectCaller(HostPackages.parse("vn.example.zalo2"),
                new String[]{HostPackages.ZALO, "vn.example.zalo2"}));
        assertEquals("vn.example.zalo2", HostPackages.selectCaller(
                HostPackages.parse("vn.example.zalo2"),
                new String[]{"com.example.other", "vn.example.zalo2"}));
    }

    @Test public void packageInputIsNormalizedAndBounded() {
        assertEquals(HostPackages.parse("vn.example.zalo2"),
                HostPackages.parse("com.zing.zalo, vn.example.zalo2;vn.example.zalo2"));
        for (String invalid : new String[]{"com.ez.zalopatch", "invalid", "com.example/other",
                "com.example.*", "com.example..clone", "com.example.$(id)"}) {
            assertThrows(IllegalArgumentException.class, () -> HostPackages.parse(invalid));
        }
        StringBuilder packages = new StringBuilder();
        for (int index = 0; index < 17; index++) packages.append("com.example.clone").append(index).append('\n');
        assertThrows(IllegalArgumentException.class, () -> HostPackages.parse(packages.toString()));
        assertTrue(HostPackages.parse(null).isEmpty());
    }

    @Test public void renamedAndExplicitMainProcessesAreRecognizedWithoutTreatingServicesAsMain() {
        assertTrue(HostPackages.mainProcess("vn.example.zalo2", null, "vn.example.zalo2"));
        assertTrue(HostPackages.mainProcess("vn.example.zalo2", "com.zing.zalo", "com.zing.zalo"));
        assertFalse(HostPackages.mainProcess("vn.example.zalo2", null, "vn.example.zalo2:service"));
        assertFalse(HostPackages.mainProcess("vn.example.zalo2", "com.zing.zalo", "vn.example.zalo2"));
        assertTrue(HostPackages.mainProcess("vn.example.zalo2", null, null));
    }

    @Test public void loaderRequiresZaloClassesForRenamedPackages() {
        ClassLoader empty = new ClassLoader(null) {};
        assertTrue(HostPackages.canLoad(HostPackages.ZALO, empty));
        assertFalse(HostPackages.canLoad("com.example.other", empty));
        assertFalse(HostPackages.canLoad("com.ez.zalopatch", getClass().getClassLoader()));
        assertTrue(HostPackages.canLoad("vn.example.zalo2", getClass().getClassLoader()));
        ClassLoader noPeer = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("com.vng.zing.vn.zrtc.PeerJNI")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        assertFalse(HostPackages.canLoad("vn.example.zalo2", noPeer));
    }
}
