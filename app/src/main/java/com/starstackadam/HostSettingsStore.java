package com.starstackadam;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Endpoint FTP di HD e Vespera, indipendenti.
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
    private static final String KEY_FTP_TIMEOUT = "ftp_timeout_sec";
    private static final String KEY_LIVE_EDGE = "live_edge";
    private static final String KEY_LIVE_STARS = "live_stars";
    private static final String KEY_LIVE_VOTES = "live_votes";
    private static final String KEY_LIVE_REJECT = "live_reject";
    private static final String KEY_LIVE_BG = "live_bg";
    private static final String KEY_SHARE_EDGE = "share_edge";
    private static final String KEY_SHARE_STARS = "share_stars";
    private static final String KEY_SHARE_VOTES = "share_votes";
    private static final String KEY_SHARE_REJECT = "share_reject";
    private static final String KEY_SHARE_BG = "share_bg";
    private static final String KEY_SHARE_FITS = "share_fits";

    /** Timeout di connessione FTP, in secondi. */
    public static final int[] FTP_TIMEOUTS = {8, 12, 20, 30, 45};

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
                .apply();
    }

    public String getHost() {
        Endpoint ep = Endpoint.parse(getEndpoint(), getPort());
        return ep.host;
    }

    public void setHost(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) h = DEFAULT_PI_HOST;
        // Se l’utente scrive solo l’host (senza porta), aggiorna entrambi gli endpoint
        // tenendo le porte già scelte in Impostazioni.
        if (!h.contains(":")) {
            prefs.edit()
                    .putString(KEY_HOST, h)
                    .putString(KEY_HD_ENDPOINT, h + ":" + getHdPort())
                    .putString(KEY_VESPERA_ENDPOINT, h + ":" + getVesperaPort())
                    .apply();
            return;
        }
        setEndpoint(h);
    }

    public int getHdPort() {
        return Endpoint.parse(getHdEndpoint(), PORT_HD).port;
    }

    public int getVesperaPort() {
        return Endpoint.parse(getVesperaEndpoint(), PORT_VESPERA).port;
    }

    public int getFtpTimeoutSec() {
        return StackOptions.nearest(prefs.getInt(KEY_FTP_TIMEOUT, 12), FTP_TIMEOUTS);
    }

    public StackOptions getLiveOptions() {
        return StackOptions.live(
                prefs.getInt(KEY_LIVE_EDGE, 1280),
                prefs.getInt(KEY_LIVE_STARS, 40),
                prefs.getInt(KEY_LIVE_VOTES, 6),
                prefs.getBoolean(KEY_LIVE_REJECT, true),
                prefs.getBoolean(KEY_LIVE_BG, false));
    }

    public StackOptions getShareOptions() {
        return StackOptions.share(
                prefs.getInt(KEY_SHARE_EDGE, 2048),
                prefs.getInt(KEY_SHARE_STARS, 60),
                prefs.getInt(KEY_SHARE_VOTES, 6),
                prefs.getBoolean(KEY_SHARE_REJECT, true),
                prefs.getBoolean(KEY_SHARE_BG, false),
                prefs.getBoolean(KEY_SHARE_FITS, false));
    }

    /**
     * Salva i due endpoint, il timeout e i due profili di stack.
     * HD e Vespera possono avere host e porta diversi.
     */
    public void saveSettings(
            String hdEndpoint,
            String vesperaEndpoint,
            int timeoutSec,
            StackOptions live,
            StackOptions share) {
        Endpoint hd = Endpoint.parse(hdEndpoint, PORT_HD);
        Endpoint ve = Endpoint.parse(vesperaEndpoint, PORT_VESPERA);
        String hdHost = hd.host.isEmpty() ? DEFAULT_PI_HOST : hd.host;
        String veHost = ve.host.isEmpty() ? DEFAULT_PI_HOST : ve.host;
        int hdPort = clampPort(hd.port, PORT_HD);
        int vePort = clampPort(ve.port, PORT_VESPERA);
        StackOptions liveSafe = live == null ? StackOptions.liveBalanced() : live;
        StackOptions shareSafe = share == null ? StackOptions.shareQuality() : share;
        prefs.edit()
                .putString(KEY_HOST, hdHost)
                .putString(KEY_HD_ENDPOINT, hdHost + ":" + hdPort)
                .putString(KEY_VESPERA_ENDPOINT, veHost + ":" + vePort)
                .putInt(KEY_FTP_TIMEOUT, StackOptions.nearest(timeoutSec, FTP_TIMEOUTS))
                .putInt(KEY_LIVE_EDGE, liveSafe.maxEdge)
                .putInt(KEY_LIVE_STARS, liveSafe.maxStars)
                .putInt(KEY_LIVE_VOTES, liveSafe.minVotes)
                .putBoolean(KEY_LIVE_REJECT, liveSafe.rejectUnaligned)
                .putBoolean(KEY_LIVE_BG, liveSafe.subtractBackground)
                .putInt(KEY_SHARE_EDGE, shareSafe.maxEdge)
                .putInt(KEY_SHARE_STARS, shareSafe.maxStars)
                .putInt(KEY_SHARE_VOTES, shareSafe.minVotes)
                .putBoolean(KEY_SHARE_REJECT, shareSafe.rejectUnaligned)
                .putBoolean(KEY_SHARE_BG, shareSafe.subtractBackground)
                .putBoolean(KEY_SHARE_FITS, shareSafe.useFits)
                .apply();
    }

    private static int clampPort(int port, int fallback) {
        if (port > 0 && port < 65536) return port;
        return fallback;
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

    /**
     * Parametri di uno dei due stack.
     * Live (prestazioni): lato più corto. Le pose non allineate restano fuori.
     * Condivisibile (qualità) di default esclude le pose sotto soglia e sale di risoluzione.
     */
    public static final class StackOptions {
        public static final int[] LIVE_EDGES = {640, 960, 1280, 1600};
        public static final int[] SHARE_EDGES = {1280, 1600, 2048, 2560};
        public static final int[] STARS = {24, 40, 60, 80};
        public static final int[] VOTES = {3, 4, 6, 8};

        public final int maxEdge;
        public final int maxStars;
        public final int minVotes;
        public final boolean rejectUnaligned;
        public final boolean subtractBackground;
        /** Solo lo stack condivisibile. Il live resta sui JPEG. */
        public final boolean useFits;

        private StackOptions(
                int maxEdge,
                int maxStars,
                int minVotes,
                boolean rejectUnaligned,
                boolean subtractBackground,
                boolean useFits) {
            this.maxEdge = maxEdge;
            this.maxStars = maxStars;
            this.minVotes = minVotes;
            this.rejectUnaligned = rejectUnaligned;
            this.subtractBackground = subtractBackground;
            this.useFits = useFits;
        }

        public static StackOptions live(
                int edge, int stars, int votes, boolean reject, boolean background) {
            return new StackOptions(
                    nearest(edge, LIVE_EDGES),
                    nearest(stars, STARS),
                    nearest(votes, VOTES),
                    reject,
                    background,
                    false);
        }

        public static StackOptions share(
                int edge, int stars, int votes, boolean reject, boolean background) {
            return share(edge, stars, votes, reject, background, false);
        }

        public static StackOptions share(
                int edge, int stars, int votes, boolean reject, boolean background, boolean fits) {
            return new StackOptions(
                    nearest(edge, SHARE_EDGES),
                    nearest(stars, STARS),
                    nearest(votes, VOTES),
                    reject,
                    background,
                    fits);
        }

        public static StackOptions livePerformance() {
            return live(960, 24, 3, true, false);
        }

        public static StackOptions liveBalanced() {
            return live(1280, 40, 6, true, false);
        }

        public static StackOptions liveSharp() {
            return live(1600, 60, 6, true, false);
        }

        public static StackOptions shareFast() {
            return share(1600, 40, 6, true, false);
        }

        public static StackOptions shareQuality() {
            return share(2048, 60, 6, true, false);
        }

        public static StackOptions shareMax() {
            return share(2560, 60, 6, true, true);
        }

        public boolean same(StackOptions other) {
            return other != null
                    && maxEdge == other.maxEdge
                    && maxStars == other.maxStars
                    && minVotes == other.minVotes
                    && rejectUnaligned == other.rejectUnaligned
                    && subtractBackground == other.subtractBackground;
        }

        public String summary() {
            return maxEdge + " px · " + maxStars + " stelle · voti ≥ " + minVotes
                    + (rejectUnaligned ? " · esclude le pose deboli" : " · tiene le pose deboli")
                    + (subtractBackground ? " · fondo sottratto" : "")
                    + (useFits ? " · FITS" : "");
        }

        public static int nearest(int value, int[] allowed) {
            int best = allowed[0];
            int dist = Math.abs(value - best);
            for (int i = 1; i < allowed.length; i++) {
                int d = Math.abs(value - allowed[i]);
                if (d < dist) {
                    dist = d;
                    best = allowed[i];
                }
            }
            return best;
        }
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
