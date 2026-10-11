package rikka.shizuku.shell;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.Os;
import android.text.TextUtils;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.BaseDexClassLoader;
import rikka.hidden.compat.PackageManagerApis;
import stub.dalvik.system.VMRuntimeHidden;

@SuppressWarnings("deprecation")
public class ShizukuShellLoader {

    private static final long REQUEST_BINDER_TIMEOUT_MS = 15000L;
    private static final long REQUEST_BINDER_ACTIVITY_FALLBACK_MS = 2500L;

    private static String[] args;
    private static String callingPackage;
    private static String managerApplicationId;
    private static Handler handler;
    private static Bundle requestData;
    private static Runnable timeoutRunnable;
    private static Runnable activityFallbackRunnable;
    private static final AtomicBoolean binderReceived = new AtomicBoolean(false);

    private static final Binder receiverBinder = new Binder() {

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                ApplicationInfo managerApplicationInfo = requireManagerCaller();
                IBinder binder = data.readStrongBinder();
                data.readString(); // Legacy wire field; never trust a caller-provided code path.
                if (binder != null && binder.pingBinder()) {
                    if (!binderReceived.compareAndSet(false, true)) {
                        return true;
                    }
                    if (timeoutRunnable != null) {
                        handler.removeCallbacks(timeoutRunnable);
                    }
                    if (activityFallbackRunnable != null) {
                        handler.removeCallbacks(activityFallbackRunnable);
                    }
                    handler.post(() -> onBinderReceived(binder, managerApplicationInfo.sourceDir));
                } else {
                    System.err.println("Server is not running");
                    System.err.flush();
                    System.exit(1);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private static String resolveManagerApplicationId() {
        String applicationId = System.getenv("MANAGER_APPLICATION_ID");
        if (TextUtils.isEmpty(applicationId) || "MANAGER_PKG".equals(applicationId)) {
            applicationId = BuildConfig.MANAGER_APPLICATION_ID;
        }
        return applicationId;
    }

    private static ApplicationInfo requireManagerCaller() {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        // Android's platform UID contract assigns a range of 100000 IDs per user.
        int callingUserId = callingUid / 100000;
        ApplicationInfo applicationInfo = PackageManagerApis.getApplicationInfoNoThrow(
                managerApplicationId, 0, callingUserId);
        if (applicationInfo == null
                || applicationInfo.uid != callingUid
                || TextUtils.isEmpty(applicationInfo.sourceDir)
                || !PackageManagerApis.getPackagesForUidNoThrow(callingUid).contains(managerApplicationId)) {
            throw new SecurityException(
                    "Permission Denial: binder response from uid=" + callingUid
                            + ", pid=" + callingPid
                            + " is not the manager");
        }
        return applicationInfo;
    }

    private static void requestForBinder() throws RemoteException {
        Bundle data = new Bundle();
        data.putBinder("binder", receiverBinder);
        requestData = data;

        Intent intent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                .setPackage(managerApplicationId)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("data", data);

        IBinder amBinder = ServiceManager.getService("activity");
        IActivityManager am;
        if (Build.VERSION.SDK_INT >= 26) {
            am = IActivityManager.Stub.asInterface(amBinder);
        } else {
            am = ActivityManagerNative.asInterface(amBinder);
        }

        // broadcastIntent will fail on Android 8.x
        //  com.android.server.am.ActivityManagerService.isInstantApp(ActivityManagerService.java:18547)
        //  com.android.server.am.ActivityManagerService.broadcastIntentLocked(ActivityManagerService.java:18972)
        //  com.android.server.am.ActivityManagerService.broadcastIntent(ActivityManagerService.java:19703)
        //
        try {
            am.broadcastIntent(null, intent, null, null, 0, null, null,
                    null, -1, null, true, false, 0);
        } catch (Throwable e) {
            if ((Build.VERSION.SDK_INT != Build.VERSION_CODES.O && Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1)
                    || !Objects.equals(e.getMessage(), "Calling application did not provide package name")) {
                throw e;
            }

            System.err.println("broadcastIntent fails on Android 8.0 or 8.1, fallback to startActivity");
            System.err.flush();

            startActivityFallback(data);
        }
    }

    private static void startActivityFallback(Bundle data) throws RemoteException {
        if (binderReceived.get()) {
            return;
        }

        Intent activityIntent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                .setPackage(managerApplicationId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("data", data);

        IBinder amBinder = ServiceManager.getService("activity");
        IActivityManager am;
        if (Build.VERSION.SDK_INT >= 26) {
            am = IActivityManager.Stub.asInterface(amBinder);
        } else {
            am = ActivityManagerNative.asInterface(amBinder);
        }
        am.startActivityAsUser(null, callingPackage, activityIntent, null, null, null, 0, 0, null, null, Os.getuid() / 100000);
    }

    private static void onBinderReceived(IBinder binder, String sourceDir) {
        var base = sourceDir.substring(0, sourceDir.lastIndexOf('/'));
        String librarySearchPath = base + "/lib/" + VMRuntimeHidden.getRuntime().vmInstructionSet();
        String systemLibrarySearchPath = System.getProperty("java.library.path");
        if (!TextUtils.isEmpty(systemLibrarySearchPath)) {
            librarySearchPath += File.pathSeparatorChar + systemLibrarySearchPath;
        }

        try {
            var classLoader = new BaseDexClassLoader(sourceDir, null, librarySearchPath, ClassLoader.getSystemClassLoader());
            Class<?> cls = classLoader.loadClass("moe.shizuku.manager.shell.Shell");
            cls.getDeclaredMethod("main", String[].class, String.class, IBinder.class, Handler.class)
                    .invoke(null, args, callingPackage, binder, handler);
        } catch (ClassNotFoundException tr) {
            System.err.println("Class not found");
            System.err.println("Make sure you have Shizuku v12.0.0 or above installed");
            System.err.flush();
            System.exit(1);
        } catch (Throwable tr) {
            tr.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        ShizukuShellLoader.args = args;
        ShizukuShellLoader.managerApplicationId = resolveManagerApplicationId();

        String packageName;
        var pkg = PackageManagerApis.getPackagesForUidNoThrow(Os.getuid());
        if (pkg.size() == 1) {
            packageName = pkg.get(0);
        } else {
            packageName = System.getenv("RISH_APPLICATION_ID");
            if (TextUtils.isEmpty(packageName) || "PKG".equals(packageName)) {
                abort("RISH_APPLICATION_ID is not set, set this environment variable to the id of current application (package name)");
                System.exit(1);
            }
        }

        ShizukuShellLoader.callingPackage = packageName;

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        handler = new Handler(Looper.getMainLooper());

        try {
            requestForBinder();
        } catch (Throwable tr) {
            tr.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }

        activityFallbackRunnable = () -> {
            if (binderReceived.get() || requestData == null) {
                return;
            }
            try {
                startActivityFallback(requestData);
            } catch (Throwable ignored) {
            }
        };
        handler.postDelayed(activityFallbackRunnable, REQUEST_BINDER_ACTIVITY_FALLBACK_MS);

        timeoutRunnable = () -> abort(
                String.format(
                        "Request timeout. Shizuku did not respond to the current app (%1$s) in time. " +
                                "Please make sure Shizuku is running, then try again. If this keeps happening, remove battery restrictions for both current app (%1$s) and Shizuku.",
                        packageName)
        );
        handler.postDelayed(timeoutRunnable, REQUEST_BINDER_TIMEOUT_MS);

        Looper.loop();
        System.exit(0);
    }

    private static void abort(String message) {
        System.err.println(message);
        System.err.flush();
        System.exit(1);
    }
}
