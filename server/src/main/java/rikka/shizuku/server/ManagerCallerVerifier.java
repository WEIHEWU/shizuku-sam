package rikka.shizuku.server;

import androidx.annotation.Nullable;

import java.util.List;

import rikka.shizuku.server.util.UserHandleCompat;

/**
 * Verifies the manager security principal using only Binder/PackageManager-derived identity.
 */
final class ManagerCallerVerifier {

    private ManagerCallerVerifier() {
    }

    static boolean isManagerCaller(
            int callingUid,
            int managerAppId,
            @Nullable List<String> callingPackages) {
        if (callingUid < 0 || managerAppId < 0) {
            return false;
        }
        if (UserHandleCompat.getAppId(callingUid) != managerAppId) {
            return false;
        }
        return callingPackages != null
                && callingPackages.contains(ServerConstants.MANAGER_APPLICATION_ID);
    }
}
