package com.starstackadam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stack in primo piano: resta in esecuzione con l'app in secondo piano o lo schermo spento.
 * Si ferma solo con Annulla, oppure quando il lavoro è finito.
 */
public final class StackService extends Service {
    public static final String ACTION_START = "com.starstackadam.action.STACK_START";
    public static final String ACTION_CANCEL = "com.starstackadam.action.STACK_CANCEL";
    public static final String ACTION_RESUME = "com.starstackadam.action.STACK_RESUME";
    public static final String EXTRA_JOB = "stack_job";
    public static final String EXTRA_BACKGROUND = "stack_background";
    public static final String EXTRA_LEVELS = "stack_levels";
    public static final String EXTRA_BLACK = "stack_black";
    public static final String EXTRA_WHITE = "stack_white";
    public static final String EXTRA_GAMMA = "stack_gamma";

    private static final String CHANNEL = "stack";
    private static final int NOTIF_ID = 70;
    private static final long WAKE_MAX_MS = 6L * 60L * 60L * 1000L;

    private static final Object GATE = new Object();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile long activeJob;
    private static volatile boolean running;
    private static volatile StackSession activeSession;
    private static volatile StackProgress last;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private StackSession session;
    private PowerManager.WakeLock wakeLock;
    private int generation;

    public interface Listener {
        void onProgress(StackProgress progress);
    }

    public static void listen(Listener listener) {
        if (listener == null) return;
        LISTENERS.add(listener);
        StackProgress snap = last;
        if (snap != null) listener.onProgress(snap);
    }

    public static void unlisten(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    public static boolean isRunning() {
        return running;
    }

    public static StackSession session() {
        return activeSession;
    }

    public static boolean sameJob(Intent intent) {
        if (intent == null) return false;
        long job = intent.getLongExtra(EXTRA_JOB, 0L);
        return job != 0L && job == activeJob && activeSession != null;
    }

    public static void start(Context context, Intent work) {
        Intent intent = new Intent(context, StackService.class);
        intent.setAction(ACTION_START);
        if (work != null && work.getExtras() != null) intent.putExtras(work.getExtras());
        context.startForegroundService(intent);
    }

    public static void cancel(Context context) {
        Intent intent = new Intent(context, StackService.class);
        intent.setAction(ACTION_CANCEL);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "stack-service");
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            if (session != null) session.requestCancel();
            else stopSelf();
            return START_STICKY;
        }
        ensureChannel();
        startForeground(
                NOTIF_ID,
                notification("Stack in corso", "Avvio…", true, 0, 1, true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (intent != null) begin(intent, startId);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWake();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void begin(Intent intent, int startId) {
        long job = intent.getLongExtra(EXTRA_JOB, 0L);
        if (job == 0L) job = System.currentTimeMillis();
        final long jobId = job;
        final int gen;
        final StackSession jobSession;
        final StackSession.Request request;
        synchronized (GATE) {
            if (jobId == activeJob && running) return;
            generation++;
            gen = generation;
            if (session != null) session.requestCancel();
            session = new StackSession(new FrameCache(this));
            jobSession = session;
            activeSession = session;
            activeJob = jobId;
            running = true;
            last = null;
            acquireWake();
            request = requestFrom(intent);
        }
        executor.execute(() -> {
            StackProgress outcome = null;
            try {
                jobSession.run(request, progress -> {
                    if (gen != generation) return;
                    publish(progress, true);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Stack interrotto" : e.getMessage();
                outcome = StackProgress.failed(0, 0, 0, message);
                publish(outcome, false);
            } finally {
                synchronized (GATE) {
                    if (gen == generation) {
                        running = false;
                        releaseWake();
                        StackProgress done = outcome != null ? outcome : last;
                        showFinished(done);
                        stopSelf(startId);
                    }
                }
            }
        });
    }

    private void publish(StackProgress progress, boolean notify) {
        last = progress;
        if (notify) {
            boolean busy = progress != null && !progress.done;
            String title = busy ? "Stack in corso" : titleFor(progress);
            String body = bodyFor(progress);
            int max = progress == null ? 1 : Math.max(1, progress.frameTotal);
            int now = progress == null ? 0 : progress.frameIndex;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIF_ID, notification(title, body, busy && now < max, now, max, busy));
            }
        }
        main.post(() -> {
            for (Listener listener : LISTENERS) listener.onProgress(progress);
        });
    }

    private void showFinished(StackProgress progress) {
        boolean cancelled = progress != null && progress.cancelled;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }
        if (cancelled) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            manager.cancel(NOTIF_ID);
            return;
        }
        String title = titleFor(progress);
        manager.notify(NOTIF_ID, notification(title, bodyFor(progress), false, 0, 0, false));
        stopForeground(STOP_FOREGROUND_DETACH);
    }

    private String titleFor(StackProgress progress) {
        if (progress == null) return "Stack terminato";
        if (progress.cancelled) return "Stack annullato";
        if (progress.error != null) return "Stack non riuscito";
        if (progress.done) return "Stack completato";
        return "Stack in corso";
    }

    private String bodyFor(StackProgress progress) {
        if (progress == null) return "Apri l'app per il risultato";
        if (progress.error != null && !progress.error.isBlank()) return progress.error;
        if (progress.note != null && !progress.note.isBlank() && progress.done) return progress.note;
        if (progress.frameTotal > 0) {
            return String.format(Locale.ITALY, "Frame %d / %d", progress.frameIndex, progress.frameTotal);
        }
        return progress.note == null ? "" : progress.note;
    }

    private Notification notification(
            String title, String body, boolean indeterminate, int now, int max, boolean ongoing) {
        Intent open = new Intent(this, StackWorkActivity.class);
        open.setAction(ACTION_RESUME);
        open.putExtra(EXTRA_JOB, activeJob);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_stack)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (max > 0) builder.setProgress(max, Math.min(now, max), indeterminate);
        if (ongoing) {
            Intent cancel = new Intent(this, StackService.class);
            cancel.setAction(ACTION_CANCEL);
            PendingIntent cancelPi = PendingIntent.getService(
                    this, 2, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    R.drawable.ic_stat_stack, "Annulla", cancelPi).build());
        }
        return builder.build();
    }

    private StackSession.Request requestFrom(Intent intent) {
        String host = intent.getStringExtra(MainActivity.EXTRA_HOST);
        int port = intent.getIntExtra(MainActivity.EXTRA_PORT, HostSettingsStore.PORT_HD);
        ArrayList<String> remotes = intent.getStringArrayListExtra(MainActivity.EXTRA_REMOTE_PATHS);
        ArrayList<String> locals = intent.getStringArrayListExtra(MainActivity.EXTRA_LOCAL_FILES);
        String modeName = text(intent, MainActivity.EXTRA_MODE);
        StackSession.Mode mode = "SHARE".equals(modeName) ? StackSession.Mode.SHARE : StackSession.Mode.LIVE;
        HostSettingsStore store = new HostSettingsStore(this);
        HostSettingsStore.StackOptions profile = mode == StackSession.Mode.SHARE
                ? store.getShareOptions()
                : store.getLiveOptions();
        boolean background = intent.getBooleanExtra(EXTRA_BACKGROUND, profile.subtractBackground);
        boolean levels = intent.getBooleanExtra(EXTRA_LEVELS, false);
        float black = intent.getFloatExtra(EXTRA_BLACK, 0f);
        float white = intent.getFloatExtra(EXTRA_WHITE, 1f);
        float gamma = intent.getFloatExtra(EXTRA_GAMMA, 1f);
        return new StackSession.Request(
                host,
                port,
                remotes,
                locals,
                background,
                levels,
                black,
                white,
                gamma,
                mode,
                text(intent, MainActivity.EXTRA_SCIENTIFIC),
                text(intent, MainActivity.EXTRA_PUBLIC),
                profile,
                store.getFtpTimeoutSec());
    }

    private void ensureChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Stack", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Lo stack continua anche con l'app in secondo piano");
        manager.createNotificationChannel(channel);
    }

    private void acquireWake() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return;
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "starstackadam:stack");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_MAX_MS);
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private static String text(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value.trim();
    }
}
