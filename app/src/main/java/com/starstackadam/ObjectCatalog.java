package com.starstackadam;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scansiona l'FTP e raggruppa le cartelle osservazione.
 * Il nome è quello registrato nella directory. La data di acquisizione
 * si legge dal token {@code -a} ({@code M31-a20240915}) e, sulle cartelle
 * Vaonis, dal prefisso {@code YYYY-MM-DD_…_observation_}.
 * Il nome comune arriva da {@link CommonNames#COMMON_NAME}.
 * <p>
 * {@link #outline} elenca solo i nomi delle cartelle. Pose, FITS, anteprima
 * e JSON si leggono dopo, con {@link #loadDetails}, al tocco sull'oggetto.
 */
public final class ObjectCatalog {
    private static final int MAX_DEPTH = 4;
    private static final int MAX_IMAGE_DEPTH = 6;
    private static final int MAX_OBJECTS = 400;
    private static final int MAX_JSON_BYTES = 200_000;

    private static final Pattern CATALOG = Pattern.compile(
            "(?i)\\b(M\\s*\\d{1,3}|NGC\\s*\\d{1,4}|IC\\s*\\d{1,4}"
                    + "|SH\\s*2\\s*-?\\s*\\d{1,4}|HIP\\s*\\d+|HD\\s*\\d+)\\b");

    /** Data attaccata al token {@code -a} nel nome cartella. */
    private static final Pattern A_DATE = Pattern.compile(
            "(?i)-a[_\\s-]*(\\d{4})[-_]?(\\d{2})[-_]?(\\d{2})(?!\\d)");

    /**
     * Cartella Vaonis: {@code 2023-07-15_05-42-30_observation_M8}
     * (anche {@code acquisition}).
     */
    private static final Pattern OBS_DIR = Pattern.compile(
            "(?i)^(\\d{4}-\\d{2}-\\d{2}).{0,48}?(?:observation|acquisition)[_\\s-]+(.+)$");

    private static final Pattern LEADING_DATE = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})\\b");

    /** {@code 2023-07-15_M31} oppure {@code 20230915_M31}, senza la parola observation. */
    private static final Pattern DATED_OBJECT = Pattern.compile(
            "(?i)^(\\d{4})-?(\\d{2})-?(\\d{2})"
                    + "(?:[_T -]\\d{2}[-:]?\\d{2}(?:[-:]?\\d{2})?)?"
                    + "[_\\s-]+(.+)$");

    /** Dettaglio ancora da leggere, chiave = cartella mostrata in elenco. */
    private static final Map<String, Bucket> PENDING = new HashMap<>();
    /** Sale a ogni elenco nuovo, così una lettura vecchia non cancella il successivo. */
    private static int outlineEpoch;

    private ObjectCatalog() {}

    /**
     * Nomi, date e tipo dalle cartelle, senza entrare nelle sessioni
     * e senza scaricare i JSON. I file già visibili nel LIST restano pronti.
     */
    public static List<SkyObject> outline(FtpBrowser ftp) throws java.io.IOException {
        int token = ++outlineEpoch;
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        walk(ftp, "/", 0, buckets);
        List<SkyObject> objects = new ArrayList<>();
        Map<String, Bucket> pending = new HashMap<>();
        Meta empty = new Meta("", "", null);
        for (Bucket bucket : buckets.values()) {
            boolean files = !bucket.images.isEmpty()
                    || !bucket.derived.isEmpty()
                    || !bucket.fits.isEmpty();
            if (!files && bucket.pendingDirs.isEmpty()) continue;
            boolean later = !bucket.pendingDirs.isEmpty() || bucket.jsonRemote != null;
            if (later) {
                pending.put(bucket.labelPath, bucket);
                objects.add(toSky(bucket, empty, false));
            } else {
                objects.add(toSky(bucket, empty, true));
            }
        }
        objects.sort(Comparator
                .comparing((SkyObject o) -> o.kind.ordinal())
                .thenComparing(o -> o.registeredName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(o -> o.publicName, String.CASE_INSENSITIVE_ORDER));
        if (token == outlineEpoch) {
            PENDING.clear();
            PENDING.putAll(pending);
        }
        return objects;
    }

    /** Pose, FITS, anteprima e JSON di un oggetto scelto in elenco. */
    public static SkyObject loadDetails(FtpBrowser ftp, File tempDir, SkyObject stub)
            throws java.io.IOException {
        if (stub == null) throw new java.io.IOException("Oggetto assente");
        if (stub.detailsReady) return stub;
        int token = outlineEpoch;
        Bucket bucket = PENDING.get(stub.folderPath);
        if (bucket == null) {
            bucket = new Bucket(stub.folderPath);
            bucket.registered = stub.registeredName;
            bucket.dates.addAll(stub.acquisitionDates);
            bucket.pendingDirs.addAll(stub.pendingDirs);
            PENDING.put(stub.folderPath, bucket);
        }
        for (String dir : new ArrayList<>(bucket.pendingDirs)) {
            collectImages(ftp, dir, 0, bucket);
            bucket.pendingDirs.remove(dir);
        }
        if (token != outlineEpoch) throw new java.io.IOException("Elenco aggiornato");
        Meta meta = readMeta(ftp, tempDir, bucket.jsonRemote);
        SkyObject full = toSky(bucket, meta, true);
        if (token == outlineEpoch) PENDING.remove(stub.folderPath);
        return full;
    }

    private static void walk(
            FtpBrowser ftp, String path, int depth, Map<String, Bucket> buckets)
            throws java.io.IOException {
        if (depth > MAX_DEPTH || buckets.size() >= MAX_OBJECTS) return;
        List<FtpBrowser.Entry> entries;
        try {
            entries = ftp.list(path);
        } catch (java.io.IOException e) {
            if (depth == 0) throw e;
            return;
        }
        absorbLoose(path, entries, buckets);
        if (depth >= MAX_DEPTH) return;
        for (FtpBrowser.Entry entry : entries) {
            if (!entry.directory || skipDir(entry.name)) continue;
            if (buckets.size() >= MAX_OBJECTS) return;
            Session session = parseDirName(entry.name);
            if (session != null) {
                noteSession(entry, session, buckets);
                continue;
            }
            if (skipImageDir(entry.name)) continue;
            walk(ftp, entry.path, depth + 1, buckets);
        }
    }

    /** Ricorda la sessione senza elencare i file: quello avviene al tocco. */
    private static void noteSession(
            FtpBrowser.Entry entry,
            Session session,
            Map<String, Bucket> buckets) {
        String key = groupKey(session.registered, entry.path);
        Bucket bucket = bucketFor(buckets, key, session.registered, entry.path);
        if (bucket == null) return;
        bucket.dates.addAll(session.dates);
        if (!bucket.pendingDirs.contains(entry.path)) bucket.pendingDirs.add(entry.path);
    }

    private static void collectImages(FtpBrowser ftp, String path, int depth, Bucket bucket)
            throws java.io.IOException {
        if (depth > MAX_IMAGE_DEPTH) return;
        List<FtpBrowser.Entry> entries = ftp.list(path);
        List<String> lights = new ArrayList<>();
        List<String> derived = new ArrayList<>();
        for (FtpBrowser.Entry entry : entries) {
            if (entry.directory) continue;
            if (FtpBrowser.isFitsName(entry.name)) {
                addImages(bucket.fits, List.of(entry.path));
                rememberModified(bucket, entry);
            } else if (FtpBrowser.isImageName(entry.name)) {
                if (isDerivedName(entry.name)) derived.add(entry.path);
                else lights.add(entry.path);
                rememberModified(bucket, entry);
            } else if (bucket.jsonRemote == null && isMetaName(entry.name)) {
                if (entry.size <= 0 || entry.size <= MAX_JSON_BYTES) bucket.jsonRemote = entry.path;
            }
        }
        addImages(bucket.images, lights);
        addImages(bucket.derived, derived);
        if (depth >= MAX_IMAGE_DEPTH) return;
        for (FtpBrowser.Entry entry : entries) {
            if (!entry.directory || skipDir(entry.name) || skipImageDir(entry.name)) continue;
            addNameDates(bucket, entry.name);
            try {
                collectImages(ftp, entry.path, depth + 1, bucket);
            } catch (java.io.IOException ignored) {
                // Una sottocartella illeggibile non blocca il resto della sessione.
            }
        }
    }

    private static void absorbLoose(
            String path,
            List<FtpBrowser.Entry> entries,
            Map<String, Bucket> buckets) {
        List<String> lights = new ArrayList<>();
        List<String> derived = new ArrayList<>();
        List<String> fits = new ArrayList<>();
        String json = null;
        for (FtpBrowser.Entry entry : entries) {
            if (entry.directory) continue;
            if (FtpBrowser.isFitsName(entry.name)) {
                fits.add(entry.path);
            } else if (FtpBrowser.isImageName(entry.name)) {
                if (isDerivedName(entry.name)) derived.add(entry.path);
                else lights.add(entry.path);
            } else if (json == null && isMetaName(entry.name)) {
                if (entry.size <= 0 || entry.size <= MAX_JSON_BYTES) json = entry.path;
            }
        }
        if (lights.isEmpty() && derived.isEmpty() && fits.isEmpty()) return;
        String label = labelPathFor(path);
        if (genericFolder(baseName(label))) return;
        String registered = baseName(label).replace('_', ' ').replaceAll("\\s+", " ").trim();
        Bucket bucket = bucketFor(buckets, groupKey(registered, label), registered, label);
        if (bucket == null) return;
        for (FtpBrowser.Entry entry : entries) {
            if (!entry.directory) rememberModified(bucket, entry);
        }
        addImages(bucket.images, lights);
        addImages(bucket.derived, derived);
        addImages(bucket.fits, fits);
        if (bucket.jsonRemote == null) bucket.jsonRemote = json;
        addNameDates(bucket, registered);
        addNameDates(bucket, baseName(path));
    }

    private static Bucket bucketFor(
            Map<String, Bucket> buckets, String key, String registered, String path) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= MAX_OBJECTS) return null;
            bucket = new Bucket(path);
            buckets.put(key, bucket);
        }
        rememberName(bucket, registered);
        return bucket;
    }

    private static void rememberName(Bucket bucket, String registered) {
        if (registered == null) return;
        String cleaned = registered.replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return;
        if (bucket.registered == null || cleaned.length() < bucket.registered.length()) {
            bucket.registered = cleaned;
        }
    }

    private static void addImages(List<String> dest, Collection<String> images) {
        for (String image : images) {
            if (!dest.contains(image)) dest.add(image);
        }
    }

    private static SkyObject toSky(Bucket bucket, Meta meta, boolean ready) {
        String registered = bucket.registered == null ? baseName(bucket.labelPath) : bucket.registered;
        Parsed parsed = parseFolder(registered);
        String scientific = firstNonEmpty(meta.scientific, parsed.scientific);
        if (scientific.isEmpty()) {
            String token = catalogToken(registered);
            scientific = token.isEmpty() ? registered : prettyCatalog(token);
        }
        String common = CommonNames.of(registered);
        if (common.isEmpty()) common = CommonNames.of(scientific);
        if (!meta.pub.isEmpty()) common = meta.pub;

        SkyObject.Kind kind = meta.kind != null ? meta.kind : CommonNames.kindFor(registered);
        if (kind == null) kind = CommonNames.kindFor(scientific);
        if (kind == null) kind = parsed.kind;
        if (kind == SkyObject.Kind.OTHER) {
            SkyObject.Kind hinted = kindFromText(registered + " " + common + " " + scientific);
            if (hinted != SkyObject.Kind.OTHER) kind = hinted;
        }
        if (scientific.isEmpty()) scientific = registered;
        String pub = common.isEmpty() ? scientific : common;

        List<String> ordered = new ArrayList<>(bucket.dates);
        Collections.sort(ordered);
        List<String> stackable = List.of();
        List<String> fits = List.of();
        String preview = "";
        long previewAt = 0L;
        List<String> pending = List.of();
        if (ready) {
            stackable = FrameSelect.forStack(mergePaths(bucket.images, bucket.derived));
            fits = new ArrayList<>(bucket.fits);
            fits.sort(String.CASE_INSENSITIVE_ORDER);
            preview = FrameSelect.lastDerived(bucket.derived);
            if (preview.isEmpty() && !stackable.isEmpty()) {
                List<String> one = FrameSelect.forPreview(stackable);
                preview = one.isEmpty() ? "" : one.get(0);
            }
            previewAt = preview.isEmpty() ? 0L : bucket.modified.getOrDefault(preview, 0L);
        } else {
            pending = new ArrayList<>(bucket.pendingDirs);
        }
        return new SkyObject(
                kind,
                pretty(registered),
                pretty(scientific),
                pretty(pub),
                bucket.labelPath,
                stackable,
                preview,
                previewAt,
                fits,
                ordered,
                ready,
                pending);
    }

    private static void rememberModified(Bucket bucket, FtpBrowser.Entry entry) {
        if (bucket == null || entry == null || entry.path == null || entry.path.isEmpty()) return;
        if (entry.modified > 0L) bucket.modified.put(entry.path, entry.modified);
    }

    private static List<String> mergePaths(List<String> lights, List<String> derived) {
        List<String> all = new ArrayList<>(lights.size() + derived.size());
        all.addAll(lights);
        all.addAll(derived);
        return all;
    }

    /** Nome oggetto e date ricavate dal solo nome cartella. Null se non è una sessione. */
    static Session parseDirName(String name) {
        if (name == null || name.isBlank()) return null;
        LinkedHashSet<String> dates = new LinkedHashSet<>();
        collectADates(name, dates);
        Matcher obs = OBS_DIR.matcher(name.trim());
        if (obs.matches()) {
            String lead = iso(obs.group(1).substring(0, 4), obs.group(1).substring(5, 7), obs.group(1).substring(8, 10));
            if (!lead.isEmpty()) dates.add(lead);
            String registered = stripDateTokens(obs.group(2));
            if (registered.isEmpty()) return null;
            return new Session(registered, dates);
        }
        Matcher dated = DATED_OBJECT.matcher(name.trim());
        if (dated.matches()) {
            String lead = iso(dated.group(1), dated.group(2), dated.group(3));
            String registered = stripDateTokens(dated.group(4));
            if (!lead.isEmpty() && !registered.isEmpty() && !genericFolder(registered)) {
                dates.add(lead);
                return new Session(registered, dates);
            }
        }
        if (!dates.isEmpty()) {
            String registered = stripDateTokens(name);
            if (registered.isEmpty()) registered = name.trim();
            return new Session(registered, dates);
        }
        return null;
    }

    private static void addNameDates(Bucket bucket, String name) {
        collectADates(name, bucket.dates);
        Matcher lead = LEADING_DATE.matcher(name == null ? "" : name.trim());
        if (lead.find()) {
            String iso = iso(lead.group(1), lead.group(2), lead.group(3));
            if (!iso.isEmpty() && looksLikeSession(name)) bucket.dates.add(iso);
        }
    }

    private static boolean looksLikeSession(String name) {
        return name != null && OBS_DIR.matcher(name.trim()).matches();
    }

    private static void collectADates(String name, Collection<String> into) {
        if (name == null) return;
        Matcher matcher = A_DATE.matcher(name);
        while (matcher.find()) {
            String iso = iso(matcher.group(1), matcher.group(2), matcher.group(3));
            if (!iso.isEmpty()) into.add(iso);
        }
    }

    private static String stripDateTokens(String raw) {
        String text = raw == null ? "" : raw;
        text = A_DATE.matcher(text).replaceAll(" ");
        text = text.replaceFirst(
                "(?i)^\\d{4}-\\d{2}-\\d{2}(?:[_T ]\\d{2}[-:]\\d{2}(?:[-:]\\d{2})?)?[_\\s-]*", "");
        text = text.replaceFirst("(?i)^(?:observation|acquisition)[_\\s-]+", "");
        return text.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String iso(String year, String month, String day) {
        try {
            int y = Integer.parseInt(year);
            int m = Integer.parseInt(month);
            int d = Integer.parseInt(day);
            if (y < 1990 || y > 2100 || m < 1 || m > 12 || d < 1 || d > 31) return "";
            return String.format(Locale.US, "%04d-%02d-%02d", y, m, d);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String groupKey(String registered, String fallbackPath) {
        String cat = catalogKey(registered);
        if (!cat.isEmpty()) return cat;
        String folded = key(registered);
        if (!folded.isEmpty()) return folded;
        return fallbackPath == null ? "" : fallbackPath;
    }

    private static Parsed parseFolder(String folder) {
        String raw = folder == null ? "" : folder.trim();
        String scientific = "";
        StringBuilder rest = new StringBuilder();
        for (String chunk : splitChunks(raw)) {
            String token = catalogToken(chunk);
            if (!token.isEmpty() && scientific.isEmpty()) {
                scientific = prettyCatalog(token);
                String leftover = chunk.replace(token, " ").replaceAll("[_-]+", " ").trim();
                if (!leftover.isEmpty() && catalogToken(leftover).isEmpty()) {
                    if (rest.length() > 0) rest.append(' ');
                    rest.append(leftover);
                }
            } else if (!chunk.isBlank()) {
                if (rest.length() > 0) rest.append(' ');
                rest.append(chunk.trim().replace('_', ' '));
            }
        }
        String pub = rest.toString().replaceAll("\\s+", " ").trim();
        SkyObject.Kind kind = kindFromText(raw + " " + pub);
        return new Parsed(scientific, pub, kind);
    }

    private static String[] splitChunks(String raw) {
        String[] bySep = raw.split("\\s+[\\-–—|]\\s+|\\s*\\|\\s*");
        if (bySep.length > 1) return bySep;
        if (raw.contains("_")) return raw.split("_+");
        return new String[] {raw};
    }

    private static Meta readMeta(FtpBrowser ftp, File tempDir, String remote) {
        Meta empty = new Meta("", "", null);
        if (remote == null || remote.isEmpty() || tempDir == null) return empty;
        File tmp = null;
        try {
            if (!tempDir.exists() && !tempDir.mkdirs()) return empty;
            tmp = File.createTempFile("oggetto", ".json", tempDir);
            ftp.retr(remote, tmp);
            if (tmp.length() <= 0 || tmp.length() > MAX_JSON_BYTES) return empty;
            String text = readUtf8(tmp).trim();
            if (text.isEmpty() || text.charAt(0) != '{') return empty;
            JSONObject json = new JSONObject(text);
            String scientific = firstJson(json,
                    "scientificName", "scientific", "catalogName", "designation");
            String pub = firstJson(json,
                    "publicName", "commonName", "displayName", "public");
            String objectName = firstJson(json, "objectName", "name");
            if (scientific.isEmpty() && !objectName.isEmpty() && !catalogToken(objectName).isEmpty()) {
                scientific = prettyCatalog(catalogToken(objectName));
            } else if (pub.isEmpty()) {
                pub = objectName;
            }
            String type = firstJson(json, "kind", "objectType", "type", "category");
            SkyObject.Kind kind = type.isEmpty() ? null : kindFromText(type);
            if (kind == SkyObject.Kind.OTHER && !type.isEmpty()
                    && kindFromText(type) == SkyObject.Kind.OTHER
                    && !type.toLowerCase(Locale.ROOT).contains("altro")
                    && !type.toLowerCase(Locale.ROOT).contains("other")
                    && !type.toLowerCase(Locale.ROOT).contains("cluster")) {
                kind = null;
            }
            return new Meta(scientific, pub, kind);
        } catch (Exception ignored) {
            return empty;
        } finally {
            if (tmp != null && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static String readUtf8(File file) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                if (buf.size() + n > MAX_JSON_BYTES) break;
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String firstJson(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }

    private static String labelPathFor(String imageDir) {
        String name = baseName(imageDir);
        if (!genericFolder(name)) return imageDir;
        String parent = FtpBrowser.parentPath(imageDir);
        if ("/".equals(parent) || genericFolder(baseName(parent))) return imageDir;
        return parent;
    }

    private static boolean genericFolder(String name) {
        String n = key(name);
        if (n.isEmpty()) return true;
        if (!catalogKey(name).isEmpty()) return false;
        if (n.matches("\\d{8}.*")) return true;
        if (n.contains("imagesinitial") || n.contains("imagesadjust") || n.contains("pointing")) {
            return true;
        }
        return n.equals("user") || n.equals("users") || n.equals("lights") || n.equals("light")
                || n.equals("jpeg") || n.equals("jpg") || n.equals("png") || n.equals("images")
                || n.equals("image") || n.equals("pose") || n.equals("frames") || n.equals("frame")
                || n.equals("raw") || n.equals("export") || n.equals("stacked") || n.equals("stack")
                || n.equals("session") || n.equals("sessions") || n.equals("dcim")
                || n.equals("camera") || n.equals("pictures");
    }

    private static boolean isDerivedName(String name) {
        return FrameSelect.isDerivedName(name);
    }

    private static boolean isMetaName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("oggetto.json") || n.equals("object.json")
                || n.equals("target.json") || n.equals("meta.json");
    }

    private static boolean skipDir(String name) {
        if (name == null || name.isEmpty()) return true;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith(".") || n.equals("android") || n.equals("lost.dir")
                || n.equals("system volume information") || n.equals("$recycle.bin")
                || n.equals("thumbnails") || n.equals("system") || n.equals("expert mode");
    }

    private static boolean skipImageDir(String name) {
        String n = key(name);
        if (n.isEmpty()) return true;
        return n.contains("pointing") || n.contains("dark") || n.contains("expertmode")
                || n.equals("expert") || n.equals("flat") || n.equals("flats")
                || n.equals("bias") || n.equals("calibration") || n.equals("calib");
    }

    static SkyObject.Kind kindFromText(String text) {
        String n = key(text);
        if (n.contains("galass") || n.contains("galaxy") || n.contains("galaxie")) {
            return SkyObject.Kind.GALAXY;
        }
        if (n.contains("nebulos") || n.contains("nebula")) return SkyObject.Kind.NEBULA;
        if (n.contains("stella") || n.equals("star") || n.startsWith("star") || n.contains("star")) {
            if (n.contains("ammas") || n.contains("cluster")) return SkyObject.Kind.OTHER;
            return SkyObject.Kind.STAR;
        }
        return SkyObject.Kind.OTHER;
    }

    static String catalogKey(String text) {
        String token = catalogToken(text);
        if (token.isEmpty()) {
            String compact = text == null ? "" : text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            if (compact.matches("M\\d{1,3}") || compact.matches("NGC\\d{1,4}")
                    || compact.matches("IC\\d{1,4}") || compact.matches("SH2\\d{1,4}")
                    || compact.matches("HIP\\d+") || compact.matches("HD\\d+")) {
                return compact.toLowerCase(Locale.ROOT);
            }
            return "";
        }
        return token.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    static String key(String text) {
        if (text == null) return "";
        String n = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ITALY).replaceAll("[^a-z0-9]", "");
    }

    static String prettyCatalog(String token) {
        String g = token.toUpperCase(Locale.ROOT).replaceAll("[\\s_]+", "");
        g = g.replace("SH2-", "SH2");
        if (g.startsWith("NGC")) return "NGC " + g.substring(3);
        if (g.startsWith("IC") && g.length() > 2 && Character.isDigit(g.charAt(2))) {
            return "IC " + g.substring(2);
        }
        if (g.startsWith("SH2")) return "Sh2-" + g.substring(3).replace("-", "");
        if (g.startsWith("HIP")) return "HIP " + g.substring(3);
        if (g.startsWith("HD") && g.length() > 2 && Character.isDigit(g.charAt(2))) {
            return "HD " + g.substring(2);
        }
        if (g.startsWith("M") && g.length() > 1 && Character.isDigit(g.charAt(1))) {
            return "M " + g.substring(1);
        }
        return token.trim();
    }

    private static String catalogToken(String text) {
        if (text == null) return "";
        Matcher m = CATALOG.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static String pretty(String text) {
        return text == null ? "" : text.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b == null ? "" : b.trim();
    }

    private static String baseName(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return "";
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    static final class Session {
        final String registered;
        final LinkedHashSet<String> dates;

        Session(String registered, Collection<String> dates) {
            this.registered = registered;
            this.dates = new LinkedHashSet<>();
            if (dates != null) this.dates.addAll(dates);
        }
    }

    private static final class Parsed {
        final String scientific;
        final String pub;
        final SkyObject.Kind kind;

        Parsed(String scientific, String pub, SkyObject.Kind kind) {
            this.scientific = scientific;
            this.pub = pub;
            this.kind = kind;
        }
    }

    private static final class Meta {
        final String scientific;
        final String pub;
        final SkyObject.Kind kind;

        Meta(String scientific, String pub, SkyObject.Kind kind) {
            this.scientific = scientific == null ? "" : scientific;
            this.pub = pub == null ? "" : pub;
            this.kind = kind;
        }
    }

    private static final class Bucket {
        final String labelPath;
        String registered;
        final List<String> images = new ArrayList<>();
        final List<String> derived = new ArrayList<>();
        final List<String> fits = new ArrayList<>();
        final LinkedHashSet<String> dates = new LinkedHashSet<>();
        final HashMap<String, Long> modified = new HashMap<>();
        final List<String> pendingDirs = new ArrayList<>();
        String jsonRemote;

        Bucket(String labelPath) {
            this.labelPath = labelPath;
        }
    }
}
