package com.starstackadam;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Preview stack MEAN, progress, Autostretch/Levels, fondo opzionale, Salva PNG.
 */
public final class StackWorkActivity extends Activity {
    private static final int CARD = 0xE61C1F24;
    private static final int TEXT = 0xFFE8EAED;
    private static final int MUTED = 0xFF9AA0A6;
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int SHARE = 0xFF81C995;
    private static final int DANGER = 0xFFF28B82;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private StackSession session;

    private ImageView preview;
    private TextView titleView;
    private TextView subView;
    private TextView progressText;
    private TextView noteText;
    private ProgressBar progressBar;
    private Button autoBtn;
    private Button levelsBtn;
    private Button backgroundBtn;
    private boolean levelsMode;
    private boolean subtractBackground;
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
    private StackSession.Mode mode = StackSession.Mode.LIVE;
    private HostSettingsStore store;
    private HostSettingsStore.StackOptions profile;
    private String scientificName = "";
    private String publicName = "";
    private String kindLabel = "";
    private int lastUnaligned;
    private int lastTotal;
    private final StackService.Listener uiListener = this::onProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        store = new HostSettingsStore(this);
        setContentView(AppBackdrop.wrap(this, buildUi()));
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }

        Intent intent = getIntent();
        if (savedInstanceState != null && !intent.hasExtra(StackService.EXTRA_JOB)) {
            long savedJob = savedInstanceState.getLong(StackService.EXTRA_JOB, 0L);
            if (savedJob != 0L) intent.putExtra(StackService.EXTRA_JOB, savedJob);
        }
        String host = intent.getStringExtra(MainActivity.EXTRA_HOST);
        ArrayList<String> remotes = intent.getStringArrayListExtra(MainActivity.EXTRA_REMOTE_PATHS);
        scientificName = textExtra(intent, MainActivity.EXTRA_SCIENTIFIC);
        publicName = textExtra(intent, MainActivity.EXTRA_PUBLIC);
        kindLabel = textExtra(intent, MainActivity.EXTRA_KIND);
        String modeName = textExtra(intent, MainActivity.EXTRA_MODE);
        mode = "SHARE".equals(modeName) ? StackSession.Mode.SHARE : StackSession.Mode.LIVE;
        profile = mode == StackSession.Mode.SHARE ? store.getShareOptions() : store.getLiveOptions();
        subtractBackground = profile.subtractBackground;
        stylePill(backgroundBtn, subtractBackground);
        applyModeChrome();
        if (StackService.ACTION_RESUME.equals(intent.getAction())) {
            session = StackService.session();
            StackService.listen(uiListener);
            if (session == null && !StackService.isRunning()) {
                progressText.setText("Nessuno stack in corso");
                cancelBtn.setText("Chiudi");
                finished = true;
            }
            return;
        }
        if (host == null || host.isEmpty() || remotes == null || remotes.isEmpty()) {
            progressText.setText("Parametri mancanti");
            progressText.setTextColor(DANGER);
            cancelBtn.setText("Chiudi");
            return;
        }
        StackService.listen(uiListener);
        launchJob();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (StackService.ACTION_RESUME.equals(intent.getAction())) {
            session = StackService.session();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(StackService.EXTRA_JOB, getIntent().getLongExtra(StackService.EXTRA_JOB, 0L));
    }

    @Override
    protected void onDestroy() {
        StackService.unlisten(uiListener);
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
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int markSize = dp(40);
        ImageView mark = AppBackdrop.mark(this, markSize);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(markSize, markSize);
        markLp.rightMargin = dp(10);
        header.addView(mark, markLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = text("Stack", 18, true);
        titleView.setTextColor(ACCENT);
        titles.addView(titleView);
        subView = text("", 12, false);
        subView.setTextColor(MUTED);
        subView.setPadding(0, dp(2), 0, 0);
        titles.addView(subView);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(8);
        root.addView(header, headerLp);

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
        progressText.setShadowLayer(dp(3), 0, dp(1), 0xE0000000);
        progressText.setPadding(0, dp(6), 0, 0);
        root.addView(progressText);

        noteText = text("", 12, false);
        noteText.setTextColor(TEXT);
        noteText.setShadowLayer(dp(3), 0, dp(1), 0xE0000000);
        noteText.setPadding(0, dp(2), 0, dp(8));
        root.addView(noteText);

        LinearLayout stretchRow = new LinearLayout(this);
        stretchRow.setOrientation(LinearLayout.HORIZONTAL);
        autoBtn = pill("Autostretch", true);
        levelsBtn = pill("Livelli", false);
        autoBtn.setOnClickListener(v -> setStretch(false));
        levelsBtn.setOnClickListener(v -> setStretch(true));
        stretchRow.addView(autoBtn, weightLp());
        stretchRow.addView(space(dp(8)));
        stretchRow.addView(levelsBtn, weightLp());
        LinearLayout.LayoutParams stretchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stretchLp.topMargin = dp(4);
        root.addView(stretchRow, stretchLp);

        levelsBox = new LinearLayout(this);
        levelsBox.setOrientation(LinearLayout.VERTICAL);
        levelsBox.setVisibility(View.GONE);
        levelsBox.setBackground(rounded(CARD, dp(8)));
        levelsBox.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams levelsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        levelsLp.topMargin = dp(6);
        levelsLabel = text("Nero / Bianco / Gamma", 12, false);
        levelsLabel.setTextColor(TEXT);
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
        root.addView(levelsBox, levelsLp);

        backgroundBtn = pill("Sottrai fondo", false);
        backgroundBtn.setOnClickListener(v -> {
            subtractBackground = !subtractBackground;
            stylePill(backgroundBtn, subtractBackground);
            StackSession live = liveSession();
            if (live != null) live.setBackgroundFit(subtractBackground);
            refreshPreview();
        });
        LinearLayout.LayoutParams bgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bgLp.topMargin = dp(6);
        root.addView(backgroundBtn, bgLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        cancelBtn = actionButton("Annulla", MUTED);
        cancelBtn.setOnClickListener(v -> {
            if (finished) {
                finish();
                return;
            }
            StackService.cancel(this);
            cancelBtn.setEnabled(false);
            noteText.setText("Annullamento…");
        });
        saveBtn = actionButton("Salva", ACCENT);
        saveBtn.setEnabled(false);
        saveBtn.setAlpha(0.45f);
        saveBtn.setOnClickListener(v -> savePng());
        buttons.addView(cancelBtn, weightLp());
        buttons.addView(space(dp(8)));
        buttons.addView(saveBtn, weightLp());
        root.addView(buttons);

        return root;
    }

    private void launchJob() {
        Intent intent = getIntent();
        if (intent.getLongExtra(StackService.EXTRA_JOB, 0L) == 0L) {
            intent.putExtra(StackService.EXTRA_JOB, System.currentTimeMillis());
        }
        intent.putExtra(StackService.EXTRA_BACKGROUND, subtractBackground);
        intent.putExtra(StackService.EXTRA_LEVELS, levelsMode);
        intent.putExtra(StackService.EXTRA_BLACK, levelBlack());
        intent.putExtra(StackService.EXTRA_WHITE, levelWhite());
        intent.putExtra(StackService.EXTRA_GAMMA, levelGamma());
        if (StackService.sameJob(intent)) {
            session = StackService.session();
            return;
        }
        progressBar.setProgress(0);
        StackService.start(this, intent);
    }

    private StackSession liveSession() {
        StackSession live = StackService.session();
        if (live != null) session = live;
        return session;
    }

    private void onProgress(StackProgress progress) {
        if (isDestroyed() || progress == null) return;
        StackSession live = StackService.session();
        if (live != null) session = live;
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

        lastTotal = progress.frameTotal;
        lastUnaligned = progress.unalignedCount;

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
        if (liveSession() == null || session.lastStacked() == null) return;
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
        StackSession live = liveSession();
        if (live == null) return;
        live.setDisplayMode(levelsMode, levelBlack(), levelWhite(), levelGamma());
        live.setBackgroundFit(subtractBackground);
    }

    private void setStretch(boolean levels) {
        levelsMode = levels;
        stylePill(autoBtn, !levels);
        stylePill(levelsBtn, levels);
        levelsBox.setVisibility(levels ? View.VISIBLE : View.GONE);
        applyDisplaySettings();
        refreshPreview();
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
        if (previewBitmap == null || liveSession() == null || session.lastStacked() == null) {
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
                String base = fileBase();
                String pngName = base + ".png";
                Uri uri = writePng(pngName, bmp, caption());
                bmp.recycle();
                String jsonNote = "";
                if (mode == StackSession.Mode.SHARE) {
                    String jsonName = base + ".json";
                    Uri jsonUri = writeJson(jsonName, shareCard(pngName));
                    jsonNote = jsonUri != null
                            ? " Scheda " + jsonName + " in Download/StarStacKadam."
                            : " Scheda JSON non salvata.";
                }
                String savedNote = jsonNote;
                main.post(() -> {
                    saveBtn.setEnabled(true);
                    if (uri != null) {
                        Toast.makeText(this, "Salvato: " + pngName, Toast.LENGTH_LONG).show();
                        noteText.setText("PNG in Pictures/StarStacKadam." + savedNote);
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

    private Uri writePng(String name, Bitmap bmp, String description) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.TITLE, caption());
        values.put(MediaStore.Images.Media.DESCRIPTION, description);
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

    private Uri writeJson(String name, String body) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/StarStacKadam");
        }
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) return null;
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return uri;
    }

    private void applyModeChrome() {
        boolean share = mode == StackSession.Mode.SHARE;
        titleView.setText(share ? "Stack condivisibile" : "Anteprima Vespera");
        titleView.setTextColor(share ? SHARE : ACCENT);
        String who = caption();
        HostSettingsStore.StackOptions opt = profile;
        String body;
        if (share) {
            String tuning = opt == null
                    ? ""
                    : "Lato " + opt.maxEdge + " · " + opt.maxStars + " stelle · voti ≥ " + opt.minVotes
                    + " · pose non allineate escluse. ";
            body = tuning
                    + (opt != null && opt.useFits ? "FITS. " : "")
                    + "PNG senza perdita + scheda JSON. ";
        } else {
            body = "Ultimo stack Vespera (*-output.jpg), senza rimediarlo. ";
        }
        subView.setText(body
                + AppVersion.label(this)
                + (who.isEmpty() ? "" : " — " + who));
        saveBtn.setText(share ? "Salva PNG + scheda" : "Salva PNG");
        saveBtn.setBackground(rounded(share ? SHARE : ACCENT, dp(8)));
    }

    private String caption() {
        if (scientificName.isEmpty()) return publicName;
        if (publicName.isEmpty() || publicName.equalsIgnoreCase(scientificName)) return scientificName;
        return scientificName + " — " + publicName;
    }

    private String fileBase() {
        String sci = slug(scientificName);
        String pub = slug(publicName);
        String core;
        if (sci.isEmpty() && pub.isEmpty()) core = "starstackadam";
        else if (pub.isEmpty() || pub.equals(sci)) core = sci;
        else core = sci + "_" + pub;
        String tag = mode == StackSession.Mode.SHARE ? "condiviso" : "live";
        return core + "_" + tag + "_" + System.currentTimeMillis();
    }

    private String shareCard(String pngName) throws Exception {
        int used = Math.max(0, lastTotal - lastUnaligned);
        JSONObject json = new JSONObject();
        json.put("app", "StarStacKadam");
        json.put("version", AppVersion.name(this));
        json.put("mode", "share");
        json.put("method", "MEAN");
        json.put("rejectUnaligned", true);
        json.put("minAlignVotes", 4);
        json.put("workingEdge", 2048);
        json.put("container", "png");
        json.put("bitDepth", 8);
        json.put("source", profile != null && profile.useFits ? "fits" : "jpeg");
        json.put("scientificName", scientificName);
        json.put("publicName", publicName);
        json.put("kind", kindLabel);
        json.put("framesUsed", used);
        json.put("framesRejected", lastUnaligned);
        json.put("png", pngName);
        json.put("created", OffsetDateTime.now().toString());
        return json.toString(2);
    }

    private static String textExtra(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value.trim();
    }

    private static String slug(String value) {
        if (value == null) return "";
        String n = value.trim().replaceAll("[^\\p{L}\\p{N}]+", "-");
        n = n.replaceAll("-{2,}", "-");
        if (n.startsWith("-")) n = n.substring(1);
        if (n.endsWith("-")) n = n.substring(0, n.length() - 1);
        if (n.length() > 60) n = n.substring(0, 60);
        return n;
    }

    private TextView text(String value, float sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private Button pill(String label, boolean on) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        stylePill(b, on);
        return b;
    }

    private void stylePill(Button b, boolean on) {
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(on ? ACCENT : CARD, dp(8)));
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
        TextView label = text(name, 13, true);
        label.setTextColor(TEXT);
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
