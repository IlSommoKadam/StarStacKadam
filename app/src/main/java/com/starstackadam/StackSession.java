package com.starstackadam;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Due modalità, con i parametri letti da Impostazioni.
 * Live (prestazioni): MEAN streaming, lato più corto, le pose deboli restano.
 * Condivisibile (qualità): lato più lungo, le pose sotto soglia si escludono;
 * il PNG + scheda JSON si salva a fine lavoro.
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

        int total = request.remotePaths.size();
        if (total == 0) {
            listener.onProgress(StackProgress.failed(0, 0, 0, "Nessuna posa nell'oggetto"));
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
            for (int i = 0; i < total; i++) {
                if (cancelRequested) {
                    listener.onProgress(StackProgress.cancelled(i, total, unaligned));
                    return;
                }

                String remote = request.remotePaths.get(i);
                File local = resolveLocal(request, i, remote);
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
                    FrameAlign.Transform transform = FrameAlign.match(refStars, stars, width, height);
                    lastVotes = transform.votes;
                    if (transform.votes < minVotes) {
                        unaligned++;
                        if (opt.rejectUnaligned) {
                            String skip = "Esclusa " + baseName(remote) + " (non allineata)";
                            if (!objectLabel.isEmpty()) skip = objectLabel + " — " + skip;
                            listener.onProgress(StackProgress.running(
                                    i + 1, total, lastVotes, unaligned, skip,
                                    lastStacked, lastPreviewW, lastPreviewH,
                                    lastStacked == null ? null : buildPreview(lastStacked).argb));
                            continue;
                        }
                    }
                    ImagePlane warped = FrameAlign.warp(plane, transform);
                    StackCombine.accumulate(sum, count, warped);
                }

                ImagePlane mean = StackCombine.fromSum(width, height, sum, count);
                lastStacked = mean;
                lastPreviewW = mean.width;
                lastPreviewH = mean.height;

                String note = (share ? "Condivisibile " : "Live ") + (i + 1) + "/" + total;
                if (!objectLabel.isEmpty()) note = objectLabel + " — " + note;
                Preview preview = buildPreview(mean);
                if (preview.fitNote != null && !preview.fitNote.isEmpty()) {
                    note = note + " — " + preview.fitNote;
                }
                if (unaligned > 0) {
                    note = note + (opt.rejectUnaligned ? " — escluse: " : " — non allineate: ") + unaligned;
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
            int used = opt.rejectUnaligned ? total - unaligned : total;
            String doneNote = share
                    ? "Stack condivisibile — " + used + " pose, lato " + maxEdge
                    : "Visione live — " + used + " pose, lato " + maxEdge;
            if (!objectLabel.isEmpty()) doneNote = objectLabel + " — " + doneNote;
            if (unaligned > 0) {
                doneNote += opt.rejectUnaligned
                        ? " — escluse " + unaligned
                        : " — non allineate " + unaligned;
            }
            if (preview.fitNote != null && !preview.fitNote.isEmpty()) {
                doneNote = doneNote + " — " + preview.fitNote;
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

    private File resolveLocal(Request request, int index, String remote) {
        if (index < request.localFiles.size()) {
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
