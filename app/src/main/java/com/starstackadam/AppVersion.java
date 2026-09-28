package com.starstackadam;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** versionName letto dalla build. La sequenza parte da 0.0.1. */
public final class AppVersion {
    private AppVersion() {}

    public static String name(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (info.versionName != null && !info.versionName.isEmpty()) {
                return info.versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return "0.0.1";
    }

    public static long code(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) {
            return 0L;
        }
    }

    public static String label(Context context) {
        return "v" + name(context);
    }
}
