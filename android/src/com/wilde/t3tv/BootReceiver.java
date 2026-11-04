package com.wilde.t3tv;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && context.getSharedPreferences("dashboard",0).getBoolean("monitor",false)) {
            context.startForegroundService(new Intent(context, MonitorService.class));
        }
    }
}
