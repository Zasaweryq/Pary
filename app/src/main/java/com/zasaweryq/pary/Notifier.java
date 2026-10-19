package com.zasaweryq.pary;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.zasaweryq.pary.core.Lesson;
import com.zasaweryq.pary.core.Schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Уведомления о парах на завтра и планирование будильника. */
public final class Notifier {
    static final String CHANNEL = "tomorrow";
    private static final int NOTIF_ID = 1001;
    private static final int ALARM_REQ = 7;
    private static final Locale RU = new Locale("ru");

    private Notifier() { }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Пары на завтра",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Вечернее напоминание о завтрашних занятиях");
            nm.createNotificationChannel(ch);
        }
    }

    public static String dayTitle(LocalDate d) {
        LocalDate today = LocalDate.now();
        String date = d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", RU));
        if (d.equals(today)) return "Сегодня, " + date;
        if (d.equals(today.plusDays(1))) return "Завтра, " + date;
        if (d.equals(today.minusDays(1))) return "Вчера, " + date;
        return Character.toUpperCase(date.charAt(0)) + date.substring(1);
    }

    public static String pairsWord(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return n + " пара";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return n + " пары";
        return n + " пар";
    }

    /** Показать уведомление о занятиях на указанный день. force — показать, даже если пар нет. */
    public static boolean showFor(Context c, LocalDate day, boolean force) {
        ensureChannel(c);
        Schedule s = Store.load(c);
        String group = Store.group(c);
        if (s == null || group.isEmpty()) {
            if (force) post(c, "Расписание не выбрано", "Откройте приложение и загрузите файл расписания", null);
            return false;
        }
        List<Lesson> ls = s.forDay(group, day);
        if (ls.isEmpty()) {
            if (force || Store.notifyEmpty(c)) {
                post(c, dayTitle(day) + " — пар нет", group + ": можно отдыхать", null);
                return true;
            }
            return false;
        }
        int pairs = 0;
        StringBuilder big = new StringBuilder();
        for (Lesson l : ls) {
            pairs += l.pairTo - l.pairFrom + 1;
            if (big.length() > 0) big.append('\n');
            big.append(l.timeRange()).append(" · ").append(Schedule.prettyTitle(l.title));
            if (!l.type.isEmpty()) big.append(" (").append(l.type).append(')');
            if (!l.room.isEmpty()) big.append(" · ").append(l.room);
        }
        Lesson first = ls.get(0);
        String title = dayTitle(day) + " — " + pairsWord(pairs);
        String text = "Начало в " + Lesson.TIMES[Math.max(0, first.pairFrom - 1)][0] + ": "
                + Schedule.prettyTitle(first.title) + (first.room.isEmpty() ? "" : ", " + first.room);
        post(c, title, text, big.toString());
        return true;
    }

    private static void post(Context c, String title, String text, String big) {
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("showTomorrow", true);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        if (big != null) b.setStyle(new Notification.BigTextStyle().bigText(big));
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (SecurityException ignored) {
            // нет разрешения на уведомления
        }
    }

    /** Запланировать следующее напоминание (или снять, если выключено). */
    public static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent i = new Intent(c, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, ALARM_REQ, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!Store.enabled(c)) return;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime at = LocalDateTime.of(now.toLocalDate(), LocalTime.of(Store.hour(c), Store.minute(c)));
        if (!at.isAfter(now.plusSeconds(5))) at = at.plusDays(1);
        long t = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        boolean exact = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) exact = am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        }
        Store.prefs(c).edit().putLong("nextAlarm", t).apply();
    }
}
