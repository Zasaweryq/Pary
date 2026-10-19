package com.zasaweryq.pary.core;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Разобранное расписание: загрузка из файла Excel и простое текстовое хранение. */
public class Schedule {
    public String sourceName = "";
    public final List<String> groups = new ArrayList<>();
    public final List<Lesson> lessons = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public static Schedule fromExcel(byte[] data, String fileName) throws IOException {
        List<Grid> grids;
        boolean zip = data.length > 2 && data[0] == 'P' && data[1] == 'K';
        if (zip) grids = XlsxReader.read(data);
        else grids = XlsReader.read(data);
        ScheduleParser.Result r = ScheduleParser.parse(grids);
        Schedule s = new Schedule();
        s.sourceName = fileName == null ? "" : fileName;
        s.groups.addAll(r.groups);
        s.lessons.addAll(r.lessons);
        s.warnings.addAll(r.warnings);
        if (s.groups.isEmpty()) throw new IOException("В файле не найдено ни одной группы");
        return s;
    }

    /** Объединить с другим файлом (например, расписание другого курса). */
    public void merge(Schedule o) {
        Set<String> gs = new LinkedHashSet<>(groups);
        gs.addAll(o.groups);
        groups.clear();
        groups.addAll(gs);
        // занятия групп из нового файла заменяют старые
        Set<String> replaced = new LinkedHashSet<>(o.groups);
        lessons.removeIf(l -> replaced.contains(l.group));
        lessons.addAll(o.lessons);
        java.util.Collections.sort(lessons);
        sourceName = sourceName.isEmpty() ? o.sourceName : sourceName + " + " + o.sourceName;
    }

    public List<Lesson> forDay(String group, LocalDate day) {
        List<Lesson> out = new ArrayList<>();
        for (Lesson l : lessons) if (l.group.equals(group) && l.date.equals(day)) out.add(l);
        return out;
    }

    // ---------- хранение ----------
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("#source\t").append(esc(sourceName)).append('\n');
        for (String g : groups) sb.append("#group\t").append(esc(g)).append('\n');
        for (Lesson l : lessons) {
            sb.append(esc(l.group)).append('\t').append(l.date).append('\t')
                    .append(l.pairFrom).append('\t').append(l.pairTo).append('\t')
                    .append(esc(l.title)).append('\t').append(esc(l.type)).append('\t')
                    .append(esc(l.teacher)).append('\t').append(esc(l.room)).append('\t')
                    .append(esc(l.note)).append('\n');
        }
        return sb.toString();
    }

    public static Schedule deserialize(String text) {
        Schedule s = new Schedule();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            if (p[0].equals("#source")) { s.sourceName = unesc(p[1]); continue; }
            if (p[0].equals("#group")) { s.groups.add(unesc(p[1])); continue; }
            if (p.length < 9) continue;
            try {
                Lesson l = new Lesson();
                l.group = unesc(p[0]);
                l.date = LocalDate.parse(p[1]);
                l.pairFrom = Integer.parseInt(p[2]);
                l.pairTo = Integer.parseInt(p[3]);
                l.title = unesc(p[4]); l.type = unesc(p[5]);
                l.teacher = unesc(p[6]); l.room = unesc(p[7]); l.note = unesc(p[8]);
                s.lessons.add(l);
            } catch (Exception ignored) { }
        }
        return s;
    }

    /** Красивое название: «УПРАВ.ПРОЕКТ.РАЗРАБ.СИИ» -> «Управ.проект.разраб.СИИ». */
    public static String prettyTitle(String t) {
        if (t == null || t.isEmpty()) return "";
        Locale ru = new Locale("ru");
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\p{L}]+").matcher(t);
        int last = 0;
        boolean first = true;
        while (m.find()) {
            out.append(t, last, m.start());
            String w = m.group();
            boolean dotAfter = m.end() < t.length() && t.charAt(m.end()) == '.';
            boolean abbr = w.length() >= 2 && w.length() <= 3 && !dotAfter
                    && w.equals(w.toUpperCase(ru)) && !w.equalsIgnoreCase("и") && !w.equalsIgnoreCase("в");
            String lw = abbr ? w : w.toLowerCase(ru);
            if (first && !abbr) lw = Character.toUpperCase(lw.charAt(0)) + lw.substring(1);
            first = false;
            out.append(lw);
            last = m.end();
        }
        out.append(t.substring(last));
        return out.toString().replaceAll("\\s+", " ").trim();
    }
}
