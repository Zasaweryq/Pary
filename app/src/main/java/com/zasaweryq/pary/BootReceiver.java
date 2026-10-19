package com.zasaweryq.pary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки, обновления приложения или смены времени — заново ставим напоминание. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Notifier.schedule(context);
    }
}
