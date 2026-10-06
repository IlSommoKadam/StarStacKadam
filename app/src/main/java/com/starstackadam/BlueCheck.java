package com.starstackadam;

import android.widget.CheckBox;

/** Cerchio blu al posto del checkbox di sistema, che sulla nebulosa resta scuro. */
final class BlueCheck {
    private BlueCheck() {}

    static void apply(CheckBox box) {
        box.setButtonDrawable(R.drawable.checkbox_blue);
        // Il tema ritinta il pulsante di scuro: senza questo il blu sparisce.
        box.setButtonTintList(null);
    }
}
