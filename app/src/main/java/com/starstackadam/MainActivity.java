package com.starstackadam;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home: tab Oggetti (scan delle cartelle) e tab File FTP (scroll delle directory).
 */
public final class MainActivity extends Activity {
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_REMOTE_PATHS = "remote_paths";
    public static final String EXTRA_LOCAL_FILES = "local_files";
    public static final String EXTRA_MODE = "stack_mode";
    public static final String EXTRA_SCIENTIFIC = "scientific_name";
    public static final String EXTRA_PUBLIC = "public_name";
    public static final String EXTRA_KIND = "object_kind";

    private static final int CARD = 0xA61C222C;
    private static final int TEXT = 0xFFE8EAED;
    private static final int MUTED = 0xFF9AA0A6;
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int SHARE = 0xFF81C995;
    private static final int ONLINE = 0xFF3DDC84;
    private static final int CHECKING = 0xFFFDD663;
    private static final int DANGER = 0xFFF28B82;
    private static final int SELECTED = 0xFF243044;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private ExecutorService probes;
    private HostSettingsStore store;

    private TextView statusView;
    private TextView pathView;
    private LinearLayout listBox;
    private LinearLayout ftpListBox;
    private View objectPane;
    private View ftpPane;
    private Button connectBtn;
    private Button upBtn;
    private Button liveBtn;
    private Button shareBtn;
    private SourceChip hdChip;
    private SourceChip veChip;
    private Button tabObjectsBtn;
    private Button tabFtpBtn;
    private final List<Button> filterButtons = new ArrayList<>();

    private final List<SkyObject> objects = new ArrayList<>();
    private final List<FtpBrowser.Entry> ftpEntries = new ArrayList<>();
    private final Set<String> pickedPaths = new LinkedHashSet<>();
    private SkyObject selected;
    private SkyObject.Kind kindFilter;
    private boolean objectsTab = true;
    private boolean connected;
    private String currentDir = "/";
    private String sessionHost = "";
    private int sessionPort = HostSettingsStore.PORT_HD;
    private String boundEndpoint = "";
    private int connectGen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        probes = Executors.newFixedThreadPool(2);
        store = new HostSettingsStore(this);
        setContentView(AppBackdrop.wrap(this, buildUi()));
        applySourceUi(store.isHdSource());
        currentDir = store.getLastDir();
        pathView.setText(currentDir);
        showTab(true);
        updateStartEnabled();
        boundEndpoint = store.getEndpoint();
        connect();
        probeEndpoints();
        main.post(() -> AppUpdates.check(this, true, null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store == null) return;
        probeEndpoints();
        String now = store.getEndpoint();
        if (!now.equals(boundEndpoint)) {
            boundEndpoint = now;
            connected = false;
            connect();
        }
    }

    @Override
    protected void onDestroy() {
        if (executor != null) executor.shutdownNow();
        if (probes != null) probes.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(8), pad, pad);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        int markSize = dp(36);
        ImageView mark = AppBackdrop.mark(this, markSize);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(markSize, markSize);
        markLp.rightMargin = dp(8);
        titleRow.addView(mark, markLp);
        TextView title = label("StarStacKadam", 20, true);
        title.setTextColor(ACCENT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button settingsBtn = pillButton("Impostazioni", false);
        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        titleRow.addView(settingsBtn);
        root.addView(titleRow);

        TextView sub = label("Oggetti dalle cartelle, oppure File FTP per le directory.", 13, false);
        sub.setTextColor(MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(4), 0, dp(8));
        root.addView(sub);

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        hdChip = new SourceChip("HD");
        veChip = new SourceChip("Vespera");
        hdChip.box.setOnClickListener(v -> switchSource(true));
        veChip.box.setOnClickListener(v -> switchSource(false));
        sourceRow.addView(hdChip.box, rowBtnLp(1));
        sourceRow.addView(space(dp(8)));
        sourceRow.addView(veChip.box, rowBtnLp(1));
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceLp.bottomMargin = dp(8);
        root.addView(sourceRow, sourceLp);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabObjectsBtn = pillButton("Oggetti", true);
        tabFtpBtn = pillButton("File FTP", false);
        tabObjectsBtn.setOnClickListener(v -> showTab(true));
        tabFtpBtn.setOnClickListener(v -> showTab(false));
        tabRow.addView(tabObjectsBtn, rowBtnLp(1));
        tabRow.addView(space(dp(8)));
        tabRow.addView(tabFtpBtn, rowBtnLp(1));
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.bottomMargin = dp(8);
        root.addView(tabRow, tabLp);

        connectBtn = actionButton("Aggiorna elenco", ACCENT);
        connectBtn.setOnClickListener(v -> connect());
        root.addView(connectBtn);

        statusView = label("Connessione…", 13, false);
        statusView.setTextColor(MUTED);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(0, dp(8), 0, dp(6));
        root.addView(statusView);

        FrameLayout pages = new FrameLayout(this);
        objectPane = buildObjectPane();
        ftpPane = buildFtpPane();
        pages.addView(objectPane, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages.addView(ftpPane, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        liveBtn = actionButton("Visione live", ACCENT);
        liveBtn.setOnClickListener(v -> startStack(StackSession.Mode.LIVE));
        shareBtn = actionButton("Stack condivisibile", SHARE);
        shareBtn.setOnClickListener(v -> startStack(StackSession.Mode.SHARE));
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        liveLp.topMargin = dp(12);
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shareLp.topMargin = dp(8);
        root.addView(liveBtn, liveLp);
        root.addView(shareBtn, shareLp);

        TextView version = label(AppVersion.label(this), 15, true);
        version.setTextColor(TEXT);
        version.setShadowLayer(dp(3), 0, dp(1), 0xE0000000);
        version.setGravity(Gravity.END);
        version.setSingleLine(true);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionLp.topMargin = dp(8);
        root.addView(version, versionLp);
        return root;
    }

    private View buildObjectPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);

        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.addView(filterButton("Tutti", null));
        filterRow.addView(space(dp(6)));
        filterRow.addView(filterButton("Galassie", SkyObject.Kind.GALAXY));
        filterRow.addView(space(dp(6)));
        filterRow.addView(filterButton("Stelle", SkyObject.Kind.STAR));
        filterRow.addView(space(dp(6)));
        filterRow.addView(filterButton("Nebulose", SkyObject.Kind.NEBULA));
        filterRow.addView(space(dp(6)));
        filterRow.addView(filterButton("Altri", SkyObject.Kind.OTHER));
        filters.addView(filterRow);
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filterLp.bottomMargin = dp(8);
        pane.addView(filters, filterLp);
        styleFilters();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setBackground(rounded(CARD, dp(10)));
        listBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pane.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return pane;
    }

    private View buildFtpPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);

        pathView = label("/", 12, false);
        pathView.setTextColor(ACCENT);
        pathView.setPadding(0, 0, 0, dp(6));
        pane.addView(pathView);

        upBtn = actionButton("Cartella su", MUTED);
        upBtn.setOnClickListener(v -> goUp());
        upBtn.setEnabled(false);
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        upLp.bottomMargin = dp(8);
        pane.addView(upBtn, upLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        ftpListBox = new LinearLayout(this);
        ftpListBox.setOrientation(LinearLayout.VERTICAL);
        ftpListBox.setBackground(rounded(CARD, dp(10)));
        ftpListBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.addView(ftpListBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pane.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return pane;
    }

    private void showTab(boolean objects) {
        objectsTab = objects;
        objectPane.setVisibility(objects ? View.VISIBLE : View.GONE);
        ftpPane.setVisibility(objects ? View.GONE : View.VISIBLE);
        stylePill(tabObjectsBtn, objects);
        stylePill(tabFtpBtn, !objects);
        connectBtn.setText(objects ? "Aggiorna elenco" : "Aggiorna cartella");
        if (!objects && connected) {
            upBtn.setEnabled(!"/".equals(currentDir));
            if (ftpListBox.getChildCount() == 0) openDir(currentDir);
        }
        updateStartEnabled();
    }

    private void switchSource(boolean hd) {
        if (store.isHdSource() == hd && connected) return;
        store.setHdSource(hd);
        applySourceUi(hd);
        connected = false;
        objects.clear();
        selected = null;
        pickedPaths.clear();
        ftpEntries.clear();
        listBox.removeAllViews();
        ftpListBox.removeAllViews();
        upBtn.setEnabled(false);
        boundEndpoint = store.getEndpoint();
        updateStartEnabled();
        connect();
    }

    private void applySourceUi(boolean hd) {
        hdChip.setSelected(hd);
        veChip.setSelected(!hd);
    }

    private void probeEndpoints() {
        HostSettingsStore.Endpoint hd = HostSettingsStore.Endpoint.parse(
                store.getHdEndpoint(), HostSettingsStore.PORT_HD);
        HostSettingsStore.Endpoint ve = HostSettingsStore.Endpoint.parse(
                store.getVesperaEndpoint(), HostSettingsStore.PORT_VESPERA);
        int timeout = store.getFtpTimeoutSec();
        hdChip.setReachable(null);
        veChip.setReachable(null);
        probes.execute(() -> {
            boolean ok = FtpBrowser.reachable(hd.host, hd.port, timeout);
            main.post(() -> {
                if (!isDestroyed()) hdChip.setReachable(ok);
            });
        });
        probes.execute(() -> {
            boolean ok = FtpBrowser.reachable(ve.host, ve.port, timeout);
            main.post(() -> {
                if (!isDestroyed()) veChip.setReachable(ok);
            });
        });
    }

    private void connect() {
        if (!objectsTab && connected) {
            openDir(currentDir.isEmpty() ? "/" : currentDir);
            return;
        }
        boolean hd = store.isHdSource();
        String sourceName = hd ? "HD" : "Vespera";
        HostSettingsStore.Endpoint endpoint = HostSettingsStore.Endpoint.parse(
                store.getEndpoint(), hd ? HostSettingsStore.PORT_HD : HostSettingsStore.PORT_VESPERA);
        if (endpoint.host.isEmpty()) {
            setStatus("Endpoint " + sourceName + " mancante. Aprilo in Impostazioni.", true);
            return;
        }
        String host = endpoint.host;
        int port = endpoint.port;
        int timeout = store.getFtpTimeoutSec();
        String startDir = store.getLastDir();
        int gen = ++connectGen;
        connectBtn.setEnabled(false);
        setStatus("Lettura oggetti " + sourceName + "…", false);
        executor.execute(() -> {
            try (FtpBrowser ftp = new FtpBrowser()) {
                ftp.connect(host, port, timeout);
                List<SkyObject> listed = ObjectCatalog.scan(ftp, getCacheDir());
                String dir = startDir == null || startDir.isEmpty() ? "/" : startDir;
                List<FtpBrowser.Entry> files;
                try {
                    files = ftp.list(dir);
                    dir = ftp.pwd();
                } catch (Exception ignored) {
                    dir = "/";
                    files = ftp.list("/");
                    dir = ftp.pwd();
                }
                String finalDir = dir;
                List<FtpBrowser.Entry> finalFiles = files;
                main.post(() -> {
                    if (gen != connectGen || isDestroyed()) return;
                    sessionHost = host;
                    sessionPort = port;
                    boundEndpoint = host + ":" + port;
                    currentDir = finalDir;
                    store.setLastDir(finalDir);
                    connected = true;
                    objects.clear();
                    objects.addAll(listed);
                    selected = null;
                    pickedPaths.clear();
                    connectBtn.setEnabled(true);
                    showObjects();
                    showFtpEntries(finalFiles);
                    upBtn.setEnabled(!"/".equals(finalDir));
                    if (listed.isEmpty()) {
                        setStatus(finalFiles.isEmpty()
                                ? "Connesso a " + sourceName + ". Cartella vuota."
                                : "Connesso a " + sourceName + ". Nessuna posa JPEG/PNG.", false);
                    } else {
                        setStatus("Trovati " + listed.size()
                                + " oggetti su " + sourceName + ". Scegline uno.", false);
                    }
                    updateStartEnabled();
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) msg = "Connessione fallita";
                String err = sourceName + ": " + msg;
                main.post(() -> {
                    if (gen != connectGen || isDestroyed()) return;
                    connected = false;
                    objects.clear();
                    selected = null;
                    pickedPaths.clear();
                    connectBtn.setEnabled(true);
                    upBtn.setEnabled(false);
                    listBox.removeAllViews();
                    ftpListBox.removeAllViews();
                    setStatus(err, true);
                    updateStartEnabled();
                });
            }
        });
    }

    private void goUp() {
        if (!connected) return;
        openDir(FtpBrowser.parentPath(currentDir));
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
                ftp.connect(host, port, store.getFtpTimeoutSec());
                List<FtpBrowser.Entry> listed = ftp.list(path);
                String dir = ftp.pwd();
                main.post(() -> {
                    currentDir = dir;
                    store.setLastDir(dir);
                    pickedPaths.clear();
                    showFtpEntries(listed);
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
                    upBtn.setEnabled(connected && !"/".equals(currentDir));
                    setStatus(err, true);
                });
            }
        });
    }

    private void showFtpEntries(List<FtpBrowser.Entry> listed) {
        ftpEntries.clear();
        ftpEntries.addAll(listed);
        ftpListBox.removeAllViews();
        pathView.setText(currentDir);
        if (listed.isEmpty()) {
            TextView empty = label("Cartella vuota", 14, false);
            empty.setTextColor(MUTED);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            ftpListBox.addView(empty);
            return;
        }
        for (FtpBrowser.Entry entry : listed) {
            if (entry.directory) {
                ftpListBox.addView(dirRow(entry));
            } else if (FtpBrowser.isImageName(entry.name)) {
                ftpListBox.addView(fileRow(entry));
            } else if (FtpBrowser.isFitsName(entry.name)) {
                TextView fits = label(entry.name + " (FITS non supportato)", 13, false);
                fits.setTextColor(MUTED);
                fits.setPadding(dp(12), dp(8), dp(12), dp(8));
                ftpListBox.addView(fits);
            }
        }
    }

    private View dirRow(FtpBrowser.Entry entry) {
        TextView row = label("▸  " + entry.name, 15, true);
        row.setTextColor(ACCENT);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setOnClickListener(v -> openDir(entry.path));
        return row;
    }

    private View fileRow(FtpBrowser.Entry entry) {
        CheckBox box = new CheckBox(this);
        box.setText(entry.name + "  (" + formatSize(entry.size) + ")");
        box.setTextColor(TEXT);
        box.setPadding(dp(8), dp(10), dp(8), dp(10));
        box.setChecked(pickedPaths.contains(entry.path));
        box.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) pickedPaths.add(entry.path);
            else pickedPaths.remove(entry.path);
            updateStartEnabled();
        });
        return box;
    }

    private void showObjects() {
        listBox.removeAllViews();
        List<SkyObject> visible = new ArrayList<>();
        for (SkyObject object : objects) {
            if (kindFilter == null || object.kind == kindFilter) visible.add(object);
        }
        if (visible.isEmpty()) {
            TextView empty = label(
                    objects.isEmpty() ? "Nessun oggetto caricato" : "Nessun oggetto in questo filtro",
                    14, false);
            empty.setTextColor(MUTED);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            listBox.addView(empty);
            return;
        }
        SkyObject.Kind header = null;
        for (SkyObject object : visible) {
            if (kindFilter == null && object.kind != header) {
                header = object.kind;
                TextView section = label(sectionTitle(header), 12, true);
                section.setTextColor(ACCENT);
                section.setPadding(dp(12), dp(10), dp(12), dp(4));
                listBox.addView(section);
            }
            listBox.addView(objectRow(object));
        }
    }

    private View objectRow(SkyObject object) {
        boolean on = selected != null && selected.folderPath.equals(object.folderPath);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(rounded(on ? SELECTED : Color.TRANSPARENT, dp(8)));

        String title = object.registeredName.isEmpty() ? object.scientificName : object.registeredName;
        TextView name = label(title, 16, true);
        name.setTextColor(TEXT);
        row.addView(name);
        if (!object.publicName.isEmpty() && !object.publicName.equalsIgnoreCase(title)) {
            TextView common = label(object.publicName, 14, false);
            common.setTextColor(on ? ACCENT : TEXT);
            common.setPadding(0, dp(2), 0, 0);
            row.addView(common);
        }
        TextView meta = label(metaLine(object), 12, false);
        meta.setTextColor(on ? ACCENT : MUTED);
        meta.setPadding(0, dp(2), 0, 0);
        row.addView(meta);
        row.setOnClickListener(v -> {
            selected = object;
            showObjects();
            updateStartEnabled();
            String dates = object.datesLabel();
            StringBuilder status = new StringBuilder(object.registeredName);
            if (!object.publicName.equalsIgnoreCase(object.registeredName)) {
                status.append(" — ").append(object.publicName);
            }
            if (!dates.isEmpty()) status.append(" — ").append(dates);
            status.append(" — ").append(object.frameCount()).append(" pose");
            setStatus(status.toString(), false);
        });
        return row;
    }

    private static String metaLine(SkyObject object) {
        String frames = object.frameCount() + (object.frameCount() == 1 ? " posa" : " pose");
        String dates = object.datesLabel();
        if (dates.isEmpty()) return object.kindLabel() + "  ·  " + frames;
        return dates + "  ·  " + object.kindLabel() + "  ·  " + frames;
    }

    private static String sectionTitle(SkyObject.Kind kind) {
        return switch (kind) {
            case GALAXY -> "Galassie";
            case STAR -> "Stelle";
            case NEBULA -> "Nebulose";
            case OTHER -> "Altri";
        };
    }

    private void startStack(StackSession.Mode mode) {
        if (!connected || sessionHost.isEmpty()) {
            Toast.makeText(this, "Connettiti prima all'FTP", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> paths = new ArrayList<>();
        String scientific = "";
        String pub = "";
        String kind = "";
        if (objectsTab) {
            if (selected == null || selected.imagePaths.isEmpty()) {
                Toast.makeText(this, "Scegli un oggetto con almeno una posa", Toast.LENGTH_SHORT).show();
                return;
            }
            paths.addAll(selected.imagePaths);
            scientific = selected.scientificName;
            pub = selected.publicName;
            kind = selected.kindLabel();
        } else {
            if (pickedPaths.isEmpty()) {
                Toast.makeText(this, "Seleziona almeno un'immagine", Toast.LENGTH_SHORT).show();
                return;
            }
            paths.addAll(pickedPaths);
        }
        Intent intent = new Intent(this, StackWorkActivity.class);
        intent.putExtra(EXTRA_HOST, sessionHost);
        intent.putExtra(EXTRA_PORT, sessionPort);
        intent.putStringArrayListExtra(EXTRA_REMOTE_PATHS, paths);
        intent.putExtra(EXTRA_MODE, mode.name());
        intent.putExtra(EXTRA_SCIENTIFIC, scientific);
        intent.putExtra(EXTRA_PUBLIC, pub);
        intent.putExtra(EXTRA_KIND, kind);
        startActivity(intent);
    }

    private void updateStartEnabled() {
        boolean ok = connected && (objectsTab
                ? selected != null && selected.frameCount() > 0
                : !pickedPaths.isEmpty());
        liveBtn.setEnabled(ok);
        shareBtn.setEnabled(ok);
        liveBtn.setAlpha(ok ? 1f : 0.45f);
        shareBtn.setAlpha(ok ? 1f : 0.45f);
    }

    private void setStatus(String text, boolean error) {
        statusView.setText(text);
        statusView.setTextColor(error ? DANGER : MUTED);
    }

    private Button filterButton(String text, SkyObject.Kind kind) {
        Button b = pillButton(text, kind == null);
        b.setOnClickListener(v -> {
            kindFilter = kind;
            styleFilters();
            showObjects();
        });
        b.setTag(kind);
        filterButtons.add(b);
        return b;
    }

    private void styleFilters() {
        for (Button button : filterButtons) {
            Object tag = button.getTag();
            boolean on = kindFilter == null ? tag == null : tag == kindFilter;
            stylePill(button, on);
        }
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

    private Button pillButton(String text, boolean selected) {
        Button b = new Button(this);
        b.setText(text);
        compact(b);
        stylePill(b, selected);
        return b;
    }

    private void compact(Button b) {
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
    }

    private final class SourceChip {
        final LinearLayout box;
        final View dot;
        final TextView text;

        SourceChip(String label) {
            box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER);
            box.setMinimumHeight(dp(36));
            box.setPadding(dp(10), dp(4), dp(12), dp(4));
            dot = new View(MainActivity.this);
            int size = dp(9);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(size, size);
            dotLp.rightMargin = dp(8);
            box.addView(dot, dotLp);
            text = label(label, 14, true);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(text);
            setReachable(null);
            setSelected(false);
        }

        void setSelected(boolean on) {
            text.setTextColor(on ? Color.WHITE : TEXT);
            box.setBackground(rounded(on ? ACCENT : CARD, dp(8)));
        }

        void setReachable(Boolean online) {
            int color = online == null ? CHECKING : (online ? ONLINE : DANGER);
            GradientDrawable mark = new GradientDrawable();
            mark.setShape(GradientDrawable.OVAL);
            mark.setColor(color);
            dot.setBackground(mark);
            String state = online == null ? "verifica" : (online ? "online" : "offline");
            box.setContentDescription(text.getText() + " " + state);
        }
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
        return new LinearLayout.LayoutParams(
                weight > 0 ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                weight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return (size / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
    }
}
