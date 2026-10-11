package rikka.shizuku.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class ManagerCallerVerifierTest {

    private static final int MANAGER_APP_ID = 10123;

    @Test
    public void acceptsManagerInOwnerAndSecondaryUsers() {
        assertTrue(ManagerCallerVerifier.isManagerCaller(
                MANAGER_APP_ID,
                MANAGER_APP_ID,
                Collections.singletonList(ServerConstants.MANAGER_APPLICATION_ID)));
        assertTrue(ManagerCallerVerifier.isManagerCaller(
                10 * 100000 + MANAGER_APP_ID,
                MANAGER_APP_ID,
                List.of(ServerConstants.MANAGER_APPLICATION_ID, "shared.uid.peer")));
    }

    @Test
    public void rejectsRootShellSystemAndOrdinaryApps() {
        assertFalse(ManagerCallerVerifier.isManagerCaller(
                0, MANAGER_APP_ID, Collections.singletonList("root")));
        assertFalse(ManagerCallerVerifier.isManagerCaller(
                2000, MANAGER_APP_ID, Collections.singletonList("com.android.shell")));
        assertFalse(ManagerCallerVerifier.isManagerCaller(
                1000, MANAGER_APP_ID, Collections.singletonList("android")));
        assertFalse(ManagerCallerVerifier.isManagerCaller(
                10456, MANAGER_APP_ID, Collections.singletonList("example.untrusted")));
    }

    @Test
    public void rejectsCallerSuppliedPackageSpoofAndStaleUid() {
        assertFalse(ManagerCallerVerifier.isManagerCaller(
                10456,
                MANAGER_APP_ID,
                Collections.singletonList(ServerConstants.MANAGER_APPLICATION_ID)));
        assertFalse(ManagerCallerVerifier.isManagerCaller(
                MANAGER_APP_ID,
                MANAGER_APP_ID,
                Collections.singletonList("example.untrusted")));
        assertFalse(ManagerCallerVerifier.isManagerCaller(
                MANAGER_APP_ID,
                MANAGER_APP_ID,
                null));
    }

    @Test
    public void rejectsInvalidIdentityValues() {
        assertFalse(ManagerCallerVerifier.isManagerCaller(
                -1,
                MANAGER_APP_ID,
                Collections.singletonList(ServerConstants.MANAGER_APPLICATION_ID)));
        assertFalse(ManagerCallerVerifier.isManagerCaller(
                MANAGER_APP_ID,
                -1,
                Collections.singletonList(ServerConstants.MANAGER_APPLICATION_ID)));
    }
}
