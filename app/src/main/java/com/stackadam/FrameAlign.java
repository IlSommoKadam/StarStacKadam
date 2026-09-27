package com.stackadam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Allinea un frame al riferimento con una rotazione piccola intorno al centro
 * e una traslazione. Il voto è l'istogramma degli spostamenti fra le stelle:
 * le coppie vere cadono nello stesso bin, le coppie false si spargono.
 */
public final class FrameAlign {
    public static final class Transform {
        public final float angleDeg;
        public final float dx;
        public final float dy;
        public final int votes;

        public Transform(float angleDeg, float dx, float dy, int votes) {
            this.angleDeg = angleDeg;
            this.dx = dx;
            this.dy = dy;
            this.votes = votes;
        }

        public static Transform identity() {
            return new Transform(0f, 0f, 0f, 0);
        }
    }

    private static final float[] ANGLES = {
            0f, -0.5f, 0.5f, -1f, 1f, -1.5f, 1.5f, -2f, 2f,
            -3f, 3f, -4f, 4f, -6f, 6f, -8f, 8f
    };

    private FrameAlign() {}

    public static Transform match(List<StarFinder.Star> reference, List<StarFinder.Star> moving,
                                  int width, int height) {
        int nRef = Math.min(reference.size(), 30);
        int nMov = Math.min(moving.size(), 30);
        if (nRef < 4 || nMov < 4) return Transform.identity();
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        int win = Math.max(32, Math.min(width, height) / 3);
        int bins = win * 2 + 1;
        int[] hist = new int[bins * bins];
        Transform best = Transform.identity();
        for (float angle : ANGLES) {
            Transform candidate = translationPeak(
                    reference, nRef, moving, nMov, angle, cx, cy, win, bins, hist);
            if (candidate.votes > best.votes) best = candidate;
        }
        if (best.votes < 4) return Transform.identity();
        return best;
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
                hist[iy * bins + ix]++;
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
        if (peak < 4) return Transform.identity();
        if (second > 0 && peak < 8 && peak < second * 2) return Transform.identity();
        float dxBin = px - win;
        float dyBin = py - win;
        float accX = 0f;
        float accY = 0f;
        int acc = 0;
        for (int i = 0; i < nRef; i++) {
            StarFinder.Star ref = reference.get(i);
            for (int j = 0; j < nMov; j++) {
                float dx = ref.x - rx[j];
                float dy = ref.y - ry[j];
                if (Math.abs(dx - dxBin) <= 1.6f && Math.abs(dy - dyBin) <= 1.6f) {
                    accX += dx;
                    accY += dy;
                    acc++;
                }
            }
        }
        if (acc == 0) return new Transform(angleDeg, dxBin, dyBin, peak);
        return new Transform(angleDeg, accX / acc, accY / acc, peak);
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
