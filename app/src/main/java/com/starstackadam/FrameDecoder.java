package com.starstackadam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Decode JPEG/PNG/WebP → {@link ImagePlane} float RGB 0–1. FITS non supportato in v0.1. */
public final class FrameDecoder {
    private FrameDecoder() {}

    public static ImagePlane decode(File file) throws IOException {
        return decode(file, 0);
    }

    /**
     * @param maxEdge se &gt; 0, usa {@code inSampleSize} così il Bitmap non supera circa maxEdge
     *                sul lato lungo (picco RAM ≈ canvas MEAN, non risoluzione nativa).
     */
    public static ImagePlane decode(File file, int maxEdge) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("File assente");
        }
        String name = file.getName();
        if (FtpBrowser.isFitsName(name)) {
            throw new IOException("FITS non supportato in v0.1: " + name);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Immagine non leggibile: " + name);
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bitmap == null) {
            throw new IOException("Decode fallito: " + name);
        }
        try {
            return fromBitmap(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    static int sampleSizeFor(int width, int height, int maxEdge) {
        if (maxEdge <= 0) return 1;
        int edge = Math.max(width, height);
        int sample = 1;
        while (edge / sample > maxEdge) sample *= 2;
        return Math.max(1, sample);
    }

    public static ImagePlane fromBitmap(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] rgb = new float[w * h * 3];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int p = i * 3;
            rgb[p] = ((c >> 16) & 0xFF) / 255f;
            rgb[p + 1] = ((c >> 8) & 0xFF) / 255f;
            rgb[p + 2] = (c & 0xFF) / 255f;
        }
        return new ImagePlane(w, h, rgb, null).normalizeRange();
    }

    public static boolean isSupportedName(String name) {
        return FtpBrowser.isImageName(name);
    }

    public static String unsupportedReason(String name) {
        if (name == null) return "Nome file vuoto";
        if (FtpBrowser.isFitsName(name)) {
            return "FITS non supportato in v0.1";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return "Formato non supportato: " + lower;
    }
}
