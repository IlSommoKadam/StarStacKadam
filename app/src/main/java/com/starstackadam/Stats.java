package com.starstackadam;

import java.util.Arrays;

final class Stats {
    private Stats() {}

    static float percentile(float[] values, float q) {
        int step = Math.max(1, values.length / 24000);
        int n = 0;
        float[] sample = new float[(values.length + step - 1) / step];
        for (int i = 0; i < values.length; i += step) sample[n++] = values[i];
        return percentile(sample, n, q);
    }

    static float percentile(float[] values, int n, float q) {
        if (n <= 0) return 0f;
        float[] copy = Arrays.copyOf(values, n);
        Arrays.sort(copy, 0, n);
        int index = (int) Math.round((n - 1) * q);
        if (index < 0) index = 0;
        if (index >= n) index = n - 1;
        return copy[index];
    }

    static float median(float[] values, int n) {
        if (n <= 0) return 0f;
        float[] copy = Arrays.copyOf(values, n);
        Arrays.sort(copy, 0, n);
        if ((n & 1) == 1) return copy[n / 2];
        return 0.5f * (copy[n / 2 - 1] + copy[n / 2]);
    }

    static float mad(float[] values, int n, float median) {
        if (n <= 0) return 0f;
        float[] dev = new float[n];
        for (int i = 0; i < n; i++) dev[i] = Math.abs(values[i] - median);
        return median(dev, n);
    }

    /** Luminanza Rec.709. */
    static float luma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }
}
