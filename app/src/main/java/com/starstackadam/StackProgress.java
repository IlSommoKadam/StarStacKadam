package com.starstackadam;

/** Stato stack: frame N/M, voti allineamento, non allineati, nota, cancellabile. */
public final class StackProgress {
    public final int frameIndex;
    public final int frameTotal;
    public final int votes;
    public final int unalignedCount;
    public final String note;
    public final boolean cancelled;
    public final boolean done;
    public final String error;
    public final ImagePlane stacked;
    public final int previewWidth;
    public final int previewHeight;
    public final int[] previewArgb;

    public StackProgress(
            int frameIndex,
            int frameTotal,
            int votes,
            int unalignedCount,
            String note,
            boolean cancelled,
            boolean done,
            String error,
            ImagePlane stacked,
            int previewWidth,
            int previewHeight,
            int[] previewArgb) {
        this.frameIndex = frameIndex;
        this.frameTotal = frameTotal;
        this.votes = votes;
        this.unalignedCount = unalignedCount;
        this.note = note == null ? "" : note;
        this.cancelled = cancelled;
        this.done = done;
        this.error = error;
        this.stacked = stacked;
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
        this.previewArgb = previewArgb;
    }

    public static StackProgress running(
            int frameIndex,
            int frameTotal,
            int votes,
            int unalignedCount,
            String note,
            ImagePlane stacked,
            int previewWidth,
            int previewHeight,
            int[] previewArgb) {
        return new StackProgress(
                frameIndex, frameTotal, votes, unalignedCount, note,
                false, false, null, stacked, previewWidth, previewHeight, previewArgb);
    }

    public static StackProgress finished(
            int frameTotal,
            int unalignedCount,
            String note,
            ImagePlane stacked,
            int previewWidth,
            int previewHeight,
            int[] previewArgb) {
        return new StackProgress(
                frameTotal, frameTotal, 0, unalignedCount, note,
                false, true, null, stacked, previewWidth, previewHeight, previewArgb);
    }

    public static StackProgress cancelled(int frameIndex, int frameTotal, int unalignedCount) {
        return new StackProgress(
                frameIndex, frameTotal, 0, unalignedCount, "Annullato",
                true, true, null, null, 0, 0, null);
    }

    public static StackProgress failed(int frameIndex, int frameTotal, int unalignedCount, String error) {
        return new StackProgress(
                frameIndex, frameTotal, 0, unalignedCount, error,
                false, true, error, null, 0, 0, null);
    }
}
