package monster.l2boss.tracker;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** Мост между интерфейсом (JS) и фоновой службой. */
@CapacitorPlugin(
        name = "BossWatch",
        permissions = {@Permission(strings = {Manifest.permission.POST_NOTIFICATIONS}, alias = "notifications")}
)
public class BossWatchPlugin extends Plugin {

    private void send(String action) {
        Context ctx = getContext();
        Intent i = new Intent(ctx, BossWatchService.class).setAction(action);
        ContextCompat.startForegroundService(ctx, i);
    }

    @PluginMethod
    public void start(PluginCall call) {
        try { send(BossWatchService.ACTION_START); call.resolve(); }
        catch (Exception e) { call.reject("Не удалось запустить службу: " + e.getMessage()); }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context ctx = getContext();
        BossWatchService.cfg(ctx).edit().putString("bw.enabled", "0").apply();
        if (BossWatchService.running) {
            try { send(BossWatchService.ACTION_STOP); } catch (Exception ignored) {}
        }
        call.resolve();
    }

    @PluginMethod
    public void reload(PluginCall call) {
        try { if (BossWatchService.running) send(BossWatchService.ACTION_RELOAD); call.resolve(); }
        catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void pollNow(PluginCall call) {
        try { if (BossWatchService.running) send(BossWatchService.ACTION_POLL); call.resolve(); }
        catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void testSound(PluginCall call) {
        try { send(BossWatchService.ACTION_TEST_SOUND); call.resolve(); }
        catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void status(PluginCall call) {
        Context ctx = getContext();
        SharedPreferences st = ctx.getSharedPreferences(BossWatchService.ST, Context.MODE_PRIVATE);
        JSObject r = new JSObject();
        r.put("running", BossWatchService.running);
        r.put("lastJson", st.getString("lastJson", null));
        r.put("lastOk", st.getLong("lastOk", 0));
        r.put("lastFail", st.getLong("lastFail", 0));
        r.put("lastError", st.getString("lastError", null));
        r.put("notifications", NotificationManagerCompat.from(ctx).areNotificationsEnabled());
        r.put("batteryOk", isIgnoringBattery(ctx));
        call.resolve(r);
    }

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
            call.resolve(r);
            return;
        }
        requestPermissionForAlias("notifications", call, "notifCallback");
    }

    @PermissionCallback
    private void notifCallback(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(r);
    }

    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        Context ctx = getContext();
        try {
            Intent i;
            if (!isIgnoringBattery(ctx)) {
                i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        Context ctx = getContext();
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    static boolean isIgnoringBattery(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }
}
