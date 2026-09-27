package com.starstackadam;

import java.util.Arrays;

/** RGB lineare, riga per riga, tre float per pixel nell'intervallo tipico 0–1. */
public final class ImagePlane {
    public final int width;
    public final int height;
    public final float[] rgb;
    /** 0 = pixel non coperto dall'allineamento. Null = tutto coperto. */
    public final byte[] cover;

    public ImagePlane(int width, int height, float[] rgb, byte[] cover) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("dimensioni");
        if (rgb == null || rgb.length != width * height * 3) {
            throw new IllegalArgumentException("buffer rgb");
        }
        if (cover != null && cover.length != width * height) {
            throw new IllegalArgumentException("maschera");
        }
        this.width = width;
        this.height = height;
        this.rgb = rgb;
        this.cover = cover;
    }

    public static ImagePlane create(int width, int height) {
        return new ImagePlane(width, height, new float[width * height * 3], null);
    }

    public ImagePlane copy() {
        byte[] mask = cover == null ? null : Arrays.copyOf(cover, cover.length);
        return new ImagePlane(width, height, Arrays.copyOf(rgb, rgb.length), mask);
    }

    public boolean covered(int x, int y) {
        if (cover == null) return true;
        return cover[y * width + x] != 0;
    }

    /** Porta i valori tipo ADU (FITS) circa in 0–1. I JPEG, già sotto 2, restano com'è. */
    public ImagePlane normalizeRange() {
        float high = Stats.percentile(rgb, 0.995f);
        if (!(high > 2f)) return this;
        ImagePlane out = copy();
        for (int i = 0; i < out.rgb.length; i++) out.rgb[i] /= high;
        return out;
    }

    public ImagePlane fitEdge(int maxEdge) {
        int edge = Math.max(width, height);
        if (edge <= maxEdge) return this;
        double scale = maxEdge / (double) edge;
        int nw = Math.max(1, (int) Math.round(width * scale));
        int nh = Math.max(1, (int) Math.round(height * scale));
        return resample(nw, nh);
    }

    public ImagePlane resample(int nw, int nh) {
        if (nw == width && nh == height) return this;
        float[] dst = new float[nw * nh * 3];
        int[] count = new int[nw * nh];
        for (int y = 0; y < height; y++) {
            int dy = Math.min(nh - 1, (int) ((y + 0.5) * nh / (double) height));
            for (int x = 0; x < width; x++) {
                int dx = Math.min(nw - 1, (int) ((x + 0.5) * nw / (double) width));
                int di = dy * nw + dx;
                int si = (y * width + x) * 3;
                int d3 = di * 3;
                dst[d3] += rgb[si];
                dst[d3 + 1] += rgb[si + 1];
                dst[d3 + 2] += rgb[si + 2];
                count[di]++;
            }
        }
        for (int i = 0; i < count.length; i++) {
            float c = Math.max(1, count[i]);
            dst[i * 3] /= c;
            dst[i * 3 + 1] /= c;
            dst[i * 3 + 2] /= c;
        }
        return new ImagePlane(nw, nh, dst, null);
    }
}
