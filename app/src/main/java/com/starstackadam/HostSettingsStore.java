package com.starstackadam;

import android.content.Context;
import android.content.SharedPreferences;

/** Host Tailscale/LAN, porte HD/Vespera e ultima cartella FTP. */
public final class HostSettingsStore {
    public static final int PORT_HD = 2121;
    public static final int PORT_VESPERA = 2122;

    private static final String PREFS = "starstackadam_host";
    private static final String KEY_HOST = "host";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_LAST_DIR = "last_dir";

    private final SharedPreferences prefs;

    public HostSettingsStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getHost() {
        return prefs.getString(KEY_HOST, "");
    }

    public void setHost(String host) {
        prefs.edit().putString(KEY_HOST, host == null ? "" : host.trim()).apply();
    }

    /** true = HD (:2121), false = Vespera (:2122). */
    public boolean isHdSource() {
        return !"vespera".equals(prefs.getString(KEY_SOURCE, "hd"));
    }

    public void setHdSource(boolean hd) {
        prefs.edit().putString(KEY_SOURCE, hd ? "hd" : "vespera").apply();
    }

    public int getPort() {
        return isHdSource() ? PORT_HD : PORT_VESPERA;
    }

    public String getLastDir() {
        return prefs.getString(KEY_LAST_DIR, "/");
    }

    public void setLastDir(String dir) {
        String value = (dir == null || dir.isEmpty()) ? "/" : dir;
        prefs.edit().putString(KEY_LAST_DIR, value).apply();
    }
}
