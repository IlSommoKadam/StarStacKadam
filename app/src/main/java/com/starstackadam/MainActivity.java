package com.starstackadam;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Host Tailscale/LAN, sorgente HD/Vespera, browse FTP multi-select, avvio stack.
 * UI scura programmatica — non clone Singularity.
 */
public final class MainActivity extends Activity {
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_REMOTE_PATHS = "remote_paths";
    public static final String EXTRA_LOCAL_FILES = "local_files";

    private static final int BG = 0xFF121416;
    private static final int CARD = 0xFF1C1F24;
    private static final int TEXT = 0xFFE8EAED;
    private static final int MUTED = 0xFF9AA0A6;
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int DANGER = 0xFFF28B82;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private HostSettingsStore store;

    private EditText hostInput;
    private TextView statusView;
    private TextView pathView;
    private TextView sourceLabel;
    private LinearLayout listBox;
    private Button connectBtn;
    private Button upBtn;
    private Button startBtn;
    private Button sourceHdBtn;
    private Button sourceVespBtn;

    private final List<FtpBrowser.Entry> entries = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private String currentDir = "/";
    private boolean connected;
    private String sessionHost = "";
    private int sessionPort = HostSettingsStore.PORT_HD;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        store = new HostSettingsStore(this);
        setContentView(buildUi());
        hostInput.setText(store.getHost());
        applySourceUi(store.isHdSource());
        currentDir = store.getLastDir();
        pathView.setText(currentDir);
        updateStartEnabled();
    }

    @Override
    protected void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = label("StarStacKadam", 22, true);
        title.setTextColor(ACCENT);
        root.addView(title);

        TextView sub = label("Stack preview (JPEG) — MEAN streaming via FTP batch", 13, false);
        sub.setTextColor(MUTED);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        root.addView(label("Host Tailscale / LAN", 12, false));
        hostInput = new EditText(this);
        hostInput.setHint("es. 100.x.y.z oppure hostname");
        hostInput.setTextColor(TEXT);
        hostInput.setHintTextColor(MUTED);
        hostInput.setBackground(rounded(CARD, dp(8)));
        hostInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setSingleLine(true);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hostLp.topMargin = dp(4);
        hostLp.bottomMargin = dp(10);
        root.addView(hostInput, hostLp);

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceHdBtn = pillButton("HD :2121", true);
        sourceVespBtn = pillButton("Vespera :2122", false);
        sourceHdBtn.setOnClickListener(v -> {
            store.setHdSource(true);
            applySourceUi(true);
            connected = false;
            selected.clear();
            listBox.removeAllViews();
            setStatus("Sorgente HD — riconnetti", false);
            updateStartEnabled();
        });
        sourceVespBtn.setOnClickListener(v -> {
            store.setHdSource(false);
            applySourceUi(false);
            connected = false;
            selected.clear();
            listBox.removeAllViews();
            setStatus("Sorgente Vespera — riconnetti", false);
            updateStartEnabled();
        });
        sourceRow.addView(sourceHdBtn, rowBtnLp(0));
        sourceRow.addView(space(dp(8)));
        sourceRow.addView(sourceVespBtn, rowBtnLp(0));
        root.addView(sourceRow);

        sourceLabel = label("", 12, false);
        sourceLabel.setTextColor(MUTED);
        sourceLabel.setPadding(0, dp(6), 0, dp(8));
        root.addView(sourceLabel);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        connectBtn = actionButton("Connetti", ACCENT);
        connectBtn.setOnClickListener(v -> connect());
        upBtn = actionButton("Cartella su", MUTED);
        upBtn.setOnClickListener(v -> goUp());
        upBtn.setEnabled(false);
        actionRow.addView(connectBtn, rowBtnLp(1));
        actionRow.addView(space(dp(8)));
        actionRow.addView(upBtn, rowBtnLp(1));
        root.addView(actionRow);

        statusView = label("Inserisci host e premi Connetti.", 13, false);
        statusView.setTextColor(MUTED);
        statusView.setPadding(0, dp(10), 0, dp(4));
        root.addView(statusView);

        pathView = label("/", 12, false);
        pathView.setTextColor(ACCENT);
        pathView.setPadding(0, 0, 0, dp(8));
        root.addView(pathView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setBackground(rounded(CARD, dp(10)));
        listBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        startBtn = actionButton("Avvia stack", ACCENT);
        startBtn.setOnClickListener(v -> startStack());
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startLp.topMargin = dp(12);
        root.addView(startBtn, startLp);

        return root;
    }

    private void applySourceUi(boolean hd) {
        stylePill(sourceHdBtn, hd);
        stylePill(sourceVespBtn, !hd);
        int port = hd ? HostSettingsStore.PORT_HD : HostSettingsStore.PORT_VESPERA;
        sourceLabel.setText(hd
                ? "FTP Helper HD — porta " + port + " (multi-select batch)"
                : "FTP telescopio — porta " + port + " (multi-select batch, no live poll)");
    }

    private void connect() {
        String host = hostInput.getText() == null ? "" : hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            setStatus("Host obbligatorio", true);
            return;
        }
        store.setHost(host);
        int port = store.getPort();
        connectBtn.setEnabled(false);
        setStatus("Connessione a " + host + ":" + port + "…", false);
        executor.execute(() -> {
            try (FtpBrowser ftp = new FtpBrowser()) {
                ftp.connect(host, port);
                String start = store.getLastDir();
                List<FtpBrowser.Entry> listed;
                try {
                    listed = ftp.list(start);
                } catch (Exception e) {
                    start = "/";
                    listed = ftp.list("/");
                }
                String dir = ftp.pwd();
                List<FtpBrowser.Entry> finalListed = listed;
                String finalDir = dir;
                main.post(() -> {
                    sessionHost = host;
                    sessionPort = port;
                    currentDir = finalDir;
                    store.setLastDir(finalDir);
                    connected = true;
                    selected.clear();
                    showEntries(finalListed);
                    upBtn.setEnabled(!"/".equals(finalDir));
                    connectBtn.setEnabled(true);
                    setStatus("Connesso — seleziona JPEG/PNG e Avvia stack", false);
                    updateStartEnabled();
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) msg = "Connessione fallita";
                String err = msg;
                main.post(() -> {
                    connected = false;
                    connectBtn.setEnabled(true);
                    upBtn.setEnabled(false);
                    listBox.removeAllViews();
                    setStatus(err, true);
                    updateStartEnabled();
                });
            }
        });
    }

    private void goUp() {
        if (!connected) return;
        String parent = FtpBrowser.parentPath(currentDir);
        openDir(parent);
    }

    private void openDir(String path) {
        if (!connected) return;
        connectBtn.setEnabled(false);
        upBtn.setEnabled(false);
        setStatus("Apertura " + path + "…", false);
        String host = sessionHost;
        int port = sessionPort;
        executor.execute(() -> {
            try (FtpBrowser ftp = new FtpBrowser()) {
                ftp.connect(host, port);
                List<FtpBrowser.Entry> listed = ftp.list(path);
                String dir = ftp.pwd();
                main.post(() -> {
                    currentDir = dir;
                    store.setLastDir(dir);
                    selected.clear();
                    showEntries(listed);
                    upBtn.setEnabled(!"/".equals(dir));
                    connectBtn.setEnabled(true);
                    setStatus("Cartella: " + dir, false);
                    updateStartEnabled();
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) msg = "LIST fallito";
                String err = msg;
                main.post(() -> {
                    connectBtn.setEnabled(true);
                    upBtn.setEnabled(!"/".equals(currentDir));
                    setStatus(err, true);
                });
            }
        });
    }

    private void showEntries(List<FtpBrowser.Entry> listed) {
        entries.clear();
        entries.addAll(listed);
        listBox.removeAllViews();
        pathView.setText(currentDir);
        if (listed.isEmpty()) {
            TextView empty = label("Cartella vuota", 14, false);
            empty.setTextColor(MUTED);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            listBox.addView(empty);
            return;
        }
        for (FtpBrowser.Entry entry : listed) {
            if (entry.directory) {
                listBox.addView(dirRow(entry));
            } else if (FtpBrowser.isImageName(entry.name)) {
                listBox.addView(fileRow(entry));
            } else if (FtpBrowser.isFitsName(entry.name)) {
                TextView fits = label(entry.name + " (FITS non supportato)", 13, false);
                fits.setTextColor(MUTED);
                fits.setPadding(dp(12), dp(8), dp(12), dp(8));
                listBox.addView(fits);
            }
        }
    }

    private View dirRow(FtpBrowser.Entry entry) {
        TextView row = label("📁  " + entry.name, 15, true);
        row.setTextColor(ACCENT);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setOnClickListener(v -> openDir(entry.path));
        return row;
    }

    private View fileRow(FtpBrowser.Entry entry) {
        CheckBox box = new CheckBox(this);
        box.setText(entry.name + "  (" + formatSize(entry.size) + ")");
        box.setTextColor(TEXT);
        box.setPadding(dp(8), dp(10), dp(8), dp(10));
        box.setChecked(selected.contains(entry.path));
        box.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) selected.add(entry.path);
            else selected.remove(entry.path);
            updateStartEnabled();
        });
        return box;
    }

    private void startStack() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Seleziona almeno un'immagine", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!connected || sessionHost.isEmpty()) {
            Toast.makeText(this, "Connettiti prima all'FTP", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> paths = new ArrayList<>(selected);
        Intent intent = new Intent(this, StackWorkActivity.class);
        intent.putExtra(EXTRA_HOST, sessionHost);
        intent.putExtra(EXTRA_PORT, sessionPort);
        intent.putStringArrayListExtra(EXTRA_REMOTE_PATHS, paths);
        startActivity(intent);
    }

    private void updateStartEnabled() {
        startBtn.setEnabled(connected && !selected.isEmpty());
        startBtn.setAlpha(startBtn.isEnabled() ? 1f : 0.45f);
    }

    private void setStatus(String text, boolean error) {
        statusView.setText(text);
        statusView.setTextColor(error ? DANGER : MUTED);
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
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(color, dp(8)));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        return b;
    }

    private Button pillButton(String text, boolean selected) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        stylePill(b, selected);
        return b;
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

    private LinearLayout.LayoutParams rowBtnLp(int weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                weight > 0 ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                weight);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return (size / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
    }
}
