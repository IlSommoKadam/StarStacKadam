package com.starstackadam;

/**
 * Sottrae un fondo lento stimato a polinomio di secondo grado.
 * In ogni cella di una griglia si tiene il cielo scuro (percentile basso),
 * non le stelle. Il modello si adatta a quel cielo e si sottrae, canale per canale.
 * È il procedimento classico di estrazione del gradiente, non una rete neurale.
 */
public final class BackgroundFit {
    public static final class Fit {
        public final ImagePlane image;
        public final boolean applied;
        public final String note;

        Fit(ImagePlane image, boolean applied, String note) {
            this.image = image;
            this.applied = applied;
            this.note = note;
        }
    }

    private BackgroundFit() {}

    public static Fit subtract(ImagePlane src) {
        int gx = 6;
        int gy = 6;
        double[][] samples = new double[gx * gy][2];
        double[][] channel = new double[3][gx * gy];
        int n = 0;
        for (int cy = 0; cy < gy; cy++) {
            int y0 = cy * src.height / gy;
            int y1 = (cy + 1) * src.height / gy;
            for (int cx = 0; cx < gx; cx++) {
                int x0 = cx * src.width / gx;
                int x1 = (cx + 1) * src.width / gx;
                float[] bag = new float[(y1 - y0) * (x1 - x0)];
                int count = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        if (!src.covered(x, y)) continue;
                        int p = (y * src.width + x) * 3;
                        bag[count++] = Stats.luma(src.rgb[p], src.rgb[p + 1], src.rgb[p + 2]);
                    }
                }
                if (count < 16) continue;
                float med = Stats.median(bag, count);
                float limit = percentileOf(bag, count, 0.22f);
                double sr = 0, sg = 0, sb = 0;
                int used = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        if (!src.covered(x, y)) continue;
                        int p = (y * src.width + x) * 3;
                        float lum = Stats.luma(src.rgb[p], src.rgb[p + 1], src.rgb[p + 2]);
                        if (lum > limit || lum > med) continue;
                        sr += src.rgb[p];
                        sg += src.rgb[p + 1];
                        sb += src.rgb[p + 2];
                        used++;
                    }
                }
                if (used < 8) continue;
                samples[n][0] = ((x0 + x1) * 0.5 / Math.max(1, src.width - 1)) * 2 - 1;
                samples[n][1] = ((y0 + y1) * 0.5 / Math.max(1, src.height - 1)) * 2 - 1;
                channel[0][n] = sr / used;
                channel[1][n] = sg / used;
                channel[2][n] = sb / used;
                n++;
            }
        }
        if (n < 8) {
            return new Fit(src.copy(), false, "Fondo non stimato: troppo poche zone di cielo.");
        }
        double[][] coeff = new double[3][];
        for (int c = 0; c < 3; c++) {
            coeff[c] = fitQuadratic(samples, channel[c], n);
            if (coeff[c] == null) {
                return new Fit(src.copy(), false, "Fondo non stimato: il polinomio non si chiude.");
            }
        }
        ImagePlane out = ImagePlane.create(src.width, src.height);
        byte[] cover = src.cover == null ? null : new byte[src.cover.length];
        if (cover != null) System.arraycopy(src.cover, 0, cover, 0, cover.length);
        double[] pedestal = new double[3];
        int pedCount = 0;
        for (int i = 0; i < n; i++) {
            double nx = samples[i][0];
            double ny = samples[i][1];
            for (int c = 0; c < 3; c++) pedestal[c] += eval(coeff[c], nx, ny);
            pedCount++;
        }
        for (int c = 0; c < 3; c++) pedestal[c] /= Math.max(1, pedCount);
        for (int y = 0; y < src.height; y++) {
            double ny = (y / (double) Math.max(1, src.height - 1)) * 2 - 1;
            for (int x = 0; x < src.width; x++) {
                double nx = (x / (double) Math.max(1, src.width - 1)) * 2 - 1;
                int p = (y * src.width + x) * 3;
                for (int c = 0; c < 3; c++) {
                    double model = eval(coeff[c], nx, ny);
                    out.rgb[p + c] = (float) (src.rgb[p + c] - model + pedestal[c]);
                }
            }
        }
        ImagePlane done = new ImagePlane(src.width, src.height, out.rgb, cover);
        return new Fit(done, true, "Fondo polinomiale sottratto (griglia 6×6, grado 2).");
    }

    private static float percentileOf(float[] values, int n, float q) {
        float[] copy = new float[n];
        System.arraycopy(values, 0, copy, 0, n);
        java.util.Arrays.sort(copy);
        int index = (int) Math.round((n - 1) * q);
        if (index < 0) index = 0;
        if (index >= n) index = n - 1;
        return copy[index];
    }

    static double[] fitQuadratic(double[][] xy, double[] z, int n) {
        double[][] ata = new double[6][6];
        double[] atb = new double[6];
        double[] row = new double[6];
        for (int i = 0; i < n; i++) {
            double x = xy[i][0];
            double y = xy[i][1];
            row[0] = 1;
            row[1] = x;
            row[2] = y;
            row[3] = x * x;
            row[4] = x * y;
            row[5] = y * y;
            for (int r = 0; r < 6; r++) {
                atb[r] += row[r] * z[i];
                for (int c = 0; c < 6; c++) ata[r][c] += row[r] * row[c];
            }
        }
        return solve(ata, atb);
    }

    static double eval(double[] a, double x, double y) {
        return a[0] + a[1] * x + a[2] * y + a[3] * x * x + a[4] * x * y + a[5] * y * y;
    }

    static double[] solve(double[][] ata, double[] atb) {
        int n = atb.length;
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(ata[i], 0, a[i], 0, n);
            a[i][n] = atb[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            double div = a[col][col];
            if (Math.abs(div) < 1e-12) return null;
            for (int c = col; c <= n; c++) a[col][c] /= div;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double factor = a[r][col];
                if (factor == 0) continue;
                for (int c = col; c <= n; c++) a[r][c] -= factor * a[col][c];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = a[i][n];
        return x;
    }
}
