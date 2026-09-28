package com.starstackadam;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Sfondo nebulosa a pieno schermo, con velo scuro perché testo e controlli restino leggibili.
 */
final class AppBackdrop {
    private AppBackdrop() {}

    static View wrap(Context context, View content) {
        FrameLayout frame = new FrameLayout(context);

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageResource(R.drawable.bg_nebulosa);
        ColorMatrix lift = new ColorMatrix();
        lift.setScale(1.25f, 1.25f, 1.25f, 1f);
        image.setColorFilter(new ColorMatrixColorFilter(lift));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams match = matchParent();
        frame.addView(image, match);

        View scrim = new View(context);
        GradientDrawable shade = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x22101828, 0x55101828});
        scrim.setBackground(shade);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(scrim, matchParent());

        content.setBackgroundColor(0x00000000);
        int padL = content.getPaddingLeft();
        int padT = content.getPaddingTop();
        int padR = content.getPaddingRight();
        int padB = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(padL + bars.left, padT + bars.top, padR + bars.right, padB + bars.bottom);
            return insets;
        });
        frame.addView(content, matchParent());
        return frame;
    }

    static ImageView mark(Context context, int sizePx) {
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.ic_launcher_art);
        mark.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mark.setContentDescription(context.getString(R.string.app_name));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF070B16);
        shape.setCornerRadius(sizePx * 0.22f);
        mark.setBackground(shape);
        mark.setClipToOutline(true);
        mark.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        return mark;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
