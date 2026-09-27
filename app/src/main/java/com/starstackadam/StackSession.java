package com.starstackadam;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Stack MEAN streaming: frame 0 = fitEdge(1280) come canvas;
 * successivi = resample stesso WxH → stars → align → warp → accumulate.
 * BackgroundFit solo se richiesto; Median/Sigma mai usati qui.
 */
public final class StackSession {
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

        public Request(
                String host,
                int port,
                List<String> remotePaths,
                List<String> localFiles,
                boolean backgroundFit) {
            this(host, port, remotePaths, localFiles, backgroundFit, false, 0f, 1f, 1f);
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
                float levelGamma) {
            this.host = host;
            this.port = port;
            this.remotePaths = remotePaths == null ? List.of() : new ArrayList<>(remotePaths);
            this.localFiles = localFiles == null ? List.of() : new ArrayList<>(localFiles);
            this.backgroundFit = backgroundFit;
            this.useLevels = useLevels;
            this.levelBlack = levelBlack;
            this.levelWhite = levelWhite;
            this.levelGamma = levelGamma;
        }
    }

    private static final int MAX_EDGE = 1280;
    private static final int MAX_STARS = 40;

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

        int total = request.remotePaths.size();
        if (total == 0) {
            listener.onProgress(StackProgress.failed(0, 0, 0, "Nessun frame selezionato"));
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
                        ftp.connect(request.host, request.port);
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

                ImagePlane plane = FrameDecoder.decode(local);
                if (i == 0) {
                    plane = plane.fitEdge(MAX_EDGE);
                    width = plane.width;
                    height = plane.height;
                    refStars = StarFinder.find(plane, MAX_STARS);
                    sum = new double[width * height * 3];
                    count = new int[width * height];
                    StackCombine.accumulate(sum, count, plane);
                    lastVotes = refStars.size();
                } else {
                    if (plane.width != width || plane.height != height) {
                        plane = plane.resample(width, height);
                    }
                    List<StarFinder.Star> stars = StarFinder.find(plane, MAX_STARS);
                    FrameAlign.Transform transform = FrameAlign.match(refStars, stars, width, height);
                    lastVotes = transform.votes;
                    if (transform.votes < 4) {
                        unaligned++;
                    }
                    ImagePlane warped = FrameAlign.warp(plane, transform);
                    StackCombine.accumulate(sum, count, warped);
                }

                ImagePlane mean = StackCombine.fromSum(width, height, sum, count);
                lastStacked = mean;
                lastPreviewW = mean.width;
                lastPreviewH = mean.height;

                String note = "MEAN " + (i + 1) + "/" + total;
                Preview preview = buildPreview(mean);
                if (preview.fitNote != null && !preview.fitNote.isEmpty()) {
                    note = note + " — " + preview.fitNote;
                }
                if (unaligned > 0) {
                    note = note + " — non allineati: " + unaligned;
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
            String doneNote = "Stack preview (JPEG) MEAN — " + total + " frame";
            if (unaligned > 0) doneNote += " — non allineati: " + unaligned;
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

    private static String baseName(String path) {
        if (path == null) return "?";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
