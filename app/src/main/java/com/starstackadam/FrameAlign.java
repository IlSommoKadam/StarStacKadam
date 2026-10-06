package com.starstackadam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Allinea un frame al riferimento con una rotazione piccola intorno al centro
 * e una traslazione. Istogramma grezzo per il picco, poi raffinamento a coppie
 * esclusive così i voti contano stelle vere e non doppioni.
 */
public final class FrameAlign {
    public static final class Transform {
        public final float angleDeg;
        public final float dx;
        public final float dy;
        public final int votes;
        /** Scarto quadratico medio delle stelle abbinate, in pixel. */
        public final float rms;

        public Transform(float angleDeg, float dx, float dy, int votes) {
            this(angleDeg, dx, dy, votes, 0f);
        }

        public Transform(float angleDeg, float dx, float dy, int votes, float rms) {
            this.angleDeg = angleDeg;
            this.dx = dx;
            this.dy = dy;
            this.votes = votes;
            this.rms = rms;
        }

        public static Transform identity() {
            return new Transform(0f, 0f, 0f, 0, 0f);
        }
    }

    /** Sopra questa soglia il match ha troppe stelle ma è impreciso: scie. */
    public static final float MAX_RMS_PX = 1.15f;

    private static final float[] ANGLES = {
            0f, -0.5f, 0.5f, -1f, 1f, -1.5f, 1.5f, -2f, 2f,
            -3f, 3f, -4f, 4f, -5f, 5f, -6f, 6f, -8f, 8f,
            -10f, 10f, -12f, 12f, -15f, 15f
    };

    private FrameAlign() {}

    public static Transform match(List<StarFinder.Star> reference, List<StarFinder.Star> moving,
                                  int width, int height) {
        int nRef = Math.min(reference.size(), 40);
        int nMov = Math.min(moving.size(), 40);
        if (nRef < 4 || nMov < 4) return Transform.identity();
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        int win = Math.max(48, Math.min(width, height) / 2);
        int bins = win * 2 + 1;
        int[] hist = new int[bins * bins];
        Transform best = Transform.identity();
        for (float angle : ANGLES) {
            Transform candidate = translationPeak(
                    reference, nRef, moving, nMov, angle, cx, cy, win, bins, hist);
            if (candidate.votes > best.votes) best = candidate;
        }
        if (best.votes < 3) return Transform.identity();
        return refine(reference, nRef, moving, nMov, best, cx, cy);
    }

    /**
     * Compone {@code outer} ∘ {@code inner}: prima {@code inner} (sorgente → guida),
     * poi {@code outer} (guida → riferimento).
     */
    public static Transform compose(Transform outer, Transform inner) {
        if (outer == null) return inner == null ? Transform.identity() : inner;
        if (inner == null) return outer;
        float angle = outer.angleDeg + inner.angleDeg;
        double rad = Math.toRadians(outer.angleDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float dx = outer.dx + inner.dx * cos - inner.dy * sin;
        float dy = outer.dy + inner.dx * sin + inner.dy * cos;
        int votes = Math.min(
                outer.votes <= 0 ? Integer.MAX_VALUE : outer.votes,
                inner.votes <= 0 ? Integer.MAX_VALUE : inner.votes);
        if (votes == Integer.MAX_VALUE) votes = Math.max(outer.votes, inner.votes);
        float rms = Math.max(outer.rms, inner.rms);
        return new Transform(angle, dx, dy, votes, rms);
    }

    private static Transform translationPeak(
            List<StarFinder.Star> reference, int nRef,
            List<StarFinder.Star> moving, int nMov,
            float angleDeg, float cx, float cy,
            int win, int bins, int[] hist) {
        Arrays.fill(hist, 0);
        double rad = Math.toRadians(angleDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float[] rx = new float[nMov];
        float[] ry = new float[nMov];
        for (int j = 0; j < nMov; j++) {
            StarFinder.Star star = moving.get(j);
            float x = star.x - cx;
            float y = star.y - cy;
            rx[j] = x * cos - y * sin + cx;
            ry[j] = x * sin + y * cos + cy;
        }
        for (int i = 0; i < nRef; i++) {
            StarFinder.Star ref = reference.get(i);
            for (int j = 0; j < nMov; j++) {
                float dx = ref.x - rx[j];
                float dy = ref.y - ry[j];
                if (Math.abs(dx) > win || Math.abs(dy) > win) continue;
                int ix = Math.round(dx) + win;
                int iy = Math.round(dy) + win;
                if (ix < 0 || iy < 0 || ix >= bins || iy >= bins) continue;
                for (int oy = -1; oy <= 1; oy++) {
                    int yy = iy + oy;
                    if (yy < 0 || yy >= bins) continue;
                    for (int ox = -1; ox <= 1; ox++) {
                        int xx = ix + ox;
                        if (xx < 0 || xx >= bins) continue;
                        hist[yy * bins + xx] += (ox == 0 && oy == 0) ? 3 : 1;
                    }
                }
            }
        }
        int peak = 0;
        int second = 0;
        int px = win;
        int py = win;
        for (int i = 0; i < hist.length; i++) {
            int votes = hist[i];
            if (votes > peak) {
                second = peak;
                peak = votes;
                px = i % bins;
                py = i / bins;
            } else if (votes > second) {
                second = votes;
            }
        }
        int pairVotes = (peak + 2) / 3;
        if (pairVotes < 3) return Transform.identity();
        if (second > 0 && pairVotes < 6 && peak < second * 1.35f) return Transform.identity();
        return new Transform(angleDeg, px - win, py - win, pairVotes);
    }

    /** Angolo fine ±0.5° e coppie 1:1 entro 1.8 px: dx/dy medi sulle stelle vere. */
    private static Transform refine(
            List<StarFinder.Star> reference, int nRef,
            List<StarFinder.Star> moving, int nMov,
            Transform coarse, float cx, float cy) {
        Transform best = exclusivePairs(
                reference, nRef, moving, nMov, coarse.angleDeg, coarse.dx, coarse.dy, cx, cy);
        for (int step = -5; step <= 5; step++) {
            if (step == 0) continue;
            float angle = coarse.angleDeg + step * 0.1f;
            Transform trial = exclusivePairs(
                    reference, nRef, moving, nMov, angle, coarse.dx, coarse.dy, cx, cy);
            if (trial.votes > best.votes
                    || (trial.votes == best.votes && residual(trial) < residual(best))) {
                best = trial;
            }
        }
        if (best.votes < 3) return Transform.identity();
        return best;
    }

    private static float residual(Transform t) {
        return Math.abs(t.dx) + Math.abs(t.dy) + Math.abs(t.angleDeg) * 0.01f;
    }

    private static Transform exclusivePairs(
            List<StarFinder.Star> reference, int nRef,
            List<StarFinder.Star> moving, int nMov,
            float angleDeg, float seedDx, float seedDy, float cx, float cy) {
        double rad = Math.toRadians(angleDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float[] mx = new float[nMov];
        float[] my = new float[nMov];
        for (int j = 0; j < nMov; j++) {
            StarFinder.Star star = moving.get(j);
            float x = star.x - cx;
            float y = star.y - cy;
            mx[j] = x * cos - y * sin + cx + seedDx;
            my[j] = x * sin + y * cos + cy + seedDy;
        }
        boolean[] used = new boolean[nMov];
        float accX = 0f;
        float accY = 0f;
        int pairs = 0;
        final float maxDist2 = 1.8f * 1.8f;
        for (int i = 0; i < nRef; i++) {
            StarFinder.Star ref = reference.get(i);
            int bestJ = -1;
            float bestD2 = maxDist2;
            for (int j = 0; j < nMov; j++) {
                if (used[j]) continue;
                float dx = ref.x - mx[j];
                float dy = ref.y - my[j];
                float d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestJ = j;
                }
            }
            if (bestJ < 0) continue;
            used[bestJ] = true;
            // Spostamento rispetto alla sola rotazione (senza seed), per il valore assoluto.
            float x = moving.get(bestJ).x - cx;
            float y = moving.get(bestJ).y - cy;
            float rx = x * cos - y * sin + cx;
            float ry = x * sin + y * cos + cy;
            accX += ref.x - rx;
            accY += ref.y - ry;
            pairs++;
        }
        if (pairs < 3) return new Transform(angleDeg, seedDx, seedDy, 0, 99f);
        float dx = accX / pairs;
        float dy = accY / pairs;
        float sum2 = 0f;
        int rmsN = 0;
        java.util.Arrays.fill(used, false);
        for (int i = 0; i < nRef; i++) {
            StarFinder.Star ref = reference.get(i);
            int bestJ = -1;
            float bestD2 = maxDist2;
            for (int j = 0; j < nMov; j++) {
                if (used[j]) continue;
                float x = moving.get(j).x - cx;
                float y = moving.get(j).y - cy;
                float rx = x * cos - y * sin + cx + dx;
                float ry = x * sin + y * cos + cy + dy;
                float ex = ref.x - rx;
                float ey = ref.y - ry;
                float d2 = ex * ex + ey * ey;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestJ = j;
                }
            }
            if (bestJ < 0) continue;
            used[bestJ] = true;
            sum2 += bestD2;
            rmsN++;
        }
        float rms = rmsN > 0 ? (float) Math.sqrt(sum2 / rmsN) : 99f;
        return new Transform(angleDeg, dx, dy, pairs, rms);
    }

    /** Ricampiona {@code src} nel sistema del riferimento. */
    public static ImagePlane warp(ImagePlane src, Transform transform) {
        if (transform.votes <= 0
                && transform.angleDeg == 0f
                && transform.dx == 0f
                && transform.dy == 0f) {
            return src;
        }
        int w = src.width;
        int h = src.height;
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        double rad = Math.toRadians(-transform.angleDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float[] dst = new float[w * h * 3];
        byte[] cover = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float px = x - transform.dx - cx;
                float py = y - transform.dy - cy;
                float mx = px * cos - py * sin + cx;
                float my = px * sin + py * cos + cy;
                if (!sample(src, mx, my, dst, (y * w + x) * 3)) continue;
                cover[y * w + x] = 1;
            }
        }
        return new ImagePlane(w, h, dst, cover);
    }

    private static boolean sample(ImagePlane src, float x, float y, float[] dst, int at) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        if (x0 < 0 || y0 < 0 || x0 + 1 >= src.width || y0 + 1 >= src.height) return false;
        float tx = x - x0;
        float ty = y - y0;
        int i00 = (y0 * src.width + x0) * 3;
        int i10 = i00 + 3;
        int i01 = i00 + src.width * 3;
        int i11 = i01 + 3;
        float w00 = (1f - tx) * (1f - ty);
        float w10 = tx * (1f - ty);
        float w01 = (1f - tx) * ty;
        float w11 = tx * ty;
        for (int c = 0; c < 3; c++) {
            dst[at + c] = w00 * src.rgb[i00 + c]
                    + w10 * src.rgb[i10 + c]
                    + w01 * src.rgb[i01 + c]
                    + w11 * src.rgb[i11 + c];
        }
        return true;
    }

    public static List<StarFinder.Star> rotate(List<StarFinder.Star> stars, float angleDeg,
                                                float cx, float cy) {
        double rad = Math.toRadians(angleDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        List<StarFinder.Star> out = new ArrayList<>(stars.size());
        for (StarFinder.Star star : stars) {
            float x = star.x - cx;
            float y = star.y - cy;
            out.add(new StarFinder.Star(
                    x * cos - y * sin + cx,
                    x * sin + y * cos + cy,
                    star.flux));
        }
        return out;
    }
}
