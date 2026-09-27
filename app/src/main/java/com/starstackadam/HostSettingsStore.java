package com.starstackadam;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Host Tailscale/LAN del Raspberry e porte FTP Helper.
 * Default: stesso host Pi, HD {@value #PORT_HD}, Vespera {@value #PORT_VESPERA}.
 */
public final class HostSettingsStore {
    public static final int PORT_HD = 2121;
    public static final int PORT_VESPERA = 2122;

    /**
     * Hostname/IP di default del Raspberry su Tailscale (MagicDNS o IP {@code 100.x}).
     * Alla prima installazione viene precompilato; poi resta quello salvato dall’utente.
     */
    public static final String DEFAULT_PI_HOST = "raspe";

    public static final String DEFAULT_HD_ENDPOINT = DEFAULT_PI_HOST + ":" + PORT_HD;
    public static final String DEFAULT_VESPERA_ENDPOINT = DEFAULT_PI_HOST + ":" + PORT_VESPERA;

    private static final String PREFS = "starstackadam_host";
    private static final String KEY_HOST = "host";
    private static final String KEY_HD_ENDPOINT = "hd_endpoint";
    private static final String KEY_VESPERA_ENDPOINT = "vespera_endpoint";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_LAST_DIR = "last_dir";

    private final SharedPreferences prefs;

    public HostSettingsStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacyHost();
    }

    /** Endpoint corrente ({@code host:porta}) per la sorgente selezionata. */
    public String getEndpoint() {
        return isHdSource() ? getHdEndpoint() : getVesperaEndpoint();
    }

    public void setEndpoint(String endpoint) {
        Endpoint parsed = Endpoint.parse(endpoint, getPort());
        if (isHdSource()) {
            setHdEndpoint(parsed.host + ":" + parsed.port);
        } else {
            setVesperaEndpoint(parsed.host + ":" + parsed.port);
        }
        // Tiene allineato anche l’host condiviso (campo legacy / stesso Pi).
        prefs.edit().putString(KEY_HOST, parsed.host).apply();
    }

    public String getHdEndpoint() {
        String value = prefs.getString(KEY_HD_ENDPOINT, null);
        if (value == null || value.trim().isEmpty()) return DEFAULT_HD_ENDPOINT;
        return value.trim();
    }

    public void setHdEndpoint(String endpoint) {
        Endpoint parsed = Endpoint.parse(endpoint, PORT_HD);
        prefs.edit()
                .putString(KEY_HD_ENDPOINT, parsed.host + ":" + parsed.port)
                .putString(KEY_HOST, parsed.host)
                .apply();
    }

    public String getVesperaEndpoint() {
        String value = prefs.getString(KEY_VESPERA_ENDPOINT, null);
        if (value == null || value.trim().isEmpty()) return DEFAULT_VESPERA_ENDPOINT;
        return value.trim();
    }

    public void setVesperaEndpoint(String endpoint) {
        Endpoint parsed = Endpoint.parse(endpoint, PORT_VESPERA);
        prefs.edit()
                .putString(KEY_VESPERA_ENDPOINT, parsed.host + ":" + parsed.port)
                .putString(KEY_HOST, parsed.host)
                .apply();
    }

    public String getHost() {
        Endpoint ep = Endpoint.parse(getEndpoint(), getPort());
        return ep.host;
    }

    public void setHost(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) h = DEFAULT_PI_HOST;
        // Se l’utente scrive solo l’host (senza porta), aggiorna entrambi gli endpoint.
        if (!h.contains(":")) {
            prefs.edit()
                    .putString(KEY_HOST, h)
                    .putString(KEY_HD_ENDPOINT, h + ":" + PORT_HD)
                    .putString(KEY_VESPERA_ENDPOINT, h + ":" + PORT_VESPERA)
                    .apply();
            return;
        }
        setEndpoint(h);
    }

    /** true = HD (:2121), false = Vespera (:2122). */
    public boolean isHdSource() {
        return !"vespera".equals(prefs.getString(KEY_SOURCE, "hd"));
    }

    public void setHdSource(boolean hd) {
        prefs.edit().putString(KEY_SOURCE, hd ? "hd" : "vespera").apply();
    }

    public int getPort() {
        Endpoint ep = Endpoint.parse(getEndpoint(), isHdSource() ? PORT_HD : PORT_VESPERA);
        return ep.port;
    }

    public String getLastDir() {
        return prefs.getString(KEY_LAST_DIR, "/");
    }

    public void setLastDir(String dir) {
        String value = (dir == null || dir.isEmpty()) ? "/" : dir;
        prefs.edit().putString(KEY_LAST_DIR, value).apply();
    }

    private void migrateLegacyHost() {
        if (prefs.contains(KEY_HD_ENDPOINT) || prefs.contains(KEY_VESPERA_ENDPOINT)) return;
        String legacy = prefs.getString(KEY_HOST, "");
        if (legacy == null || legacy.trim().isEmpty()) {
            prefs.edit()
                    .putString(KEY_HOST, DEFAULT_PI_HOST)
                    .putString(KEY_HD_ENDPOINT, DEFAULT_HD_ENDPOINT)
                    .putString(KEY_VESPERA_ENDPOINT, DEFAULT_VESPERA_ENDPOINT)
                    .apply();
            return;
        }
        String host = legacy.trim();
        if (host.contains(":")) {
            Endpoint parsed = Endpoint.parse(host, PORT_HD);
            host = parsed.host;
        }
        prefs.edit()
                .putString(KEY_HOST, host)
                .putString(KEY_HD_ENDPOINT, host + ":" + PORT_HD)
                .putString(KEY_VESPERA_ENDPOINT, host + ":" + PORT_VESPERA)
                .apply();
    }

    /** host + porta da stringa {@code host}, {@code host:port} o {@code ftp://host:port}. */
    public static final class Endpoint {
        public final String host;
        public final int port;

        public Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public static Endpoint parse(String raw, int defaultPort) {
            String text = raw == null ? "" : raw.trim();
            if (text.regionMatches(true, 0, "ftp://", 0, 6)) {
                text = text.substring(6);
            }
            int slash = text.indexOf('/');
            if (slash >= 0) text = text.substring(0, slash);
            if (text.isEmpty()) {
                return new Endpoint(DEFAULT_PI_HOST, defaultPort);
            }
            int colon = text.lastIndexOf(':');
            if (colon > 0 && colon < text.length() - 1) {
                String hostPart = text.substring(0, colon).trim();
                String portPart = text.substring(colon + 1).trim();
                try {
                    int port = Integer.parseInt(portPart);
                    if (port > 0 && port < 65536 && !hostPart.isEmpty()) {
                        return new Endpoint(hostPart, port);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return new Endpoint(text, defaultPort);
        }

        @Override public String toString() {
            return host + ":" + port;
        }
    }
}
