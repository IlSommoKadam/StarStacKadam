package com.starstackadam;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Endpoint FTP di HD e Vespera, e profili dei due stack (prestazioni / qualità).
 */
public final class SettingsActivity extends Activity {
    private static final int CARD = 0xE61C1F24;
    private static final int TEXT = 0xFFE8EAED;
    private static final int MUTED = 0xFF9AA0A6;
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int SHARE = 0xFF81C995;
    private static final int ONLINE = 0xFF3DDC84;
    private static final int CHECKING = 0xFFFDD663;
    private static final int OFFLINE = 0xFFF28B82;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService probes;
    private HostSettingsStore store;
    private EditText hdEndpoint;
    private EditText veEndpoint;
    private View hdDot;
    private View veDot;

    private Picker timeoutPicker;
    private EditText megaLink;
    private TextView updateNote;
    private Picker liveEdge;
    private Picker liveStars;
    private Picker liveVotes;
    private CheckBox liveReject;
    private CheckBox liveBg;
    private TextView liveSummary;
    private Button livePerfBtn;
    private Button liveBalancedBtn;
    private Button liveSharpBtn;

    private Picker shareEdge;
    private Picker shareStars;
    private Picker shareVotes;
    private CheckBox shareReject;
    private CheckBox shareBg;
    private CheckBox shareFits;
    private TextView shareSummary;
    private Button shareFastBtn;
    private Button shareQualityBtn;
    private Button shareMaxBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new HostSettingsStore(this);
        probes = Executors.newFixedThreadPool(2);
        setContentView(AppBackdrop.wrap(this, buildUi()));
        loadFromStore();
        probeEndpoints();
    }

    @Override
    protected void onDestroy() {
        if (probes != null) probes.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = actionButton("Chiudi", CARD);
        back.setTextColor(TEXT);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = label("Impostazioni", 20, true);
        title.setTextColor(ACCENT);
        title.setSingleLine(true);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(12), 0, dp(12));

        body.addView(section("Endpoint"));
        body.addView(hint("HD e Vespera separati. Pallino verde se l'FTP risponde."));
        hdEndpoint = field(HostSettingsStore.DEFAULT_HD_ENDPOINT,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        veEndpoint = field(HostSettingsStore.DEFAULT_VESPERA_ENDPOINT,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hdDot = statusDot();
        veDot = statusDot();
        body.addView(endpointLabel("Endpoint HD", hdDot));
        body.addView(hdEndpoint, fieldLp());
        body.addView(endpointLabel("Endpoint Vespera", veDot));
        body.addView(veEndpoint, fieldLp());
        hdEndpoint.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) probeEndpoints();
        });
        veEndpoint.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) probeEndpoints();
        });

        body.addView(fieldLabel("Timeout FTP (secondi)"));
        timeoutPicker = picker(HostSettingsStore.FTP_TIMEOUTS, 12, this::refreshSummaries);
        body.addView(timeoutPicker.view);

        body.addView(section("Aggiornamenti"));
        body.addView(hint("Versione installata: " + AppVersion.name(this)
                + ". Il controllo parte all'avvio. Se l'app resta aperta, si ripete quando torna visibile "
                + "e sono passate almeno 24 ore. Puoi sempre premere Controlla aggiornamenti."));
        body.addView(fieldLabel("Link della cartella aggiornamenti"));
        megaLink = field("https://mega.nz/folder/…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        megaLink.setText(AppUpdates.versionUrl(this));
        body.addView(megaLink, fieldLp());
        Button saveLink = actionButton("Salva link", CARD);
        saveLink.setTextColor(TEXT);
        saveLink.setOnClickListener(v -> {
            AppUpdates.saveLinks(this, linkText(), "");
            updateNote.setText("Link salvato");
        });
        body.addView(saveLink, bottomLp(8));
        updateNote = hint(AppUpdates.lastCheck(this));
        body.addView(updateNote);
        Button checkBtn = actionButton("Controlla aggiornamenti", ACCENT);
        checkBtn.setOnClickListener(v -> {
            AppUpdates.saveLinks(this, linkText(), "");
            AppUpdates.check(this, false, updateNote);
        });
        body.addView(checkBtn, bottomLp(8));

        body.addView(section("Anteprima Vespera"));
        body.addView(hint(
                "Mostra l'ultimo stack del telescopio (*-output.jpg), senza rimediarlo. "
                        + "Il lato lungo vale solo per il ridimensionamento a schermo."));
        LinearLayout livePresets = new LinearLayout(this);
        livePresets.setOrientation(LinearLayout.HORIZONTAL);
        livePerfBtn = presetButton("Prestazioni");
        liveBalancedBtn = presetButton("Bilanciato");
        liveSharpBtn = presetButton("Nitido");
        livePerfBtn.setOnClickListener(v -> applyLive(HostSettingsStore.StackOptions.livePerformance()));
        liveBalancedBtn.setOnClickListener(v -> applyLive(HostSettingsStore.StackOptions.liveBalanced()));
        liveSharpBtn.setOnClickListener(v -> applyLive(HostSettingsStore.StackOptions.liveSharp()));
        livePresets.addView(livePerfBtn, rowLp());
        livePresets.addView(space(dp(6)));
        livePresets.addView(liveBalancedBtn, rowLp());
        livePresets.addView(space(dp(6)));
        livePresets.addView(liveSharpBtn, rowLp());
        body.addView(livePresets, bottomLp(8));

        body.addView(fieldLabel("Lato lungo (px)"));
        liveEdge = picker(HostSettingsStore.StackOptions.LIVE_EDGES, 1280, this::refreshSummaries);
        body.addView(liveEdge.view);
        body.addView(fieldLabel("Stelle per l'allineamento"));
        liveStars = picker(HostSettingsStore.StackOptions.STARS, 40, this::refreshSummaries);
        body.addView(liveStars.view);
        body.addView(fieldLabel("Voti minimi"));
        liveVotes = picker(HostSettingsStore.StackOptions.VOTES, 4, this::refreshSummaries);
        body.addView(liveVotes.view);
        liveReject = check("Escludi le pose sotto soglia");
        liveBg = check("Sottrai il fondo all'avvio");
        body.addView(liveReject);
        body.addView(liveBg);
        liveSummary = hint("");
        liveSummary.setTextColor(ACCENT);
        body.addView(liveSummary);

        body.addView(section("Stack condivisibile — qualità"));
        body.addView(hint(
                "Risoluzione più alta. Le pose con pochi voti di allineamento, "
                        + "di solito, non entrano nello stack. Massima arriva a 2560 px e sottrae il fondo."));
        LinearLayout sharePresets = new LinearLayout(this);
        sharePresets.setOrientation(LinearLayout.HORIZONTAL);
        shareFastBtn = presetButton("Veloce");
        shareQualityBtn = presetButton("Qualità");
        shareMaxBtn = presetButton("Massima");
        shareFastBtn.setOnClickListener(v -> applyShare(HostSettingsStore.StackOptions.shareFast()));
        shareQualityBtn.setOnClickListener(v -> applyShare(HostSettingsStore.StackOptions.shareQuality()));
        shareMaxBtn.setOnClickListener(v -> applyShare(HostSettingsStore.StackOptions.shareMax()));
        sharePresets.addView(shareFastBtn, rowLp());
        sharePresets.addView(space(dp(6)));
        sharePresets.addView(shareQualityBtn, rowLp());
        sharePresets.addView(space(dp(6)));
        sharePresets.addView(shareMaxBtn, rowLp());
        body.addView(sharePresets, bottomLp(8));

        body.addView(fieldLabel("Lato lungo (px)"));
        shareEdge = picker(HostSettingsStore.StackOptions.SHARE_EDGES, 2048, this::refreshSummaries);
        body.addView(shareEdge.view);
        body.addView(fieldLabel("Stelle per l'allineamento"));
        shareStars = picker(HostSettingsStore.StackOptions.STARS, 40, this::refreshSummaries);
        body.addView(shareStars.view);
        body.addView(fieldLabel("Voti minimi"));
        shareVotes = picker(HostSettingsStore.StackOptions.VOTES, 4, this::refreshSummaries);
        body.addView(shareVotes.view);
        shareReject = check("Escludi le pose sotto soglia");
        shareBg = check("Sottrai il fondo all'avvio");
        shareFits = check("FITS (opzione avanzata)");
        body.addView(shareReject);
        body.addView(shareBg);
        body.addView(shareFits);
        body.addView(hint(
                "Solo lo stack condivisibile. I FITS pesano di più da scaricare; "
                        + "il lato lungo resta quello scelto sopra, il live resta sui JPEG."));
        shareSummary = hint("");
        shareSummary.setTextColor(SHARE);
        body.addView(shareSummary);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, pad);
        Button reset = actionButton("Predefiniti", CARD);
        reset.setTextColor(TEXT);
        reset.setOnClickListener(v -> applyDefaults());
        Button save = actionButton("Salva", ACCENT);
        save.setOnClickListener(v -> saveAndClose());
        actions.addView(reset, rowLp());
        actions.addView(space(dp(8)));
        actions.addView(save, rowLp());
        root.addView(actions);
        return root;
    }

    private void loadFromStore() {
        hdEndpoint.setText(store.getHdEndpoint());
        veEndpoint.setText(store.getVesperaEndpoint());
        timeoutPicker.select(store.getFtpTimeoutSec());
        applyLive(store.getLiveOptions());
        applyShare(store.getShareOptions());
        shareFits.setChecked(store.getShareOptions().useFits);
    }

    private void applyDefaults() {
        hdEndpoint.setText(HostSettingsStore.DEFAULT_HD_ENDPOINT);
        veEndpoint.setText(HostSettingsStore.DEFAULT_VESPERA_ENDPOINT);
        timeoutPicker.select(12);
        applyLive(HostSettingsStore.StackOptions.liveBalanced());
        applyShare(HostSettingsStore.StackOptions.shareQuality());
        shareFits.setChecked(false);
        probeEndpoints();
        Toast.makeText(this, "Modulo riportato ai predefiniti. Premi Salva per tenerli.", Toast.LENGTH_SHORT).show();
    }

    private void applyLive(HostSettingsStore.StackOptions options) {
        liveEdge.select(options.maxEdge);
        liveStars.select(options.maxStars);
        liveVotes.select(options.minVotes);
        liveReject.setChecked(options.rejectUnaligned);
        liveBg.setChecked(options.subtractBackground);
        refreshSummaries();
    }

    private void applyShare(HostSettingsStore.StackOptions options) {
        shareEdge.select(options.maxEdge);
        shareStars.select(options.maxStars);
        shareVotes.select(options.minVotes);
        shareReject.setChecked(options.rejectUnaligned);
        shareBg.setChecked(options.subtractBackground);
        refreshSummaries();
    }

    private void refreshSummaries() {
        if (liveSummary == null || shareSummary == null || liveReject == null || shareReject == null) return;
        HostSettingsStore.StackOptions live = currentLive();
        HostSettingsStore.StackOptions share = currentShare();
        if (liveSummary != null) liveSummary.setText(live.summary());
        if (shareSummary != null) shareSummary.setText(share.summary());
        stylePreset(livePerfBtn, live.same(HostSettingsStore.StackOptions.livePerformance()));
        stylePreset(liveBalancedBtn, live.same(HostSettingsStore.StackOptions.liveBalanced()));
        stylePreset(liveSharpBtn, live.same(HostSettingsStore.StackOptions.liveSharp()));
        stylePreset(shareFastBtn, share.same(HostSettingsStore.StackOptions.shareFast()));
        stylePreset(shareQualityBtn, share.same(HostSettingsStore.StackOptions.shareQuality()));
        stylePreset(shareMaxBtn, share.same(HostSettingsStore.StackOptions.shareMax()));
    }

    private HostSettingsStore.StackOptions currentLive() {
        return HostSettingsStore.StackOptions.live(
                liveEdge.selected,
                liveStars.selected,
                liveVotes.selected,
                liveReject.isChecked(),
                liveBg.isChecked());
    }

    private HostSettingsStore.StackOptions currentShare() {
        return HostSettingsStore.StackOptions.share(
                shareEdge.selected,
                shareStars.selected,
                shareVotes.selected,
                shareReject.isChecked(),
                shareBg.isChecked(),
                shareFits != null && shareFits.isChecked());
    }

    private void saveAndClose() {
        if (!acceptableEndpoint(textOf(hdEndpoint), HostSettingsStore.PORT_HD)
                || !acceptableEndpoint(textOf(veEndpoint), HostSettingsStore.PORT_VESPERA)) {
            Toast.makeText(this, "Endpoint non valido. Usa host:porta, ad esempio raspe:2121", Toast.LENGTH_SHORT).show();
            return;
        }
        store.saveSettings(
                textOf(hdEndpoint),
                textOf(veEndpoint),
                timeoutPicker.selected,
                currentLive(),
                currentShare());
        AppUpdates.saveLinks(this, linkText(), "");
        Toast.makeText(this, "Impostazioni salvate", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void probeEndpoints() {
        if (hdDot == null || veDot == null || probes == null) return;
        HostSettingsStore.Endpoint hd = HostSettingsStore.Endpoint.parse(
                textOf(hdEndpoint), HostSettingsStore.PORT_HD);
        HostSettingsStore.Endpoint ve = HostSettingsStore.Endpoint.parse(
                textOf(veEndpoint), HostSettingsStore.PORT_VESPERA);
        int timeout = timeoutPicker == null ? store.getFtpTimeoutSec() : timeoutPicker.selected;
        paintDot(hdDot, null);
        paintDot(veDot, null);
        probes.execute(() -> {
            boolean ok = FtpBrowser.reachable(hd.host, hd.port, timeout);
            main.post(() -> {
                if (!isDestroyed()) paintDot(hdDot, ok);
            });
        });
        probes.execute(() -> {
            boolean ok = FtpBrowser.reachable(ve.host, ve.port, timeout);
            main.post(() -> {
                if (!isDestroyed()) paintDot(veDot, ok);
            });
        });
    }

    private static boolean acceptableEndpoint(String raw, int defaultPort) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return true;
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon >= text.length() - 1) {
            return HostSettingsStore.Endpoint.parse(text, defaultPort).host.length() > 0;
        }
        try {
            int port = Integer.parseInt(text.substring(colon + 1).trim());
            return port > 0 && port < 65536 && colon > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String textOf(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private String linkText() {
        return megaLink.getText() == null ? "" : megaLink.getText().toString().trim();
    }

    private Picker picker(int[] values, int selected, Runnable onChange) {
        return new Picker(values, selected, onChange);
    }

    private final class Picker {
        final View view;
        final int[] values;
        final Button[] buttons;
        int selected;

        Picker(int[] values, int selected, Runnable onChange) {
            this.values = values;
            this.selected = HostSettingsStore.StackOptions.nearest(selected, values);
            buttons = new Button[values.length];
            LinearLayout row = new LinearLayout(SettingsActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < values.length; i++) {
                int value = values[i];
                Button b = pillButton(String.valueOf(value), value == this.selected);
                int index = i;
                b.setOnClickListener(v -> {
                    this.selected = values[index];
                    restyle();
                    onChange.run();
                });
                buttons[i] = b;
                if (i > 0) row.addView(space(dp(6)));
                row.addView(b);
            }
            HorizontalScrollView scroll = new HorizontalScrollView(SettingsActivity.this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            scroll.setLayoutParams(lp);
            view = scroll;
            restyle();
        }

        void select(int value) {
            selected = HostSettingsStore.StackOptions.nearest(value, values);
            restyle();
        }

        void restyle() {
            for (int i = 0; i < buttons.length; i++) {
                stylePill(buttons[i], values[i] == selected);
            }
        }
    }

    private TextView section(String text) {
        TextView tv = label(text, 16, true);
        tv.setTextColor(TEXT);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = label(text, 12, false);
        tv.setTextColor(MUTED);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private TextView fieldLabel(String text) {
        TextView tv = label(text, 12, false);
        tv.setTextColor(MUTED);
        tv.setSingleLine(true);
        tv.setPadding(0, dp(2), 0, dp(4));
        return tv;
    }

    private View endpointLabel(String title, View dot) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = fieldLabel(title);
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams dotLp = (LinearLayout.LayoutParams) dot.getLayoutParams();
        dotLp.leftMargin = dp(8);
        row.addView(dot, dotLp);
        return row;
    }

    private View statusDot() {
        View dot = new View(this);
        int size = dp(10);
        dot.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        paintDot(dot, null);
        return dot;
    }

    private void paintDot(View dot, Boolean online) {
        int color = online == null ? CHECKING : (online ? ONLINE : OFFLINE);
        GradientDrawable mark = new GradientDrawable();
        mark.setShape(GradientDrawable.OVAL);
        mark.setColor(color);
        dot.setBackground(mark);
        dot.setContentDescription(online == null ? "verifica" : (online ? "online" : "offline"));
    }

    private EditText field(String hintText, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hintText);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setBackground(rounded(CARD, dp(8)));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setInputType(inputType);
        input.setSingleLine(true);
        return input;
    }

    private CheckBox check(String text) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(TEXT);
        BlueCheck.apply(box);
        box.setOnCheckedChangeListener((button, checked) -> refreshSummaries());
        return box;
    }

    private Button presetButton(String text) {
        return pillButton(text, false);
    }

    private void stylePreset(Button button, boolean on) {
        if (button == null) return;
        stylePill(button, on);
    }

    private TextView label(String text, float sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private Button actionButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        compact(b);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(color, dp(8)));
        return b;
    }

    private Button pillButton(String text, boolean on) {
        Button b = new Button(this);
        b.setText(text);
        compact(b);
        stylePill(b, on);
        return b;
    }

    private void compact(Button b) {
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
    }

    private void stylePill(Button b, boolean on) {
        b.setTextColor(on ? Color.WHITE : TEXT);
        b.setBackground(rounded(on ? ACCENT : CARD, dp(8)));
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

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private LinearLayout.LayoutParams bottomLp(int dpBottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(dpBottom);
        return lp;
    }

    private LinearLayout.LayoutParams rowLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
