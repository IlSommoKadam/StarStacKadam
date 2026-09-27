package com.starstackadam;

/**
 * Porta il frame lineare sullo schermo.
 * Autostretch: nero dalla mediana e dallo scarto MAD, poi funzione midtone
 * così il cielo resta scuro e l'oggetto compare, come nella visualizzazione
 * di uno stack lineare.
 * Livelli: nero, gamma e bianco, lo stesso controllo di un regolatore di livelli.
 */
public final class DisplayMap {
    public static final class Render {
        public final int[] argb;
        public final int[] histogram;

        Render(int[] argb, int[] histogram) {
            this.argb = argb;
            this.histogram = histogram;
        }
    }

    private DisplayMap() {}

    public static Render autostretch(ImagePlane src) {
        int pixels = src.width * src.height;
        int step = Math.max(1, pixels / 20000);
        float[] bag = new float[pixels / step + 1];
        int n = 0;
        for (int i = 0; i < pixels; i += step) {
            if (src.cover != null && src.cover[i] == 0) continue;
            int p = i * 3;
            bag[n++] = Stats.luma(src.rgb[p], src.rgb[p + 1], src.rgb[p + 2]);
        }
        float median = Stats.median(bag, n);
        float sigma = 1.4826f * Stats.mad(bag, n, median);
        float black = median - 2.8f * sigma;
        if (black > 0.9f) black = 0.9f;
        float denom = 1f - black;
        if (denom < 0.05f) denom = 0.05f;
        float xmed = clamp((median - black) / denom, 0.002f, 0.98f);
        float midtone = clamp(midtoneFor(xmed, 0.22f), 0.05f, 0.95f);
        int[] argb = new int[pixels];
        int[] hist = new int[256];
        for (int i = 0; i < pixels; i++) {
            int p = i * 3;
            float r = mtf(clamp((src.rgb[p] - black) / denom, 0f, 1f), midtone);
            float g = mtf(clamp((src.rgb[p + 1] - black) / denom, 0f, 1f), midtone);
            float b = mtf(clamp((src.rgb[p + 2] - black) / denom, 0f, 1f), midtone);
            argb[i] = pack(r, g, b);
            hist[lumaByte(r, g, b)]++;
        }
        return new Render(argb, hist);
    }

    public static Render levels(ImagePlane src, float black, float white, float gamma) {
        if (white <= black + 0.01f) white = black + 0.01f;
        if (gamma < 0.05f) gamma = 0.05f;
        float denom = white - black;
        float exp = (float) (1.0 / gamma);
        int pixels = src.width * src.height;
        int[] argb = new int[pixels];
        int[] hist = new int[256];
        for (int i = 0; i < pixels; i++) {
            int p = i * 3;
            float r = tone(src.rgb[p], black, denom, exp);
            float g = tone(src.rgb[p + 1], black, denom, exp);
            float b = tone(src.rgb[p + 2], black, denom, exp);
            argb[i] = pack(r, g, b);
            hist[lumaByte(r, g, b)]++;
        }
        return new Render(argb, hist);
    }

    private static float tone(float value, float black, float denom, float exp) {
        float x = clamp((value - black) / denom, 0f, 1f);
        if (x <= 0f || exp == 1f) return x;
        return (float) Math.pow(x, exp);
    }

    /** m tale che MTF(x, m) = target. */
    static float midtoneFor(float x, float target) {
        float den = (2f * target * x) - target - x;
        if (Math.abs(den) < 1e-6f) return 0.5f;
        return (x * (target - 1f)) / den;
    }

    static float mtf(float x, float m) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        float den = ((2f * m) - 1f) * x - m;
        if (Math.abs(den) < 1e-6f) return x;
        float y = ((m - 1f) * x) / den;
        return clamp(y, 0f, 1f);
    }

    private static int pack(float r, float g, float b) {
        int ri = Math.round(clamp(r, 0f, 1f) * 255f);
        int gi = Math.round(clamp(g, 0f, 1f) * 255f);
        int bi = Math.round(clamp(b, 0f, 1f) * 255f);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int lumaByte(float r, float g, float b) {
        int y = Math.round((0.2126f * r + 0.7152f * g + 0.0722f * b) * 255f);
        if (y < 0) return 0;
        if (y > 255) return 255;
        return y;
    }

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
