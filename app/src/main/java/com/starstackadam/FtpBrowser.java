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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Client FTP anonymous PASV (Commons Net).
 * Il canale dati usa sempre l'indirizzo IPv4 del controllo: gli helper sul Pi
 * annunciano spesso 0.0.0.0 o l'IP LAN, irraggiungibile via Tailscale.
 */
public final class FtpBrowser implements AutoCloseable {
    public static final class Entry {
        public final String name;
        public final String path;
        public final boolean directory;
        public final long size;
        /** Ultima modifica riportata dal LIST, epoch millis. 0 se assente. */
        public final long modified;

        public Entry(String name, String path, boolean directory, long size) {
            this(name, path, directory, size, 0L);
        }

        public Entry(String name, String path, boolean directory, long size, long modified) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.modified = modified;
        }
    }

    private final FTPClient ftp = new FTPClient();

    public void connect(String host, int port) throws IOException {
        connect(host, port, 12);
    }

    public void connect(String host, int port, int timeoutSec) throws IOException {
        disconnectQuietly();
        String control = host == null ? "" : host.trim();
        if (control.isEmpty()) throw new IOException("Host vuoto");

        int sec = timeoutSec < 5 ? 12 : Math.min(timeoutSec, 120);
        InetAddress address = preferIpv4(control);
        // Il Pi annuncia spesso l'IP LAN o 0.0.0.0 nel PASV. La sessione dati
        // deve tornare sull'indirizzo con cui il controllo è già connesso.
        String dataHost = address.getHostAddress();
        ftp.setConnectTimeout(sec * 1000);
        ftp.setDefaultTimeout(sec * 1000);
        ftp.setDataTimeout(java.time.Duration.ofSeconds(Math.max(30, sec * 4)));
        ftp.setUseEPSVwithIPv4(false);
        ftp.setRemoteVerificationEnabled(false);
        ftp.setIpAddressFromPasvResponse(true);
        ftp.setPassiveNatWorkaroundStrategy(hostname -> dataHost);

        ftp.connect(address, port);
        ftp.setSoTimeout(sec * 1000);
        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply) && reply != 230) {
            disconnectQuietly();
            throw new IOException("FTP rifiutato: " + oneLine(ftp.getReplyString()));
        }
        ftp.enterLocalPassiveMode();
        loginFlexible();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.setKeepAlive(true);
    }

    /**
     * Il controllo FTP risponde e accetta il login.
     * Non dipende dal canale dati: un server acceso non deve risultare offline
     * solo perché il PASV annuncia un IP irraggiungibile.
     */
    public static boolean reachable(String host, int port, int timeoutSec) {
        int sec = timeoutSec < 5 ? 8 : Math.min(timeoutSec, 12);
        try (FtpBrowser ftp = new FtpBrowser()) {
            ftp.connect(host, port, sec);
            ftp.pwd();
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
        int reply = ftp.getReplyCode();
        if (reply >= 400) {
            throw new IOException("LIST fallito: " + oneLine(ftp.getReplyString()));
        }
        if (files != null && files.length > 0) {
            List<Entry> parsed = entriesFromFiles(cwd, files);
            if (!parsed.isEmpty()) return parsed;
        }
        String[] names = ftp.listNames();
        if (names != null && names.length > 0) {
            return entriesFromNames(cwd, names);
        }
        if (files == null) {
            throw new IOException("LIST fallito: " + oneLine(ftp.getReplyString()));
        }
        return new ArrayList<>();
    }

    private List<Entry> entriesFromFiles(String cwd, FTPFile[] files) {
        List<Entry> out = new ArrayList<>();
        for (FTPFile file : files) {
            if (file == null) continue;
            String name = file.getName();
            if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
            String child = joinPath(cwd, name);
            long modified = 0L;
            if (file.getTimestamp() != null) {
                modified = file.getTimestamp().getTimeInMillis();
            }
            out.add(new Entry(name, child, file.isDirectory(), file.getSize(), modified));
        }
        return sortEntries(out);
    }

    /** LIST illeggibile: NLST dà i nomi, il tipo si prova con CWD. */
    private List<Entry> entriesFromNames(String cwd, String[] names) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (String raw : names) {
            if (raw == null) continue;
            String name = raw.trim();
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
            boolean markedDir = name.endsWith("/");
            if (markedDir) name = name.substring(0, name.length() - 1);
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
            String child = joinPath(cwd, name);
            boolean directory = markedDir || isDirectoryPath(cwd, child, name);
            out.add(new Entry(name, child, directory, 0L, 0L));
        }
        return sortEntries(out);
    }

    private boolean isDirectoryPath(String cwd, String child, String name) throws IOException {
        if (isImageName(name) || isFitsName(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json") || lower.endsWith(".txt") || lower.endsWith(".csv")) return false;
        boolean directory = ftp.changeWorkingDirectory(child);
        if (directory) ftp.changeWorkingDirectory(cwd);
        return directory;
    }

    private static List<Entry> sortEntries(List<Entry> out) {
        out.sort(Comparator
                .comparing((Entry e) -> !e.directory)
                .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
        return out;
    }

    private void loginFlexible() throws IOException {
        if (ftp.getReplyCode() == 230) return;
        String[][] attempts = {
                {"anonymous", "starstackadam@"},
                {"anonymous", "anonymous"},
                {"anonymous", ""},
                {"ftp", "ftp"}
        };
        String last = oneLine(ftp.getReplyString());
        for (String[] attempt : attempts) {
            if (!ftp.isConnected()) break;
            try {
                if (ftp.login(attempt[0], attempt[1]) || ftp.getReplyCode() == 230) return;
                last = oneLine(ftp.getReplyString());
            } catch (IOException e) {
                last = e.getMessage() == null ? "login interrotto" : e.getMessage();
                if (!ftp.isConnected()) break;
            }
        }
        try {
            String dir = ftp.printWorkingDirectory();
            if (dir != null && !dir.isEmpty()) return;
        } catch (IOException ignored) {
        }
        disconnectQuietly();
        throw new IOException("Login FTP fallito: " + last);
    }

    private static InetAddress preferIpv4(String host) throws IOException {
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Host sconosciuto: " + host, e);
        }
        if (all == null || all.length == 0) throw new IOException("Host sconosciuto: " + host);
        for (InetAddress address : all) {
            if (address instanceof Inet4Address) return address;
        }
        return all[0];
    }

    private static String oneLine(String reply) {
        if (reply == null) return "";
        return reply.replace('\r', ' ').replace('\n', ' ').trim();
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

}
