package com.starstackadam;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Preview stack MEAN, progress, Autostretch/Levels, fondo opzionale, Salva PNG.
 */
public final class StackWorkActivity extends Activity {
    private static final int BG = 0xFF121416;
    private static final int CARD = 0xFF1C1F24;
    private static final int TEXT = 0xFFE8EAED;
    private static final int MUTED = 0xFF9AA0A6;
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int DANGER = 0xFFF28B82;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private StackSession session;

    private ImageView preview;
    private TextView progressText;
    private TextView noteText;
    private ProgressBar progressBar;
    private CheckBox backgroundToggle;
    private RadioGroup stretchGroup;
    private LinearLayout levelsBox;
    private SeekBar blackSeek;
    private SeekBar whiteSeek;
    private SeekBar gammaSeek;
    private TextView levelsLabel;
    private Button cancelBtn;
    private Button saveBtn;

    private Bitmap previewBitmap;
    private boolean finished;
    private int lastW;
    private int lastH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        session = new StackSession(new FrameCache(this));
        setContentView(buildUi());

        Intent intent = getIntent();
        String host = intent.getStringExtra(MainActivity.EXTRA_HOST);
        int port = intent.getIntExtra(MainActivity.EXTRA_PORT, HostSettingsStore.PORT_HD);
        ArrayList<String> remotes = intent.getStringArrayListExtra(MainActivity.EXTRA_REMOTE_PATHS);
        ArrayList<String> locals = intent.getStringArrayListExtra(MainActivity.EXTRA_LOCAL_FILES);
        if (host == null || host.isEmpty() || remotes == null || remotes.isEmpty()) {
            progressText.setText("Parametri mancanti");
            progressText.setTextColor(DANGER);
            cancelBtn.setText("Chiudi");
            return;
        }
        startStack(host, port, remotes, locals);
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.requestCancel();
        if (executor != null) executor.shutdownNow();
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("Stack preview (JPEG)", 18, true);
        title.setTextColor(ACCENT);
        root.addView(title);

        TextView sub = text("MEAN streaming — allinea + media + stretch", 12, false);
        sub.setTextColor(MUTED);
        sub.setPadding(0, dp(2), 0, dp(8));
        root.addView(sub);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(rounded(CARD, dp(8)));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(preview, previewLp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(8);
        root.addView(progressBar, barLp);

        progressText = text("Avvio…", 14, true);
        progressText.setPadding(0, dp(6), 0, 0);
        root.addView(progressText);

        noteText = text("", 12, false);
        noteText.setTextColor(MUTED);
        noteText.setPadding(0, dp(2), 0, dp(8));
        root.addView(noteText);

        stretchGroup = new RadioGroup(this);
        stretchGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton auto = radio("Autostretch");
        auto.setId(View.generateViewId());
        RadioButton levels = radio("Livelli");
        levels.setId(View.generateViewId());
        stretchGroup.addView(auto);
        stretchGroup.addView(levels);
        auto.setChecked(true);
        stretchGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean useLevels = checkedId == levels.getId();
            levelsBox.setVisibility(useLevels ? View.VISIBLE : View.GONE);
            applyDisplaySettings();
            refreshPreview();
        });
        root.addView(stretchGroup);

        levelsBox = new LinearLayout(this);
        levelsBox.setOrientation(LinearLayout.VERTICAL);
        levelsBox.setVisibility(View.GONE);
        levelsBox.setPadding(0, dp(4), 0, dp(4));
        levelsLabel = text("Nero / Bianco / Gamma", 11, false);
        levelsLabel.setTextColor(MUTED);
        levelsBox.addView(levelsLabel);
        blackSeek = seek(0);
        whiteSeek = seek(100);
        gammaSeek = seek(50);
        levelsBox.addView(labeledSeek("Nero", blackSeek));
        levelsBox.addView(labeledSeek("Bianco", whiteSeek));
        levelsBox.addView(labeledSeek("Gamma", gammaSeek));
        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                updateLevelsLabel();
                applyDisplaySettings();
                refreshPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        blackSeek.setOnSeekBarChangeListener(seekListener);
        whiteSeek.setOnSeekBarChangeListener(seekListener);
        gammaSeek.setOnSeekBarChangeListener(seekListener);
        updateLevelsLabel();
        root.addView(levelsBox);

        backgroundToggle = new CheckBox(this);
        backgroundToggle.setText("Sottrai fondo (opzionale, off di default)");
        backgroundToggle.setTextColor(TEXT);
        backgroundToggle.setChecked(false);
        backgroundToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (session != null) session.setBackgroundFit(isChecked);
            refreshPreview();
        });
        root.addView(backgroundToggle);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        cancelBtn = actionButton("Annulla", MUTED);
        cancelBtn.setOnClickListener(v -> {
            if (finished) {
                finish();
                return;
            }
            if (session != null) session.requestCancel();
            cancelBtn.setEnabled(false);
            noteText.setText("Annullamento…");
        });
        saveBtn = actionButton("Salva PNG", ACCENT);
        saveBtn.setEnabled(false);
        saveBtn.setAlpha(0.45f);
        saveBtn.setOnClickListener(v -> savePng());
        buttons.addView(cancelBtn, weightLp());
        buttons.addView(space(dp(8)));
        buttons.addView(saveBtn, weightLp());
        root.addView(buttons);

        return root;
    }

    private void startStack(String host, int port, List<String> remotes, List<String> locals) {
        applyDisplaySettings();
        session.setBackgroundFit(backgroundToggle.isChecked());
        StackSession.Request request = new StackSession.Request(
                host, port, remotes, locals, backgroundToggle.isChecked(),
                isLevelsMode(), levelBlack(), levelWhite(), levelGamma());
        progressBar.setMax(Math.max(1, remotes.size()));
        progressBar.setProgress(0);
        executor.execute(() -> session.run(request, progress -> main.post(() -> onProgress(progress))));
    }

    private void onProgress(StackProgress progress) {
        if (isDestroyed()) return;
        progressBar.setMax(Math.max(1, progress.frameTotal));
        progressBar.setProgress(Math.min(progress.frameIndex, progress.frameTotal));
        progressText.setText(String.format(Locale.ITALY,
                "Frame %d / %d   voti=%d   non allineati=%d",
                progress.frameIndex, progress.frameTotal, progress.votes, progress.unalignedCount));
        noteText.setText(progress.note);
        noteText.setTextColor(progress.error != null ? DANGER : MUTED);

        if (progress.previewArgb != null && progress.previewWidth > 0 && progress.previewHeight > 0) {
            showPreview(progress.previewArgb, progress.previewWidth, progress.previewHeight);
        }

        if (progress.done) {
            finished = true;
            cancelBtn.setEnabled(true);
            cancelBtn.setText("Chiudi");
            boolean ok = progress.error == null && !progress.cancelled && session.lastStacked() != null;
            saveBtn.setEnabled(ok);
            saveBtn.setAlpha(ok ? 1f : 0.45f);
            if (progress.cancelled) {
                progressText.setText("Annullato");
            } else if (progress.error != null) {
                progressText.setText("Errore");
                progressText.setTextColor(DANGER);
            } else {
                progressText.setTextColor(TEXT);
            }
        }
    }

    private void showPreview(int[] argb, int w, int h) {
        if (previewBitmap == null || previewBitmap.getWidth() != w || previewBitmap.getHeight() != h
                || previewBitmap.isRecycled()) {
            if (previewBitmap != null && !previewBitmap.isRecycled()) previewBitmap.recycle();
            previewBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        previewBitmap.setPixels(argb, 0, w, 0, 0, w, h);
        preview.setImageBitmap(previewBitmap);
        lastW = w;
        lastH = h;
    }

    private void refreshPreview() {
        if (session == null || session.lastStacked() == null) return;
        applyDisplaySettings();
        executor.execute(() -> {
            StackProgress p = session.redisplay();
            main.post(() -> {
                if (p.previewArgb != null) {
                    showPreview(p.previewArgb, p.previewWidth, p.previewHeight);
                }
                if (p.note != null && !p.note.isEmpty()) {
                    noteText.setText(p.note);
                }
            });
        });
    }

    private void applyDisplaySettings() {
        if (session == null) return;
        session.setDisplayMode(isLevelsMode(), levelBlack(), levelWhite(), levelGamma());
        session.setBackgroundFit(backgroundToggle != null && backgroundToggle.isChecked());
    }

    private boolean isLevelsMode() {
        if (stretchGroup == null) return false;
        int id = stretchGroup.getCheckedRadioButtonId();
        View checked = stretchGroup.findViewById(id);
        return checked instanceof RadioButton
                && "Livelli".contentEquals(((RadioButton) checked).getText());
    }

    private float levelBlack() {
        return blackSeek == null ? 0f : blackSeek.getProgress() / 100f;
    }

    private float levelWhite() {
        float w = whiteSeek == null ? 1f : whiteSeek.getProgress() / 100f;
        float b = levelBlack();
        return Math.max(w, b + 0.02f);
    }

    private float levelGamma() {
        // 0..100 → ~0.3 .. 2.5, centro 1.0
        int p = gammaSeek == null ? 50 : gammaSeek.getProgress();
        return 0.3f + (p / 100f) * 2.2f;
    }

    private void updateLevelsLabel() {
        if (levelsLabel == null) return;
        levelsLabel.setText(String.format(Locale.US,
                "Nero %.2f · Bianco %.2f · Gamma %.2f",
                levelBlack(), levelWhite(), levelGamma()));
    }

    private void savePng() {
        if (previewBitmap == null || session == null || session.lastStacked() == null) {
            Toast.makeText(this, "Nessuna anteprima da salvare", Toast.LENGTH_SHORT).show();
            return;
        }
        saveBtn.setEnabled(false);
        applyDisplaySettings();
        executor.execute(() -> {
            try {
                StackProgress p = session.redisplay();
                if (p.previewArgb == null) throw new IllegalStateException("Anteprima vuota");
                Bitmap bmp = Bitmap.createBitmap(p.previewWidth, p.previewHeight, Bitmap.Config.ARGB_8888);
                bmp.setPixels(p.previewArgb, 0, p.previewWidth, 0, 0, p.previewWidth, p.previewHeight);
                String name = "starstackadam_" + System.currentTimeMillis() + ".png";
                Uri uri = writePng(name, bmp);
                bmp.recycle();
                main.post(() -> {
                    saveBtn.setEnabled(true);
                    if (uri != null) {
                        Toast.makeText(this, "Salvato: " + name, Toast.LENGTH_LONG).show();
                        noteText.setText("PNG salvato in Pictures/StarStacKadam");
                    } else {
                        Toast.makeText(this, "Salvataggio fallito", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                main.post(() -> {
                    saveBtn.setEnabled(true);
                    Toast.makeText(this, msg == null ? "Errore salvataggio" : msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private Uri writePng(String name, Bitmap bmp) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/StarStacKadam");
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("MediaStore insert fallito");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("OutputStream null");
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IllegalStateException("Compress PNG fallito");
            }
        }
        return uri;
    }

    private TextView text(String value, float sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private RadioButton radio(String label) {
        RadioButton rb = new RadioButton(this);
        rb.setText(label);
        rb.setTextColor(TEXT);
        return rb;
    }

    private SeekBar seek(int progress) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(progress);
        return sb;
    }

    private View labeledSeek(String name, SeekBar seekBar) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(name, 11, false);
        label.setTextColor(MUTED);
        label.setMinWidth(dp(56));
        row.addView(label);
        row.addView(seekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button actionButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(color, dp(8)));
        return b;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private View space(int width) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return v;
    }

    private LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
