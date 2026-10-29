package rikka.shizuku.server.util;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public final class InstalledPackagesCompat {

    private static final String TAG = "InstalledPackagesCompat";
    private static final int ANDROID_13 = 33;
    private static final String PARCELED_LIST_SLICE = "android.content.pm.ParceledListSlice";

    private InstalledPackagesCompat() {
    }

    public static List<PackageInfo> getInstalledPackagesNoThrow(long flags, int userId) {
        try {
            List<PackageInfo> packages = getInstalledPackages(flags, userId);
            return packages == null ? Collections.emptyList() : packages;
        } catch (Throwable e) {
            Log.w(TAG, "getInstalledPackages failed", e);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<PackageInfo> getInstalledPackages(long flags, int userId) throws ReflectiveOperationException {
        try {
            var slice = rikka.hidden.compat.PackageManagerApis.getInstalledPackages(flags, userId);
            if (slice != null && slice.getList() != null) {
                return slice.getList();
            }
        } catch (Throwable tr) {
            Log.d(TAG, "PackageManagerApis.getInstalledPackages failed, falling back to direct reflection", tr);
        }

        Object packageManager = getPackageManager();
        Method method;
        Object result;

        if (Build.VERSION.SDK_INT >= ANDROID_13) {
            method = packageManager.getClass().getMethod("getInstalledPackages", long.class, int.class);
            result = invoke(method, packageManager, flags, userId);
        } else {
            method = packageManager.getClass().getMethod("getInstalledPackages", int.class, int.class);
            result = invoke(method, packageManager, (int) flags, userId);
        }

        if (result == null) {
            return Collections.emptyList();
        }

        String resultClassName = result.getClass().getName();
        if (resultClassName.startsWith(PARCELED_LIST_SLICE) || resultClassName.contains("PackageInfoList")) {
            Object list = result.getClass().getMethod("getList").invoke(result);
            return list == null ? Collections.emptyList() : (List<PackageInfo>) list;
        }

        throw new IllegalStateException("Unsupported getInstalledPackages return type: " + resultClassName);
    }

    private static Object getPackageManager() throws ReflectiveOperationException {
        Class<?> servicesClass = Class.forName("rikka.hidden.compat.Services");
        var field = servicesClass.getDeclaredField("packageManager");
        field.setAccessible(true);
        Object service = field.get(null);
        return service.getClass().getMethod("get").invoke(service);
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ReflectiveOperationException) {
                throw (ReflectiveOperationException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }
}
