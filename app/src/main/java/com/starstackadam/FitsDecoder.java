package com.starstackadam;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Primo HDU immagine di un FITS: mono oppure RGB a piani.
 * Il lato lungo viene già ridotto a {@code maxEdge} in lettura, così non resta in RAM il frame intero.
 */
public final class FitsDecoder {
    private static final int BLOCK = 2880;

    private FitsDecoder() {}

    public static ImagePlane decode(File file, int maxEdge) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("File assente");
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            Header header = readHeader(in);
            if (header.naxis < 2 || header.width <= 0 || header.height <= 0) {
                throw new IOException("FITS senza immagine: " + file.getName());
            }
            if (header.width > 20000 || header.height > 20000) {
                throw new IOException("FITS troppo grande: " + header.width + "×" + header.height);
            }
            int step = sampleStep(header.width, header.height, maxEdge);
            int outW = countSteps(header.width, step);
            int outH = countSteps(header.height, step);
            float[] rgb = new float[outW * outH * 3];
            if (header.channels <= 1) {
                readMono(in, header, step, outW, outH, rgb);
            } else if (header.planar) {
                readPlanar(in, header, step, outW, outH, rgb);
            } else {
                readInterleaved(in, header, step, outW, outH, rgb);
            }
            return new ImagePlane(outW, outH, rgb, null).normalizeRange();
        }
    }

    private static void readMono(
            RandomAccessFile in, Header header, int step, int outW, int outH, float[] rgb) throws IOException {
        int bytes = header.pixelBytes;
        byte[] row = new byte[header.width * bytes];
        int oy = 0;
        for (int y = 0; y < header.height && oy < outH; y += step, oy++) {
            readAt(in, header.data + (long) y * header.width * bytes, row);
            int ox = 0;
            for (int x = 0; x < header.width && ox < outW; x += step, ox++) {
                float v = pixel(row, x * bytes, header);
                int p = (oy * outW + ox) * 3;
                rgb[p] = v;
                rgb[p + 1] = v;
                rgb[p + 2] = v;
            }
        }
    }

    private static void readPlanar(
            RandomAccessFile in, Header header, int step, int outW, int outH, float[] rgb) throws IOException {
        int bytes = header.pixelBytes;
        long plane = (long) header.width * header.height * bytes;
        byte[] row = new byte[header.width * bytes];
        int oy = 0;
        for (int y = 0; y < header.height && oy < outH; y += step, oy++) {
            for (int c = 0; c < 3; c++) {
                long off = header.data + c * plane + (long) y * header.width * bytes;
                readAt(in, off, row);
                int ox = 0;
                for (int x = 0; x < header.width && ox < outW; x += step, ox++) {
                    rgb[(oy * outW + ox) * 3 + c] = pixel(row, x * bytes, header);
                }
            }
        }
    }

    private static void readInterleaved(
            RandomAccessFile in, Header header, int step, int outW, int outH, float[] rgb) throws IOException {
        int channels = header.channels;
        int bytes = header.pixelBytes;
        int rowBytes = header.width * channels * bytes;
        byte[] row = new byte[rowBytes];
        int oy = 0;
        for (int y = 0; y < header.height && oy < outH; y += step, oy++) {
            readAt(in, header.data + (long) y * rowBytes, row);
            int ox = 0;
            for (int x = 0; x < header.width && ox < outW; x += step, ox++) {
                int base = x * channels * bytes;
                int p = (oy * outW + ox) * 3;
                rgb[p] = pixel(row, base, header);
                rgb[p + 1] = pixel(row, base + bytes, header);
                rgb[p + 2] = channels > 2 ? pixel(row, base + 2 * bytes, header) : rgb[p];
            }
        }
    }

    private static float pixel(byte[] raw, int offset, Header header) {
        long sample;
        switch (header.bitpix) {
            case 8 -> sample = raw[offset] & 0xFFL;
            case 16 -> sample = (short) (((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF));
            case 32 -> sample = (int) (((raw[offset] & 0xFF) << 24)
                    | ((raw[offset + 1] & 0xFF) << 16)
                    | ((raw[offset + 2] & 0xFF) << 8)
                    | (raw[offset + 3] & 0xFF));
            case -32 -> {
                int bits = ((raw[offset] & 0xFF) << 24)
                        | ((raw[offset + 1] & 0xFF) << 16)
                        | ((raw[offset + 2] & 0xFF) << 8)
                        | (raw[offset + 3] & 0xFF);
                float value = Float.intBitsToFloat(bits);
                return Float.isFinite(value) ? (float) (header.bzero + header.bscale * value) : 0f;
            }
            case -64 -> {
                long bits = 0;
                for (int i = 0; i < 8; i++) bits = (bits << 8) | (raw[offset + i] & 0xFFL);
                double value = Double.longBitsToDouble(bits);
                return Double.isFinite(value) ? (float) (header.bzero + header.bscale * value) : 0f;
            }
            default -> throw new IllegalStateException("BITPIX " + header.bitpix);
        }
        if (header.blank != null && sample == header.blank) return 0f;
        return (float) (header.bzero + header.bscale * sample);
    }

    private static void readAt(RandomAccessFile in, long offset, byte[] dest) throws IOException {
        in.seek(offset);
        int got = 0;
        while (got < dest.length) {
            int n = in.read(dest, got, dest.length - got);
            if (n < 0) throw new EOFException("FITS troncato");
            got += n;
        }
    }

    private static int sampleStep(int width, int height, int maxEdge) {
        if (maxEdge <= 0) return 1;
        int edge = Math.max(width, height);
        int step = 1;
        while (edge / step > maxEdge) step *= 2;
        return step;
    }

    private static int countSteps(int size, int step) {
        int n = 0;
        for (int i = 0; i < size; i += step) n++;
        return Math.max(1, n);
    }

    private static Header readHeader(RandomAccessFile in) throws IOException {
        Header header = new Header();
        byte[] block = new byte[BLOCK];
        boolean end = false;
        while (!end) {
            readAt(in, in.getFilePointer(), block);
            for (int i = 0; i < BLOCK; i += 80) {
                String card = new String(block, i, 80, StandardCharsets.US_ASCII);
                String key = card.substring(0, Math.min(8, card.length())).trim().toUpperCase(Locale.ROOT);
                if ("END".equals(key)) {
                    end = true;
                    break;
                }
                if (card.length() < 10 || card.charAt(8) != '=') continue;
                String value = valueOf(card.substring(10));
                switch (key) {
                    case "BITPIX" -> header.bitpix = (int) parseLong(value);
                    case "NAXIS" -> header.naxis = (int) parseLong(value);
                    case "NAXIS1" -> header.naxis1 = (int) parseLong(value);
                    case "NAXIS2" -> header.naxis2 = (int) parseLong(value);
                    case "NAXIS3" -> header.naxis3 = (int) parseLong(value);
                    case "BSCALE" -> header.bscale = parseDouble(value);
                    case "BZERO" -> header.bzero = parseDouble(value);
                    case "BLANK" -> header.blank = parseLong(value);
                    default -> {
                    }
                }
            }
        }
        header.data = in.getFilePointer();
        header.layout();
        return header;
    }

    private static String valueOf(String raw) {
        String text = raw.trim();
        int slash = text.indexOf('/');
        if (text.startsWith("'")) {
            int close = text.indexOf('\'', 1);
            return close > 0 ? text.substring(1, close) : text;
        }
        if (slash >= 0) text = text.substring(0, slash).trim();
        return text;
    }

    private static long parseLong(String text) {
        String cleaned = text.trim();
        if (cleaned.isEmpty()) return 0L;
        int dot = cleaned.indexOf('.');
        if (dot >= 0) cleaned = cleaned.substring(0, dot);
        return Long.parseLong(cleaned);
    }

    private static double parseDouble(String text) {
        if (text == null || text.isBlank()) return 0d;
        return Double.parseDouble(text.trim());
    }

    private static final class Header {
        int bitpix;
        int naxis;
        int naxis1;
        int naxis2;
        int naxis3 = 1;
        double bscale = 1d;
        double bzero;
        Long blank;
        long data;
        int width;
        int height;
        int channels = 1;
        boolean planar;
        int pixelBytes;

        void layout() throws IOException {
            pixelBytes = Math.abs(bitpix) / 8;
            if (pixelBytes < 1 || pixelBytes > 8) {
                throw new IOException("BITPIX non gestito: " + bitpix);
            }
            if (naxis >= 3 && naxis3 >= 3 && naxis1 > 4) {
                width = naxis1;
                height = naxis2;
                channels = 3;
                planar = true;
            } else if (naxis >= 3 && naxis1 > 0 && naxis1 <= 4 && naxis2 > 4) {
                width = naxis2;
                height = Math.max(1, naxis3);
                channels = naxis1;
                planar = false;
            } else {
                width = naxis1;
                height = naxis2;
                channels = 1;
                planar = false;
            }
        }
    }
}
