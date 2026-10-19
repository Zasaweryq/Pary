package com.zasaweryq.pary;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.zasaweryq.pary.core.Lesson;
import com.zasaweryq.pary.core.Schedule;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final int REQ_NOTIF = 2;

    private boolean night;
    private int cText, cMuted, cCard, cAccent, cBg;

    private TextView fileInfo, dayTitle;
    private Spinner groupSpinner;
    private LinearLayout dayList;
    private Button timeBtn;
    private Switch enabledSw;
    private CheckBox emptyCb;
    private LocalDate shownDay = LocalDate.now().plusDays(1);
    private boolean spinnerReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        cBg = night ? 0xFF121417 : 0xFFF4F5F7;
        cCard = night ? 0xFF1E2227 : 0xFFFFFFFF;
        cText = night ? 0xFFE8EAED : 0xFF1B1D21;
        cMuted = night ? 0xFF9AA0A6 : 0xFF5F6368;
        cAccent = night ? 0xFF7FB3FF : 0xFF1A5FD0;
        getWindow().setStatusBarColor(cBg);
        getWindow().setNavigationBarColor(cBg);
        if (!night) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        Notifier.ensureChannel(this);
        buildUi();
        refreshAll();
        askNotificationPermission();
        Notifier.schedule(this);
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    /** Файл расписания, открытый из другого приложения («Открыть с помощью» / «Поделиться»). */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("showTomorrow", false)) {
            shownDay = LocalDate.now().plusDays(1);
            renderDay();
        }
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) {
            intent.setAction(Intent.ACTION_MAIN);
            importFile(uri);
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(dp(16));
        l.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (primary) {
            bg.setColor(cAccent);
            b.setTextColor(night ? 0xFF0B1A33 : Color.WHITE);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), cMuted);
            b.setTextColor(cText);
        }
        b.setBackground(bg);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setStateListAnimator(null);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));
        scroll.addView(root);

        root.addView(text("Пары", 28, cText, true));
        TextView sub = text("Расписание и напоминания на завтра", 14, cMuted, false);
        root.addView(sub);

        // --- файл и группа
        LinearLayout c1 = card();
        c1.addView(text("Расписание", 13, cMuted, true));
        fileInfo = text("Файл не загружен", 15, cText, false);
        fileInfo.setPadding(0, dp(4), 0, dp(10));
        c1.addView(fileInfo);
        Button load = button("Загрузить файл (.xls / .xlsx)", true);
        load.setOnClickListener(v -> pickFile());
        c1.addView(load, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView gl = text("Группа", 13, cMuted, true);
        gl.setPadding(0, dp(14), 0, dp(2));
        c1.addView(gl);
        groupSpinner = new Spinner(this);
        groupSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!spinnerReady) return;
                Object g = p.getItemAtPosition(pos);
                if (g != null && !g.toString().equals(Store.group(MainActivity.this))) {
                    Store.setGroup(MainActivity.this, g.toString());
                    renderDay();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        c1.addView(groupSpinner);
        root.addView(c1);

        // --- уведомления
        LinearLayout c2 = card();
        c2.addView(text("Напоминание", 13, cMuted, true));
        enabledSw = new Switch(this);
        enabledSw.setText("Присылать пары на завтра");
        enabledSw.setTextColor(cText);
        enabledSw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        enabledSw.setPadding(0, dp(6), 0, dp(6));
        enabledSw.setOnCheckedChangeListener((b, on) -> {
            Store.setEnabled(this, on);
            Notifier.schedule(this);
            timeBtn.setEnabled(on);
            emptyCb.setEnabled(on);
        });
        c2.addView(enabledSw);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView at = text("Каждый день в", 15, cText, false);
        row.addView(at, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timeBtn = button("20:00", false);
        timeBtn.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> {
            Store.setTime(this, h, m);
            Notifier.schedule(this);
            refreshSettings();
            Toast.makeText(this, "Напоминание в " + fmtTime(h, m), Toast.LENGTH_SHORT).show();
        }, Store.hour(this), Store.minute(this), true).show());
        row.addView(timeBtn, new LinearLayout.LayoutParams(dp(110), dp(44)));
        c2.addView(row);

        emptyCb = new CheckBox(this);
        emptyCb.setText("Сообщать, если пар нет");
        emptyCb.setTextColor(cText);
        emptyCb.setOnCheckedChangeListener((b, on) -> Store.setNotifyEmpty(this, on));
        c2.addView(emptyCb);

        Button test = button("Проверить уведомление", false);
        test.setOnClickListener(v -> {
            askNotificationPermission();
            Notifier.showFor(this, LocalDate.now().plusDays(1), true);
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        tlp.topMargin = dp(8);
        c2.addView(test, tlp);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = getSystemService(AlarmManager.class);
            if (!am.canScheduleExactAlarms()) {
                TextView hint = text("Чтобы напоминание приходило минута в минуту, разрешите «Будильники и напоминания». Нажмите здесь.",
                        13, cAccent, false);
                hint.setPadding(0, dp(10), 0, 0);
                hint.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                });
                c2.addView(hint);
            }
        }
        root.addView(c2);

        // --- просмотр дня
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(20), 0, dp(4));
        Button prev = button("‹", false);
        prev.setOnClickListener(v -> { shownDay = shownDay.minusDays(1); renderDay(); });
        Button next = button("›", false);
        next.setOnClickListener(v -> { shownDay = shownDay.plusDays(1); renderDay(); });
        dayTitle = text("", 17, cText, true);
        dayTitle.setGravity(Gravity.CENTER);
        dayTitle.setOnClickListener(v -> { shownDay = LocalDate.now().plusDays(1); renderDay(); });
        nav.addView(prev, new LinearLayout.LayoutParams(dp(48), dp(44)));
        nav.addView(dayTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(next, new LinearLayout.LayoutParams(dp(48), dp(44)));
        root.addView(nav);

        dayList = new LinearLayout(this);
        dayList.setOrientation(LinearLayout.VERTICAL);
        root.addView(dayList);

        setContentView(scroll);
    }

    private static String fmtTime(int h, int m) {
        return String.format(Locale.ROOT, "%d:%02d", h, m);
    }

    private void refreshSettings() {
        enabledSw.setChecked(Store.enabled(this));
        emptyCb.setChecked(Store.notifyEmpty(this));
        timeBtn.setText(fmtTime(Store.hour(this), Store.minute(this)));
        timeBtn.setEnabled(Store.enabled(this));
        emptyCb.setEnabled(Store.enabled(this));
    }

    private void refreshAll() {
        refreshSettings();
        Schedule s = Store.load(this);
        spinnerReady = false;
        if (s == null || s.groups.isEmpty()) {
            fileInfo.setText("Файл не загружен — выберите файл расписания из «Загрузок» или Telegram");
            groupSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"—"}));
        } else {
            fileInfo.setText(s.sourceName + "\nГрупп: " + s.groups.size() + ", занятий: " + s.lessons.size());
            ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_dropdown_item, s.groups) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View v = super.getView(position, convertView, parent);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(cText);
                        ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                    }
                    return v;
                }
            };
            groupSpinner.setAdapter(ad);
            String g = Store.group(this);
            int idx = s.groups.indexOf(g);
            if (idx < 0) {
                idx = 0;
                Store.setGroup(this, s.groups.get(0));
            }
            groupSpinner.setSelection(idx, false);
        }
        groupSpinner.post(() -> spinnerReady = true);
        renderDay();
    }

    private void renderDay() {
        dayTitle.setText(Notifier.dayTitle(shownDay));
        dayList.removeAllViews();
        Schedule s = Store.load(this);
        String g = Store.group(this);
        if (s == null || g.isEmpty()) return;
        List<Lesson> ls = s.forDay(g, shownDay);
        if (ls.isEmpty()) {
            TextView t = text("Пар нет", 15, cMuted, false);
            t.setPadding(dp(4), dp(12), 0, dp(12));
            dayList.addView(t);
            return;
        }
        for (Lesson l : ls) {
            LinearLayout c = card();
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
            lp.topMargin = dp(8);
            c.addView(text(l.timeRange() + "  ·  " + l.pairsLabel(), 13, cAccent, true));
            TextView title = text(Schedule.prettyTitle(l.title), 16, cText, true);
            title.setPadding(0, dp(4), 0, dp(2));
            c.addView(title);
            StringBuilder meta = new StringBuilder();
            if (!l.type.isEmpty()) meta.append(l.type);
            if (!l.room.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(l.room);
            if (!l.teacher.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(l.teacher);
            if (!l.note.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(l.note);
            if (meta.length() > 0) c.addView(text(meta.toString(), 14, cMuted, false));
            dayList.addView(c);
        }
    }

    // ------------------------------------------------------------------ файлы

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения для выбора файлов", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importFile(data.getData());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        String p = uri.getLastPathSegment();
        return p == null ? "файл" : p;
    }

    private void importFile(Uri uri) {
        fileInfo.setText("Читаю файл…");
        new Thread(() -> {
            try {
                String name = displayName(uri);
                byte[] data;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new Exception("Не удалось открыть файл");
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    data = bo.toByteArray();
                }
                Schedule s = Schedule.fromExcel(data, name);
                Store.save(this, s);
                final boolean askGroup = !s.groups.contains(Store.group(this)) || s.groups.size() > 1 && Store.prefs(this).getBoolean("firstImport", true);
                Store.prefs(this).edit().putBoolean("firstImport", false).apply();
                runOnUiThread(() -> {
                    refreshAll();
                    Notifier.schedule(this);
                    String msg = "Найдено групп: " + s.groups.size() + ", занятий: " + s.lessons.size();
                    if (!s.warnings.isEmpty()) {
                        StringBuilder w = new StringBuilder(msg).append("\n\nЗамечания по файлу:\n");
                        for (int k = 0; k < Math.min(8, s.warnings.size()); k++)
                            w.append("• ").append(s.warnings.get(k)).append('\n');
                        if (s.warnings.size() > 8) w.append("…и ещё ").append(s.warnings.size() - 8);
                        new AlertDialog.Builder(this).setTitle("Расписание загружено")
                                .setMessage(w.toString()).setPositiveButton("OK", null).show();
                    } else {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    }
                    if (askGroup) groupSpinner.performClick();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    refreshAll();
                    new AlertDialog.Builder(this).setTitle("Не получилось прочитать файл")
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton("OK", null).show();
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ разрешения

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }
}
