package com.stackadam;

import java.util.List;

/**
 * Combina frame già allineati, pixel per pixel.
 * Media: integrazione, la stessa idea dello stack che cresce durante la posa.
 * Mediana: ignora un frame sporco intero.
 * Sigma-clip: media dopo aver riportato sul bordo i pixel lontani dalla mediana
 * (scarto medio assoluto). Serve a buttare scie e pixel caldi senza forare l'immagine.
 */
public final class StackCombine {
    public enum Method {
        MEAN, MEDIAN, SIGMA
    }

    private static final float SIGMA_K = 3f;

    private StackCombine() {}

    public static ImagePlane mean(List<ImagePlane> frames) {
        ImagePlane first = frames.get(0);
        int n = first.width * first.height;
        double[] sum = new double[n * 3];
        int[] count = new int[n];
        for (ImagePlane frame : frames) accumulate(sum, count, frame);
        return fromSum(first.width, first.height, sum, count);
    }

    public static ImagePlane median(List<ImagePlane> frames) {
        return perPixel(frames, false);
    }

    public static ImagePlane sigmaClip(List<ImagePlane> frames) {
        return perPixel(frames, true);
    }

    public static void accumulate(double[] sum, int[] count, ImagePlane frame) {
        int n = count.length;
        for (int i = 0; i < n; i++) {
            if (frame.cover != null && frame.cover[i] == 0) continue;
            int p = i * 3;
            sum[p] += frame.rgb[p];
            sum[p + 1] += frame.rgb[p + 1];
            sum[p + 2] += frame.rgb[p + 2];
            count[i]++;
        }
    }

    public static ImagePlane fromSum(int width, int height, double[] sum, int[] count) {
        float[] rgb = new float[sum.length];
        byte[] cover = new byte[count.length];
        for (int i = 0; i < count.length; i++) {
            if (count[i] <= 0) continue;
            cover[i] = 1;
            float inv = 1f / count[i];
            int p = i * 3;
            rgb[p] = (float) (sum[p] * inv);
            rgb[p + 1] = (float) (sum[p + 1] * inv);
            rgb[p + 2] = (float) (sum[p + 2] * inv);
        }
        return new ImagePlane(width, height, rgb, cover);
    }

    private static ImagePlane perPixel(List<ImagePlane> frames, boolean winsor) {
        ImagePlane first = frames.get(0);
        int w = first.width;
        int h = first.height;
        int pixels = w * h;
        int nFrames = frames.size();
        float[] rgb = new float[pixels * 3];
        byte[] cover = new byte[pixels];
        float[] samples = new float[nFrames];
        for (int i = 0; i < pixels; i++) {
            int kept = 0;
            for (ImagePlane frame : frames) {
                if (frame.cover != null && frame.cover[i] == 0) continue;
                samples[kept++] = 0f;
            }
            if (kept == 0) continue;
            cover[i] = 1;
            for (int c = 0; c < 3; c++) {
                int k = 0;
                for (ImagePlane frame : frames) {
                    if (frame.cover != null && frame.cover[i] == 0) continue;
                    samples[k++] = frame.rgb[i * 3 + c];
                }
                rgb[i * 3 + c] = winsor ? winsorMean(samples, k) : Stats.median(samples, k);
            }
        }
        return new ImagePlane(w, h, rgb, cover);
    }

    static float winsorMean(float[] samples, int n) {
        if (n <= 0) return 0f;
        if (n < 3) return Stats.median(samples, n);
        float med = Stats.median(samples, n);
        float sigma = 1.4826f * Stats.mad(samples, n, med);
        if (sigma < 1e-6f) return med;
        float lo = med - SIGMA_K * sigma;
        float hi = med + SIGMA_K * sigma;
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            float v = samples[i];
            if (v < lo) v = lo;
            else if (v > hi) v = hi;
            sum += v;
        }
        return sum / n;
    }
}
