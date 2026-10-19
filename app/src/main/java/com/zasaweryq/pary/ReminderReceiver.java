package com.zasaweryq.pary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;

/** Срабатывает вечером: показывает пары на завтра и планирует следующий раз. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Store.enabled(context)) Notifier.showFor(context, LocalDate.now().plusDays(1), false);
        Notifier.schedule(context);
    }
}
