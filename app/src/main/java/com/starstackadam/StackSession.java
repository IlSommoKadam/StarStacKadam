package com.starstackadam;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Due modalità, con i parametri letti da Impostazioni.
 * Live: anteprima dell'ultimo stack Vespera ({@code *-output.jpg}), senza rimediarlo.
 * Condivisibile: pose singole (o FITS), allineamento + media; PNG + scheda JSON a fine lavoro.
 * Median/Sigma restano nel motore ma non qui: tengono tutti i frame in RAM.
 */
public final class StackSession {
    public enum Mode {
        LIVE, SHARE
    }

    public interface Listener {
        void onProgress(StackProgress progress);
    }

    public static final class Request {
        public final String host;
        public final int port;
        public final List<String> remotePaths;
        public final List<String> localFiles;
        public final boolean backgroundFit;
        public final boolean useLevels;
        public final float levelBlack;
        public final float levelWhite;
        public final float levelGamma;
        public final Mode mode;
        public final String scientificName;
        public final String publicName;
        public final HostSettingsStore.StackOptions profile;
        public final int ftpTimeoutSec;

        public Request(
                String host,
                int port,
                List<String> remotePaths,
                List<String> localFiles,
                boolean backgroundFit) {
            this(host, port, remotePaths, localFiles, backgroundFit, false, 0f, 1f, 1f,
                    Mode.LIVE, "", "");
        }

        public Request(
                String host,
                int port,
                List<String> remotePaths,
                List<String> localFiles,
                boolean backgroundFit,
                boolean useLevels,
                float levelBlack,
                float levelWhite,
                float levelGamma,
                Mode mode,
                String scientificName,
                String publicName) {
            this(host, port, remotePaths, localFiles, backgroundFit, useLevels,
                    levelBlack, levelWhite, levelGamma, mode, scientificName, publicName,
                    null, 12);
        }

        public Request(
                String host,
                int port,
                List<String> remotePaths,
                List<String> localFiles,
                boolean backgroundFit,
                boolean useLevels,
                float levelBlack,
                float levelWhite,
                float levelGamma,
                Mode mode,
                String scientificName,
                String publicName,
                HostSettingsStore.StackOptions profile,
                int ftpTimeoutSec) {
            this.host = host;
            this.port = port;
            this.remotePaths = remotePaths == null ? List.of() : new ArrayList<>(remotePaths);
            this.localFiles = localFiles == null ? List.of() : new ArrayList<>(localFiles);
            this.backgroundFit = backgroundFit;
            this.useLevels = useLevels;
            this.levelBlack = levelBlack;
            this.levelWhite = levelWhite;
            this.levelGamma = levelGamma;
            this.mode = mode == null ? Mode.LIVE : mode;
            this.scientificName = scientificName == null ? "" : scientificName;
            this.publicName = publicName == null ? "" : publicName;
            this.profile = profile;
            this.ftpTimeoutSec = ftpTimeoutSec < 5 ? 12 : ftpTimeoutSec;
        }
    }

    private final FrameCache cache;
    private volatile boolean cancelRequested;
    private volatile boolean backgroundFit;
    private volatile boolean useLevels;
    private volatile float levelBlack = 0f;
    private volatile float levelWhite = 1f;
    private volatile float levelGamma = 1f;

    private ImagePlane lastStacked;
    private int lastPreviewW;
    private int lastPreviewH;

    public StackSession(FrameCache cache) {
        this.cache = cache;
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    public void setBackgroundFit(boolean enabled) {
        backgroundFit = enabled;
    }

    public void setDisplayMode(boolean levels, float black, float white, float gamma) {
        useLevels = levels;
        levelBlack = black;
        levelWhite = white;
        levelGamma = gamma;
    }

    public ImagePlane lastStacked() {
        return lastStacked;
    }

    public void run(Request request, Listener listener) {
        cancelRequested = false;
        backgroundFit = request.backgroundFit;
        useLevels = request.useLevels;
        levelBlack = request.levelBlack;
        levelWhite = request.levelWhite;
        levelGamma = request.levelGamma;

        final boolean share = request.mode == Mode.SHARE;
        final HostSettingsStore.StackOptions opt = request.profile != null
                ? request.profile
                : (share
                ? HostSettingsStore.StackOptions.shareQuality()
                : HostSettingsStore.StackOptions.liveBalanced());
        final int maxEdge = opt.maxEdge;
        final int maxStars = opt.maxStars;
        final int minVotes = opt.minVotes;
        final String objectLabel = objectLabel(request);

        List<String> paths = share
                ? FrameSelect.forStack(request.remotePaths)
                : FrameSelect.forPreview(request.remotePaths);
        String selectNote = FrameSelect.note(request.remotePaths, paths);
        int total = paths.size();
        if (total == 0) {
            listener.onProgress(StackProgress.failed(0, 0, 0, "Nessuna posa nell'oggetto"));
            return;
        }

        if (!share) {
            runVesperaPreview(request, listener, paths.get(0), maxEdge, objectLabel);
            return;
        }

        double[] sum = null;
        int[] count = null;
        int width = 0;
        int height = 0;
        List<StarFinder.Star> refStars = null;
        int unaligned = 0;
        int lastVotes = 0;
        FtpBrowser ftp = null;
        try {
            if (!selectNote.isEmpty()) {
                listener.onProgress(StackProgress.running(
                        0, total, 0, 0, selectNote, null, 0, 0, null));
            }
            for (int i = 0; i < total; i++) {
                if (cancelRequested) {
                    listener.onProgress(StackProgress.cancelled(i, total, unaligned));
                    return;
                }

                String remote = paths.get(i);
                File local = resolveLocal(request, remote);
                if (!cache.hasValid(local)) {
                    if (ftp == null) {
                        ftp = new FtpBrowser();
                        ftp.connect(request.host, request.port, request.ftpTimeoutSec);
                    }
                    listener.onProgress(StackProgress.running(
                            i, total, lastVotes, unaligned,
                            "Download " + baseName(remote) + "…",
                            lastStacked, lastPreviewW, lastPreviewH, null));
                    ftp.retr(remote, local);
                }

                if (cancelRequested) {
                    listener.onProgress(StackProgress.cancelled(i, total, unaligned));
                    return;
                }

                ImagePlane plane;
                try {
                    plane = FrameDecoder.decode(local, maxEdge);
                } catch (Exception decodeFail) {
                    cache.invalidate(local);
                    throw decodeFail;
                }
                if (i == 0) {
                    plane = plane.fitEdge(maxEdge);
                    width = plane.width;
                    height = plane.height;
                    refStars = StarFinder.find(plane, maxStars);
                    sum = new double[width * height * 3];
                    count = new int[width * height];
                    StackCombine.accumulate(sum, count, plane);
                    lastVotes = refStars.size();
                } else {
                    if (plane.width != width || plane.height != height) {
                        plane = plane.resample(width, height);
                    }
                    List<StarFinder.Star> stars = StarFinder.find(plane, maxStars);
                    // Allinea allo stack corrente (stelle più stabili del primo frame).
                    FrameAlign.Transform transform = FrameAlign.match(refStars, stars, width, height);
                    lastVotes = transform.votes;
                    if (transform.votes < minVotes || transform.rms > FrameAlign.MAX_RMS_PX) {
                        unaligned++;
                        String skip = "Esclusa " + baseName(remote)
                                + (transform.votes < minVotes
                                ? " (non allineata)"
                                : String.format(java.util.Locale.ITALY, " (rms %.1f px)", transform.rms));
                        if (!objectLabel.isEmpty()) skip = objectLabel + " — " + skip;
                        listener.onProgress(StackProgress.running(
                                i + 1, total, lastVotes, unaligned, skip,
                                lastStacked, lastPreviewW, lastPreviewH,
                                lastStacked == null ? null : buildPreview(lastStacked).argb));
                        continue;
                    }
                    ImagePlane warped = FrameAlign.warp(plane, transform);
                    StackCombine.accumulate(sum, count, warped);
                }

                ImagePlane mean = StackCombine.fromSum(width, height, sum, count);
                lastStacked = mean;
                lastPreviewW = mean.width;
                lastPreviewH = mean.height;
                // Aggiorna il riferimento sulla media: SNR migliore, meno deriva.
                if (i == 0 || (i % 3) == 0) {
                    List<StarFinder.Star> stackedStars = StarFinder.find(mean, maxStars);
                    if (stackedStars.size() >= 4) refStars = stackedStars;
                }

                String note = "Condivisibile " + (i + 1) + "/" + total;
                if (!objectLabel.isEmpty()) note = objectLabel + " — " + note;
                Preview preview = buildPreview(mean);
                if (preview.fitNote != null && !preview.fitNote.isEmpty()) {
                    note = note + " — " + preview.fitNote;
                }
                if (unaligned > 0) {
                    note = note + " — escluse: " + unaligned;
                }
                listener.onProgress(StackProgress.running(
                        i + 1, total, lastVotes, unaligned, note,
                        mean, mean.width, mean.height, preview.argb));
            }

            if (lastStacked == null) {
                listener.onProgress(StackProgress.failed(0, total, unaligned, "Stack vuoto"));
                return;
            }
            Preview preview = buildPreview(lastStacked);
            int used = total - unaligned;
            String doneNote = "Stack condivisibile — " + used + " pose, lato " + maxEdge;
            if (!objectLabel.isEmpty()) doneNote = objectLabel + " — " + doneNote;
            if (unaligned > 0) {
                doneNote += " — escluse " + unaligned;
            }
            if (preview.fitNote != null && !preview.fitNote.isEmpty()) {
                doneNote = doneNote + " — " + preview.fitNote;
            }
            if (share && request.profile != null && request.profile.useFits) {
                doneNote = doneNote + " — FITS";
            }
            listener.onProgress(StackProgress.finished(
                    total, unaligned, doneNote,
                    lastStacked, lastPreviewW, lastPreviewH, preview.argb));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
            listener.onProgress(StackProgress.failed(0, total, unaligned, msg));
        } finally {
            if (ftp != null) ftp.close();
        }
    }

    /** Ricalcola l'anteprima dall'ultimo stack MEAN (cambio stretch/fondo). */
    public StackProgress redisplay() {
        if (lastStacked == null) {
            return StackProgress.failed(0, 0, 0, "Nessuno stack");
        }
        Preview preview = buildPreview(lastStacked);
        String note = preview.fitNote == null || preview.fitNote.isEmpty()
                ? "Anteprima aggiornata" : preview.fitNote;
        return StackProgress.running(
                0, 0, 0, 0, note,
                lastStacked, lastStacked.width, lastStacked.height, preview.argb);
    }

    private Preview buildPreview(ImagePlane mean) {
        ImagePlane src = mean;
        String fitNote = null;
        if (backgroundFit) {
            BackgroundFit.Fit fit = BackgroundFit.subtract(mean);
            src = fit.image;
            fitNote = fit.note;
        }
        DisplayMap.Render render;
        if (useLevels) {
            render = DisplayMap.levels(src, levelBlack, levelWhite, levelGamma);
        } else {
            render = DisplayMap.autostretch(src);
        }
        return new Preview(render.argb, fitNote);
    }

    private static final class Preview {
        final int[] argb;
        final String fitNote;

        Preview(int[] argb, String fitNote) {
            this.argb = argb;
            this.fitNote = fitNote;
        }
    }

    /** Scarica e mostra un solo JPEG già stackato da Vespera. */
    private void runVesperaPreview(
            Request request,
            Listener listener,
            String remote,
            int maxEdge,
            String objectLabel) {
        FtpBrowser ftp = null;
        try {
            File local = resolveLocal(request, remote);
            long modified = 0L;
            if (!cache.hasValid(local)) {
                ftp = new FtpBrowser();
                ftp.connect(request.host, request.port, request.ftpTimeoutSec);
                listener.onProgress(StackProgress.running(
                        0, 1, 0, 0,
                        "Download " + baseName(remote) + "…",
                        null, 0, 0, null));
                ftp.retr(remote, local);
                modified = peekModified(ftp, remote);
            } else {
                modified = peekModifiedRemote(request, remote);
            }
            if (cancelRequested) {
                listener.onProgress(StackProgress.cancelled(0, 1, 0));
                return;
            }
            ImagePlane plane;
            try {
                plane = FrameDecoder.decode(local, maxEdge).fitEdge(maxEdge);
            } catch (Exception decodeFail) {
                cache.invalidate(local);
                throw decodeFail;
            }
            lastStacked = plane;
            lastPreviewW = plane.width;
            lastPreviewH = plane.height;
            Preview preview = buildPreview(plane);
            String note = "Stack Vespera (ultimo output) — " + baseName(remote);
            String when = SkyObject.formatWhen(modified);
            if (!when.isEmpty()) note = note + " — " + when;
            if (!objectLabel.isEmpty()) note = objectLabel + " — " + note;
            if (preview.fitNote != null && !preview.fitNote.isEmpty()) {
                note = note + " — " + preview.fitNote;
            }
            listener.onProgress(StackProgress.finished(
                    1, 0, note, plane, plane.width, plane.height, preview.argb));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
            listener.onProgress(StackProgress.failed(0, 1, 0, msg));
        } finally {
            if (ftp != null) ftp.close();
        }
    }

    private static long peekModified(FtpBrowser ftp, String remote) {
        if (ftp == null || remote == null || remote.isEmpty()) return 0L;
        try {
            return ftp.modifiedMillis(remote);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** MDTM leggero se il JPEG è già in cache (solo canale di controllo). */
    private static long peekModifiedRemote(Request request, String remote) {
        if (request == null || remote == null || remote.isEmpty()) return 0L;
        try (FtpBrowser ftp = new FtpBrowser()) {
            ftp.connect(request.host, request.port, request.ftpTimeoutSec);
            return ftp.modifiedMillis(remote);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private File resolveLocal(Request request, String remote) {
        int index = request.remotePaths.indexOf(remote);
        if (index >= 0 && index < request.localFiles.size()) {
            String path = request.localFiles.get(index);
            if (path != null && !path.isEmpty()) {
                File given = new File(path);
                if (cache.hasValid(given)) return given;
            }
        }
        return cache.fileFor(request.host, request.port, remote);
    }

    private static String objectLabel(Request request) {
        String sci = request.scientificName == null ? "" : request.scientificName.trim();
        String pub = request.publicName == null ? "" : request.publicName.trim();
        if (sci.isEmpty()) return pub;
        if (pub.isEmpty() || pub.equalsIgnoreCase(sci)) return sci;
        return sci + " · " + pub;
    }

    private static String baseName(String path) {
        if (path == null) return "?";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
