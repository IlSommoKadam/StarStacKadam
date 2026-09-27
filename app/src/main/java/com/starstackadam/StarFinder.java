package com.starstackadam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cerca le stelle come massimi locali sopra un fondo lento.
 * Il fondo è la media in una finestra larga (immagine integrale), così il
 * gradiente del cielo non viene scambiato per una stella.
 */
public final class StarFinder {
    public static final class Star {
        public final float x;
        public final float y;
        public final float flux;

        public Star(float x, float y, float flux) {
            this.x = x;
            this.y = y;
            this.flux = flux;
        }
    }

    private StarFinder() {}

    public static List<Star> find(ImagePlane image, int maxStars) {
        int w = image.width;
        int h = image.height;
        int n = w * h;
        float[] lum = new float[n];
        for (int i = 0, p = 0; i < n; i++, p += 3) {
            if (!image.covered(i % w, i / w)) {
                lum[i] = 0f;
                continue;
            }
            lum[i] = Stats.luma(image.rgb[p], image.rgb[p + 1], image.rgb[p + 2]);
        }
        int radius = Math.max(8, Math.min(w, h) / 14);
        float[] background = boxMean(lum, w, h, radius);
        float[] residual = new float[n];
        int samples = 0;
        int step = Math.max(1, n / 20000);
        float[] bag = new float[n / step + 1];
        for (int i = 0; i < n; i++) {
            residual[i] = lum[i] - background[i];
            if (i % step == 0) bag[samples++] = residual[i];
        }
        float med = Stats.median(bag, samples);
        float sigma = 1.4826f * Stats.mad(bag, samples, med);
        float threshold = med + Math.max(0.045f, 4f * sigma);

        int gap = Math.max(6, Math.min(w, h) / 90);
        List<Star> found = new ArrayList<>();
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                int i = y * w + x;
                float v = residual[i];
                if (v < threshold) continue;
                if (!isPeak(residual, w, x, y, v)) continue;
                float[] com = centerOfMass(residual, w, h, x, y);
                found.add(new Star(com[0], com[1], v));
            }
        }
        found.sort(Comparator.comparingDouble((Star s) -> s.flux).reversed());
        List<Star> kept = new ArrayList<>();
        int gap2 = gap * gap;
        for (Star star : found) {
            boolean close = false;
            for (Star other : kept) {
                float dx = star.x - other.x;
                float dy = star.y - other.y;
                if (dx * dx + dy * dy < gap2) {
                    close = true;
                    break;
                }
            }
            if (close) continue;
            kept.add(star);
            if (kept.size() >= maxStars) break;
        }
        return kept;
    }

    private static boolean isPeak(float[] residual, int w, int x, int y, float v) {
        for (int dy = -2; dy <= 2; dy++) {
            int row = (y + dy) * w;
            for (int dx = -2; dx <= 2; dx++) {
                if (dx == 0 && dy == 0) continue;
                if (residual[row + x + dx] > v) return false;
            }
        }
        return true;
    }

    private static float[] centerOfMass(float[] residual, int w, int h, int x, int y) {
        float sw = 0f;
        float sx = 0f;
        float sy = 0f;
        for (int dy = -1; dy <= 1; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= h) continue;
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx;
                if (xx < 0 || xx >= w) continue;
                float weight = Math.max(0f, residual[yy * w + xx]);
                sw += weight;
                sx += weight * xx;
                sy += weight * yy;
            }
        }
        if (sw <= 0f) return new float[]{x, y};
        return new float[]{sx / sw, sy / sw};
    }

    private static float[] boxMean(float[] src, int w, int h, int radius) {
        double[] integral = new double[(w + 1) * (h + 1)];
        int stride = w + 1;
        for (int y = 0; y < h; y++) {
            double row = 0;
            int srcRow = y * w;
            for (int x = 0; x < w; x++) {
                row += src[srcRow + x];
                integral[(y + 1) * stride + (x + 1)] = integral[y * stride + (x + 1)] + row;
            }
        }
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(h - 1, y + radius);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(w - 1, x + radius);
                double sum = integral[(y1 + 1) * stride + (x1 + 1)]
                        - integral[y0 * stride + (x1 + 1)]
                        - integral[(y1 + 1) * stride + x0]
                        + integral[y0 * stride + x0];
                int area = (x1 - x0 + 1) * (y1 - y0 + 1);
                out[y * w + x] = (float) (sum / area);
            }
        }
        return out;
    }
}
