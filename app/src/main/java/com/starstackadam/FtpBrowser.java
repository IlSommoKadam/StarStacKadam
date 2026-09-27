package com.starstackadam;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Client FTP anonymous PASV (Commons Net).
 * Se PASV annuncia 0.0.0.0 / loopback o IP privato non raggiungibile
 * rispetto al controllo, usa l'host di controllo (Tailscale/LAN).
 */
public final class FtpBrowser implements AutoCloseable {
    public static final class Entry {
        public final String name;
        public final String path;
        public final boolean directory;
        public final long size;

        public Entry(String name, String path, boolean directory, long size) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
        }
    }

    private final FTPClient ftp = new FTPClient();
    private String controlHost = "";

    public void connect(String host, int port) throws IOException {
        disconnectQuietly();
        controlHost = host == null ? "" : host.trim();
        if (controlHost.isEmpty()) throw new IOException("Host vuoto");

        ftp.setConnectTimeout(12_000);
        ftp.setDefaultTimeout(30_000);
        ftp.setDataTimeout(java.time.Duration.ofSeconds(60));
        ftp.setPassiveNatWorkaroundStrategy(new PasvHostResolver(controlHost));

        ftp.connect(controlHost, port);
        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            disconnectQuietly();
            throw new IOException("FTP rifiutato: " + ftp.getReplyString());
        }
        if (!ftp.login("anonymous", "starstackadam@")) {
            disconnectQuietly();
            throw new IOException("Login anonymous fallito: " + ftp.getReplyString());
        }
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.setKeepAlive(true);
    }

    public boolean isConnected() {
        return ftp.isConnected();
    }

    public String pwd() throws IOException {
        ensureConnected();
        String dir = ftp.printWorkingDirectory();
        return dir == null || dir.isEmpty() ? "/" : dir;
    }

    public void cwd(String path) throws IOException {
        ensureConnected();
        String target = (path == null || path.isEmpty()) ? "/" : path;
        if (!ftp.changeWorkingDirectory(target)) {
            throw new IOException("CWD fallito: " + target + " — " + ftp.getReplyString());
        }
    }

    public List<Entry> list(String path) throws IOException {
        ensureConnected();
        if (path != null && !path.isEmpty()) cwd(path);
        String cwd = pwd();
        FTPFile[] files = ftp.listFiles();
        if (files == null) {
            throw new IOException("LIST fallito: " + ftp.getReplyString());
        }
        List<Entry> out = new ArrayList<>();
        for (FTPFile file : files) {
            if (file == null) continue;
            String name = file.getName();
            if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
            String child = joinPath(cwd, name);
            out.add(new Entry(name, child, file.isDirectory(), file.getSize()));
        }
        out.sort(Comparator
                .comparing((Entry e) -> !e.directory)
                .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
        return out;
    }

    public void retr(String remotePath, File dest) throws IOException {
        ensureConnected();
        if (remotePath == null || remotePath.isEmpty()) {
            throw new IOException("Percorso remoto vuoto");
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Impossibile creare cache: " + parent);
        }
        File tmp = new File(dest.getAbsolutePath() + ".part");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            if (!ftp.retrieveFile(remotePath, out)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("RETR fallito: " + remotePath + " — " + ftp.getReplyString());
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        if (dest.exists() && !dest.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Impossibile sovrascrivere: " + dest);
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Rename cache fallito: " + dest);
        }
    }

    public void disconnectQuietly() {
        try {
            if (ftp.isConnected()) {
                try {
                    ftp.logout();
                } catch (IOException ignored) {
                }
                ftp.disconnect();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        disconnectQuietly();
    }

    private void ensureConnected() throws IOException {
        if (!ftp.isConnected()) throw new IOException("FTP non connesso");
    }

    static String joinPath(String dir, String name) {
        if (dir == null || dir.isEmpty() || "/".equals(dir)) return "/" + name;
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }

    static String parentPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return "/";
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        if (slash <= 0) return "/";
        return trimmed.substring(0, slash);
    }

    /** Estensioni accettate per lo stack preview. */
    public static boolean isImageName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp");
    }

    public static boolean isFitsName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".fits") || lower.endsWith(".fit") || lower.endsWith(".fts");
    }

    private static final class PasvHostResolver implements FTPClient.HostnameResolver {
        private final String controlHost;

        PasvHostResolver(String controlHost) {
            this.controlHost = controlHost;
        }

        @Override
        public String resolve(String hostname) throws UnknownHostException {
            if (shouldUseControlHost(hostname, controlHost)) {
                return controlHost;
            }
            return hostname;
        }
    }

    static boolean shouldUseControlHost(String pasvHost, String controlHost) {
        if (pasvHost == null || pasvHost.isEmpty()) return true;
        String host = pasvHost.trim();
        if ("0.0.0.0".equals(host) || host.startsWith("0.")
                || host.startsWith("127.") || "localhost".equalsIgnoreCase(host)) {
            return true;
        }
        if (controlHost == null || controlHost.isEmpty()) return false;
        if (host.equalsIgnoreCase(controlHost)) return false;
        try {
            InetAddress pasv = InetAddress.getByName(host);
            if (pasv.isAnyLocalAddress() || pasv.isLoopbackAddress()) return true;
            InetAddress control = InetAddress.getByName(controlHost);
            // Private mismatch: PASV privato ma controllo altrove (es. Tailscale 100.x).
            if (pasv.isSiteLocalAddress() && !control.isSiteLocalAddress()) return true;
            if (pasv.isSiteLocalAddress()
                    && control.isSiteLocalAddress()
                    && !Arrays.equals(pasv.getAddress(), control.getAddress())) {
                return true;
            }
        } catch (UnknownHostException ignored) {
            return true;
        }
        return false;
    }
}
