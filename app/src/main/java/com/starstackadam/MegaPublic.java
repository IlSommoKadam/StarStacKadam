package com.starstackadam;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Scarica file da un link pubblico Mega (file o cartella con chiave), come ESA Meter. */
public final class MegaPublic {
    private static final Pattern FILE_URL = Pattern.compile(
            "(?:https?://)?(?:www\\.)?mega(?:\\.co)?\\.nz/(?:file/|#!?)([A-Za-z0-9_-]+)(?:[!#]|%23)([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLDER_URL = Pattern.compile(
            "(?:https?://)?(?:www\\.)?mega(?:\\.co)?\\.nz/(?:folder/|#F!)([A-Za-z0-9_-]+)(?:[!#]|%23)([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);

    private MegaPublic() {}

    public static boolean isMegaFileUrl(String url) {
        return url != null && FILE_URL.matcher(url.trim()).find();
    }

    public static boolean isPublicFolderUrl(String url) {
        return url != null && FOLDER_URL.matcher(url.trim()).find();
    }

    public static boolean isPublicLink(String url) {
        return isMegaFileUrl(url) || isPublicFolderUrl(url);
    }

    public static byte[] downloadFolderFile(String url, String fileName) throws Exception {
        Matcher match = FOLDER_URL.matcher(url == null ? "" : url.trim());
        if (!match.find()) throw new IllegalArgumentException("Link degli aggiornamenti non valido");
        String folderId = match.group(1);
        byte[] shareKey = base64UrlBytes(match.group(2));
        if (shareKey.length != 16) throw new IllegalArgumentException("Chiave del link non valida");
        String wanted = fileName == null ? "" : fileName.trim();
        JSONArray nodes = apiFolder(folderId);
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null || node.optInt("t") != 0) continue;
            String encryptedKey = node.optString("k");
            int colon = encryptedKey.indexOf(':');
            if (colon >= 0) encryptedKey = encryptedKey.substring(colon + 1);
            if (encryptedKey.isBlank()) continue;
            int[] keyWords;
            try {
                keyWords = bytesToA32(aesEcbDecrypt(base64UrlBytes(encryptedKey), shareKey));
            } catch (Exception ignored) {
                continue;
            }
            if (keyWords.length < 8) continue;
            int[] aesWords = new int[] {
                    keyWords[0] ^ keyWords[4],
                    keyWords[1] ^ keyWords[5],
                    keyWords[2] ^ keyWords[6],
                    keyWords[3] ^ keyWords[7]
            };
            String name = fileNameOf(node.optString("a"), a32ToBytes(aesWords));
            if (!name.equalsIgnoreCase(wanted)) continue;
            int[] iv = new int[] {keyWords[4], keyWords[5], 0, 0};
            JSONObject info = apiNode(folderId, node.optString("h"));
            String downloadUrl = info.optString("g");
            if (downloadUrl.isBlank()) throw new IllegalStateException("Download non disponibile");
            byte[] encrypted = httpBytes(downloadUrl, 120_000);
            byte[] plain = aesCtrDecrypt(encrypted, a32ToBytes(aesWords), a32ToBytes(iv));
            int size = (int) info.optLong("s", plain.length);
            if (size < 0) size = 0;
            if (size > plain.length) size = plain.length;
            return Arrays.copyOf(plain, size);
        }
        throw new IllegalStateException("Nel link pubblico non c'è " + wanted);
    }

    public static byte[] downloadBytes(String url) throws Exception {
        if (isPublicFolderUrl(url)) {
            throw new IllegalArgumentException("Il link è di una cartella: serve il nome del file.");
        }
        Matcher match = FILE_URL.matcher(url == null ? "" : url.trim());
        if (!match.find()) throw new IllegalArgumentException("Link del file non valido");
        String fileId = match.group(1);
        int[] keyWords = bytesToA32(base64UrlBytes(match.group(2)));
        if (keyWords.length != 8 && keyWords.length != 4) {
            throw new IllegalArgumentException("Chiave del file non valida");
        }
        int[] aesWords;
        int[] iv;
        if (keyWords.length == 8) {
            aesWords = new int[] {
                    keyWords[0] ^ keyWords[4],
                    keyWords[1] ^ keyWords[5],
                    keyWords[2] ^ keyWords[6],
                    keyWords[3] ^ keyWords[7]
            };
            iv = new int[] {keyWords[4], keyWords[5], 0, 0};
        } else {
            aesWords = keyWords;
            iv = new int[] {0, 0, 0, 0};
        }
        JSONObject info = apiGet(fileId);
        String downloadUrl = info.optString("g");
        if (downloadUrl.isBlank()) throw new IllegalStateException("Download non disponibile");
        byte[] encrypted = httpBytes(downloadUrl, 120_000);
        byte[] plain = aesCtrDecrypt(encrypted, a32ToBytes(aesWords), a32ToBytes(iv));
        int size = (int) info.optLong("s", plain.length);
        if (size < 0) size = 0;
        if (size > plain.length) size = plain.length;
        return Arrays.copyOf(plain, size);
    }

    public static JSONObject downloadJson(String url) throws Exception {
        String text = new String(downloadBytes(url), StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        return new JSONObject(text);
    }

    private static String fileNameOf(String attr, byte[] key) {
        if (attr == null || attr.isBlank()) return "";
        try {
            byte[] plain = aesCbcDecrypt(base64UrlBytes(attr), key);
            int end = plain.length;
            while (end > 0 && plain[end - 1] == 0) end--;
            String text = new String(plain, 0, end, StandardCharsets.UTF_8);
            if (!text.startsWith("MEGA")) return "";
            return new JSONObject(text.substring(4)).optString("n");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JSONArray apiFolder(String folderId) throws Exception {
        JSONArray body = new JSONArray().put(new JSONObject().put("a", "f").put("c", 1).put("r", 1));
        Object first = apiPost("https://g.api.mega.co.nz/cs?n=" + folderId, body).get(0);
        if (first instanceof Number && ((Number) first).intValue() < 0) {
            throw new IllegalStateException("Cartella non disponibile (" + ((Number) first).intValue() + ")");
        }
        if (!(first instanceof JSONObject)) throw new IllegalStateException("Risposta non valida");
        JSONArray nodes = ((JSONObject) first).optJSONArray("f");
        return nodes == null ? new JSONArray() : nodes;
    }

    private static JSONObject apiNode(String folderId, String nodeId) throws Exception {
        JSONArray body = new JSONArray().put(
                new JSONObject().put("a", "g").put("g", 1).put("ssl", 2).put("n", nodeId));
        Object first = apiPost("https://g.api.mega.co.nz/cs?n=" + folderId, body).get(0);
        if (first instanceof Number && ((Number) first).intValue() < 0) {
            throw new IllegalStateException("File non disponibile (" + ((Number) first).intValue() + ")");
        }
        if (!(first instanceof JSONObject)) throw new IllegalStateException("Risposta non valida");
        return (JSONObject) first;
    }

    private static JSONObject apiGet(String fileId) throws Exception {
        JSONArray body = new JSONArray().put(
                new JSONObject().put("a", "g").put("g", 1).put("ssl", 2).put("p", fileId));
        Object first = apiPost("https://g.api.mega.co.nz/cs", body).get(0);
        if (first instanceof Number && ((Number) first).intValue() < 0) {
            throw new IllegalStateException("File non disponibile (" + ((Number) first).intValue() + ")");
        }
        if (!(first instanceof JSONObject)) throw new IllegalStateException("Risposta non valida");
        return (JSONObject) first;
    }

    private static JSONArray apiPost(String url, JSONArray body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(20_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "StarStacKadam");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.getOutputStream().write(payload);
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readText(stream);
        if (code < 200 || code >= 300) throw new IllegalStateException("Servizio aggiornamenti " + code);
        return new JSONArray(text);
    }

    private static byte[] httpBytes(String url, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "StarStacKadam");
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] data = readBytes(stream);
        if (code < 200 || code >= 300) throw new IllegalStateException("Download " + code);
        return data;
    }

    private static String readText(InputStream stream) throws Exception {
        return new String(readBytes(stream), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream stream) throws Exception {
        if (stream == null) return new byte[0];
        try (InputStream in = stream) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[256 * 1024];
            int n;
            while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    private static byte[] aesEcbDecrypt(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    private static byte[] aesCbcDecrypt(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        return cipher.doFinal(data);
    }

    private static byte[] aesCtrDecrypt(byte[] data, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private static byte[] base64UrlBytes(String value) {
        String text = value.replace('-', '+').replace('_', '/');
        while (text.length() % 4 != 0) text += "=";
        return Base64.decode(text, Base64.DEFAULT);
    }

    private static int[] bytesToA32(byte[] raw) {
        byte[] padded = Arrays.copyOf(raw, ((raw.length + 3) / 4) * 4);
        ByteBuffer buffer = ByteBuffer.wrap(padded).order(ByteOrder.BIG_ENDIAN);
        int[] words = new int[padded.length / 4];
        for (int i = 0; i < words.length; i++) words[i] = buffer.getInt();
        return words;
    }

    private static byte[] a32ToBytes(int[] words) {
        ByteBuffer buffer = ByteBuffer.allocate(words.length * 4).order(ByteOrder.BIG_ENDIAN);
        for (int word : words) buffer.putInt(word);
        return buffer.array();
    }

    static String facing(String message) {
        if (message == null || message.isBlank()) return "Controllo non riuscito";
        String cleaned = message.replaceAll("(?i)\\bmega\\b", "").replaceAll("\\s{2,}", " ").trim();
        return cleaned.isEmpty() ? "Controllo non riuscito" : cleaned;
    }
}
