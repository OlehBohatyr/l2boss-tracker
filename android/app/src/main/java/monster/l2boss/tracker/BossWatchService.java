package monster.l2boss.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Фоновая служба: раз в N секунд опрашивает API, при новом убийстве босса
 * показывает уведомление и (если включён тумблер) играет мелодию.
 * Также напоминает об открытии окна респа.
 */
public class BossWatchService extends Service {

    public static final String API = "https://s.l2lb.monster/api/v1/asterios/bosses";
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_POLL = "poll";
    public static final String ACTION_RELOAD = "reload";
    public static final String ACTION_TEST_SOUND = "test_sound";

    static final String CH_WATCH = "watch";
    static final String CH_KILL = "kill";
    static final String CH_WINDOW = "window";
    static final int FG_ID = 1;

    /** Настройки, которые пишет интерфейс (плагин Preferences). */
    static final String CFG = "CapacitorStorage";
    /** Внутреннее состояние службы. */
    static final String ST = "bosswatch";

    static volatile boolean running = false;

    private ScheduledExecutorService exec;
    private ScheduledFuture<?> task;
    private PowerManager.WakeLock wakeLock;
    private MediaPlayer player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String fgText = "Запуск…";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
        exec = Executors.newSingleThreadScheduledExecutor();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "l2boss:watch");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_START;
        switch (action) {
            case ACTION_STOP:
                cfg(this).edit().putString("bw.enabled", "0").apply();
                shutdown();
                return START_NOT_STICKY;
            case ACTION_TEST_SOUND:
                playSound();
                if (!running) schedule();
                break;
            case ACTION_POLL:
                if (!running) schedule(); else exec.execute(this::poll);
                break;
            case ACTION_RELOAD:
            case ACTION_START:
            default:
                cfg(this).edit().putString("bw.enabled", "1").apply();
                schedule();
                break;
        }
        return START_STICKY;
    }

    private void schedule() {
        if (task != null) task.cancel(false);
        long sec = Math.max(30, parseLong(cfg(this).getString("bw.interval", "60"), 60));
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        task = exec.scheduleWithFixedDelay(this::poll, 0, sec, TimeUnit.SECONDS);
        running = true;
    }

    private void shutdown() {
        running = false;
        if (task != null) task.cancel(false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (task != null) task.cancel(false);
        exec.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------- Опрос ----------------

    private void poll() {
        SharedPreferences st = getSharedPreferences(ST, MODE_PRIVATE);
        SharedPreferences cfg = cfg(this);
        long now = System.currentTimeMillis();
        try {
            String[] res = fetchBosses();
            String body = res[0];
            JSONArray arr = new JSONArray(body);
            Set<String> sound = splitSet(cfg.getString("bw.sound", ""));
            boolean firstRun = !st.contains("lastOk");
            boolean play = false;
            SharedPreferences.Editor ed = st.edit();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String token = o.optString("boss_token", o.optString("name", "boss" + i));
                String kill = o.optString("last_kill_at", "");
                if (kill.isEmpty() || "null".equals(kill)) continue;
                String prev = st.getString("kill_" + token, null);
                if (!firstRun && prev != null && !kill.equals(prev)) {
                    boolean withSound = sound.contains(token);
                    notifyKill(o, token, kill, withSound, i);
                    if (withSound) play = true;
                    ed.putLong("killSeen_" + token, now);
                }
                ed.putString("kill_" + token, kill);
            }
            ed.putString("lastJson", body).putString("source", res[1]).putLong("lastOk", now).remove("lastError").apply();
            if (play) main.post(this::playSound);
            checkWindows(arr, cfg, st, now);
            updateForeground(arr, cfg, now);
        } catch (Exception e) {
            st.edit().putString("lastError", e.getClass().getSimpleName() + ": " + e.getMessage()).putLong("lastFail", now).apply();
            fgText = "Нет связи с сервером, повторю позже";
            postForeground();
        }
    }

    public static final String ASTERIOS = "https://asterios.tm/index.php?cmd=rss&serv=0&filter=keyboss";
    public static final String[] TOKENS = {"Cabrio", "Hallate", "Kernon", "Golkonda"};

    /** Возвращает {json, источник}. Сначала официальный сайт, потом l2lb. */
    static String[] fetchBosses() throws Exception {
        Exception first = null;
        try {
            String html = httpGet(ASTERIOS, "https://asterios.tm/");
            String json = parseAsterios(html);
            if (json != null) return new String[]{json, "asterios.tm"};
            first = new Exception("asterios.tm: не нашёл боссов на странице");
        } catch (Exception e) { first = e; }
        try {
            String json = httpGet(API, "https://l2lb.monster/respawn-asterios-subclass-rb-x5/");
            new JSONArray(json); // проверка формата
            return new String[]{json, "l2lb.monster"};
        } catch (Exception e) {
            throw new Exception("asterios.tm: " + first.getMessage() + "; l2lb: " + e.getMessage());
        }
    }

    private static final java.util.regex.Pattern ENTRY = java.util.regex.Pattern.compile(
            "(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d):\\s*Убит босс ([^<]+?)</a>.*?<td>([^<]*?)</td>",
            java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern KILLER = java.util.regex.Pattern.compile(
            "нанес (.+?)(?: из клана (.+?))?\\.?\\s*$");

    /** Разбирает ленту «Ключевые боссы» с asterios.tm: берёт последнее убийство каждого саб РБ. */
    static String parseAsterios(String html) throws Exception {
        java.util.regex.Matcher m = ENTRY.matcher(html);
        JSONObject[] found = new JSONObject[TOKENS.length];
        int entries = 0;
        while (m.find()) {
            entries++;
            String time = m.group(1), boss = unescape(m.group(2)).trim(), desc = unescape(m.group(3)).trim();
            for (int i = 0; i < TOKENS.length; i++) {
                if (found[i] != null || !boss.contains(TOKENS[i])) continue;
                JSONObject o = new JSONObject();
                o.put("boss_token", TOKENS[i]);
                o.put("name", TOKENS[i]);
                o.put("full_name", boss);
                o.put("last_kill_at", time);
                java.util.regex.Matcher k = KILLER.matcher(desc);
                if (k.find()) {
                    o.put("killer_name", k.group(1).trim());
                    o.put("clan_name", k.group(2) != null ? k.group(2).trim() : JSONObject.NULL);
                }
                found[i] = o;
            }
        }
        if (entries == 0) return null;
        JSONArray arr = new JSONArray();
        for (JSONObject o : found) if (o != null) arr.put(o);
        return arr.length() > 0 ? arr.toString() : null;
    }

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&#039;", "'").replace("&#39;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
    }

    static String httpGet(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8");
        c.setRequestProperty("Accept-Language", "ru,en;q=0.8");
        if (referer != null) {
            c.setRequestProperty("Referer", referer);
            Uri r = Uri.parse(referer);
            c.setRequestProperty("Origin", r.getScheme() + "://" + r.getHost());
        }
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ---------------- Окна респа ----------------

    private void checkWindows(JSONArray arr, SharedPreferences cfg, SharedPreferences st, long now) throws Exception {
        if (!"1".equals(cfg.getString("bw.notifyOpen", "1"))) return;
        double openH = parseDouble(cfg.getString("bw.openH", "18"), 18);
        double closeH = parseDouble(cfg.getString("bw.closeH", "30"), 30);
        long before = parseLong(cfg.getString("bw.notifyBefore", "10"), 10) * 60000L;
        Set<String> watch = splitSet(cfg.getString("bw.watch", "Cabrio,Hallate,Kernon,Golkonda"));
        SharedPreferences.Editor ed = st.edit();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String token = o.optString("boss_token", o.optString("name", "boss" + i));
            String name = o.optString("name", token);
            String kill = o.optString("last_kill_at", "");
            if (!watch.contains(token)) continue;
            long k = parseMsk(kill);
            if (k <= 0) continue;
            long open = k + (long) (openH * 3600000L), close = k + (long) (closeH * 3600000L);
            if (now >= close) continue;
            if (now >= open) {
                if (!kill.equals(st.getString("open_" + token, null))) {
                    notifySimple(CH_WINDOW, 200 + i, "⚔ " + name + ": окно открыто", "Может появиться до " + hhmm(close));
                    ed.putString("open_" + token, kill).putString("pre_" + token, kill);
                }
            } else if (before > 0 && now >= open - before) {
                if (!kill.equals(st.getString("pre_" + token, null))) {
                    long mins = Math.max(1, (open - now + 59999) / 60000);
                    notifySimple(CH_WINDOW, 200 + i, "⏳ " + name + ": окно через " + mins + " мин", "Откроется в " + hhmm(open) + ", закроется в " + hhmm(close));
                    ed.putString("pre_" + token, kill);
                }
            }
        }
        ed.apply();
    }

    private void updateForeground(JSONArray arr, SharedPreferences cfg, long now) throws Exception {
        double openH = parseDouble(cfg.getString("bw.openH", "18"), 18);
        double closeH = parseDouble(cfg.getString("bw.closeH", "30"), 30);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            long k = parseMsk(o.optString("last_kill_at", ""));
            if (k <= 0) continue;
            long open = k + (long) (openH * 3600000L), close = k + (long) (closeH * 3600000L);
            if (sb.length() > 0) sb.append(" · ");
            sb.append(o.optString("name", "?")).append(' ');
            if (now < open) sb.append("с ").append(hhmm(open));
            else if (now < close) sb.append("ОКНО до ").append(hhmm(close));
            else sb.append("жив");
        }
        fgText = sb.length() > 0 ? sb.toString() : "Слежу за убийствами";
        postForeground();
    }

    // ---------------- Уведомления ----------------

    static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel watch = new NotificationChannel(CH_WATCH, "Фоновое слежение", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("Постоянное уведомление, пока работает слежение");
        watch.setShowBadge(false);
        NotificationChannel kill = new NotificationChannel(CH_KILL, "Убийство босса", NotificationManager.IMPORTANCE_HIGH);
        kill.setDescription("Босс убит (мелодия играет отдельно, по тумблерам)");
        kill.setSound(null, null);
        kill.enableVibration(true);
        kill.setVibrationPattern(new long[]{0, 400, 200, 400});
        NotificationChannel win = new NotificationChannel(CH_WINDOW, "Окно респа", NotificationManager.IMPORTANCE_HIGH);
        win.setDescription("Окно респа скоро откроется / открылось");
        nm.createNotificationChannel(watch);
        nm.createNotificationChannel(kill);
        nm.createNotificationChannel(win);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildForeground() {
        Intent stop = new Intent(this, BossWatchService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat_boss)
                .setContentTitle("Слежу за саб РБ")
                .setContentText(fgText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(fgText))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp())
                .addAction(0, "Остановить", stopPi)
                .build();
    }

    private void goForeground() {
        Notification n = buildForeground();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FG_ID, n);
        }
    }

    private void postForeground() {
        if (!running) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(FG_ID, buildForeground());
    }

    private void notifyKill(JSONObject o, String token, String kill, boolean withSound, int idx) {
        String name = o.optString("name", token);
        String killer = o.optString("killer_name", "");
        String clan = o.optString("clan_name", "");
        if ("null".equals(clan)) clan = "";
        if ("null".equals(killer)) killer = "";
        StringBuilder who = new StringBuilder();
        if (!killer.isEmpty()) who.append(killer);
        if (!clan.isEmpty()) who.append(" [").append(clan).append("]");
        long k = parseMsk(kill);
        SharedPreferences cfg = cfg(this);
        double openH = parseDouble(cfg.getString("bw.openH", "18"), 18);
        double closeH = parseDouble(cfg.getString("bw.closeH", "30"), 30);
        String text = (who.length() > 0 ? "Убил: " + who + ". " : "")
                + (k > 0 ? "Окно: " + dayHhmm(k + (long) (openH * 3600000L)) + " – " + dayHhmm(k + (long) (closeH * 3600000L)) : "");
        notifySimple(CH_KILL, 100 + idx, (withSound ? "🔔 " : "☠ ") + name + " убит", text);
    }

    private void notifySimple(String channel, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = new NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_stat_boss)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        nm.notify(id, n);
    }

    // ---------------- Звук ----------------

    private void playSound() {
        try {
            if (player != null) { player.release(); player = null; }
            final int[] left = {Math.max(1, (int) parseLong(cfg(this).getString("bw.repeat", "2"), 2))};
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(this, Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.kill_fanfare));
            mp.setOnCompletionListener(m -> {
                left[0]--;
                if (left[0] > 0) { m.seekTo(0); m.start(); }
                else { m.release(); if (player == m) player = null; }
            });
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            getSharedPreferences(ST, MODE_PRIVATE).edit().putString("lastError", "Звук: " + e.getMessage()).apply();
        }
    }

    // ---------------- Утилиты ----------------

    static SharedPreferences cfg(Context c) { return c.getSharedPreferences(CFG, MODE_PRIVATE); }

    static long parseMsk(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
            Date d = f.parse(s.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Exception e) { return 0; }
    }

    static String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t)); }

    static String dayHhmm(long t) { return new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date(t)); }

    static Set<String> splitSet(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String p : s.split(",")) { p = p.trim(); if (!p.isEmpty()) out.add(p); }
        return out;
    }

    static long parseLong(String s, long def) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; } }

    static double parseDouble(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }
}
