package monster.l2boss.tracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Перезапускает слежение после перезагрузки телефона или обновления приложения. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!"1".equals(BossWatchService.cfg(context).getString("bw.enabled", "0"))) return;
        try {
            Intent i = new Intent(context, BossWatchService.class).setAction(BossWatchService.ACTION_START);
            ContextCompat.startForegroundService(context, i);
        } catch (Exception ignored) {
        }
    }
}
