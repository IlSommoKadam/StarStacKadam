package com.starstackadam;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Cache locale sotto {@code getCacheDir()/frames}. */
public final class FrameCache {
    private final File root;

    public FrameCache(Context context) {
        root = new File(context.getCacheDir(), "frames");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
    }

    public File root() {
        return root;
    }

    public File fileFor(String host, int port, String remotePath) {
        String key = host + "|" + port + "|" + remotePath;
        String hash = sha1Hex(key);
        String base = baseName(remotePath);
        String safe = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 48) safe = safe.substring(safe.length() - 48);
        return new File(root, hash.substring(0, 12) + "_" + safe);
    }

    public boolean hasValid(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    /** Elimina un file di cache corrotto / non decodificabile. */
    public void invalidate(File file) {
        if (file == null) return;
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String baseName(String path) {
        if (path == null || path.isEmpty()) return "frame.bin";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? "frame.bin" : name;
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
