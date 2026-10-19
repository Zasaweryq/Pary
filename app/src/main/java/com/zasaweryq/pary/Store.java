package com.zasaweryq.pary;

import android.content.Context;
import android.content.SharedPreferences;

import com.zasaweryq.pary.core.Schedule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Хранение расписания и настроек на устройстве. */
public final class Store {
    private static final String FILE = "schedule.tsv";
    private static Schedule cached;

    private Store() { }

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("pary", Context.MODE_PRIVATE);
    }

    public static synchronized Schedule load(Context c) {
        if (cached != null) return cached;
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            cached = Schedule.deserialize(new String(b, 0, off, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        return cached;
    }

    public static synchronized void save(Context c, Schedule s) throws Exception {
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.serialize().getBytes(StandardCharsets.UTF_8));
        }
        File dst = new File(c.getFilesDir(), FILE);
        if (!tmp.renameTo(dst)) throw new Exception("Не удалось сохранить расписание");
        cached = s;
    }

    public static String group(Context c) { return prefs(c).getString("group", ""); }
    public static void setGroup(Context c, String g) { prefs(c).edit().putString("group", g).apply(); }

    public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", true); }
    public static void setEnabled(Context c, boolean v) { prefs(c).edit().putBoolean("enabled", v).apply(); }

    public static boolean notifyEmpty(Context c) { return prefs(c).getBoolean("notifyEmpty", false); }
    public static void setNotifyEmpty(Context c, boolean v) { prefs(c).edit().putBoolean("notifyEmpty", v).apply(); }

    public static int hour(Context c) { return prefs(c).getInt("hour", 20); }
    public static int minute(Context c) { return prefs(c).getInt("minute", 0); }
    public static void setTime(Context c, int h, int m) {
        prefs(c).edit().putInt("hour", h).putInt("minute", m).apply();
    }
}
