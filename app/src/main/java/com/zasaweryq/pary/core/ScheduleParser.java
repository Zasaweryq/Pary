package com.zasaweryq.pary.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор сетки расписания формата ВолгГТУ: группы по столбцам, дни недели и
 * «часы» (1-2, 3-4, …) по строкам, у каждого занятия — список конкретных дат.
 */
public class ScheduleParser {

    private static final Pattern GROUP = Pattern.compile("^([А-ЯЁA-Z]{1,8})\\s*-\\s*(\\d+(?:\\.\\d+)?[а-яa-z]?)$");
    private static final Pattern SLOT = Pattern.compile("^(\\d{1,2})\\s*-\\s*(\\d{1,2})$");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})");
    private static final Pattern YEARS = Pattern.compile("(20\\d\\d)\\s*-\\s*(20\\d\\d)");
    private static final Pattern ROOM = Pattern.compile("^([А-ЯЁA-Z]{1,3}\\.?\\s*)?\\d{2,4}\\s*[а-яa-z]?(\\s*-\\s*\\d+)?[а-яa-z]?$");
    private static final Pattern TYPE = Pattern.compile("^(лаб|лекц|практ|семин|зач|экз|конс)[^\\d]*(\\d+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS_NOTE = Pattern.compile("^\\d+\\s*-\\s*\\d+\\s*час", Pattern.CASE_INSENSITIVE);

    private static final String[] DAYS = {"ПОНЕДЕЛЬНИК", "ВТОРНИК", "СРЕДА", "ЧЕТВЕРГ", "ПЯТНИЦА", "СУББОТА", "ВОСКРЕСЕНЬЕ"};
    private static final String[] MONTHS = {"январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр"};

    public static class Result {
        public final List<String> groups = new ArrayList<>();
        public final List<Lesson> lessons = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    public static Result parse(List<Grid> grids) {
        Result res = new Result();
        Set<String> groups = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Grid g : grids) {
            Result r = new ScheduleParser(g).run();
            groups.addAll(r.groups);
            for (Lesson l : r.lessons) {
                String k = l.group + "|" + l.date + "|" + l.pairFrom + "|" + l.title + "|" + l.type;
                if (seen.add(k)) res.lessons.add(l);
            }
            res.warnings.addAll(r.warnings);
        }
        res.groups.addAll(groups);
        Collections.sort(res.lessons);
        return res;
    }

    // -------------------------------------------------------------------------

    private final Grid g;
    private int startYear;
    private final TreeMap<Integer, String> groupCols = new TreeMap<>(); // начальный столбец -> группа
    private int groupWidth = 4;
    private int firstGroupCol = Integer.MAX_VALUE;
    private final Map<Integer, Integer> monthCols = new LinkedHashMap<>(); // столбец -> месяц

    private ScheduleParser(Grid g) { this.g = g; }

    static String normGroup(String s) {
        Matcher m = GROUP.matcher(s.trim().replaceAll("\\s+", " "));
        return m.matches() ? m.group(1) + "-" + m.group(2) : null;
    }

    private Result run() {
        Result res = new Result();
        // учебный год
        startYear = LocalDate.now().getMonthValue() >= 7 ? LocalDate.now().getYear() : LocalDate.now().getYear() - 1;
        outer:
        for (int r = 0; r < Math.min(g.rows, 15); r++)
            for (int c = 0; c < g.cols; c++) {
                Matcher m = YEARS.matcher(g.get(r, c));
                if (m.find()) { startYear = Integer.parseInt(m.group(1)); break outer; }
            }

        // строка с названиями групп
        int headerRow = -1;
        for (int r = 0; r < g.rows && headerRow < 0; r++) {
            int n = 0;
            for (int c = 0; c < g.cols; c++) if (normGroup(g.get(r, c)) != null) n++;
            if (n >= 1) headerRow = r;
        }
        if (headerRow < 0) { res.warnings.add("Лист «" + g.name + "»: не найдены группы"); return res; }
        for (int c = 0; c < g.cols; c++) {
            String gr = normGroup(g.get(headerRow, c));
            if (gr != null) groupCols.put(c, gr);
            String low = g.get(headerRow, c).toLowerCase(Locale.ROOT);
            for (int i = MONTHS.length - 1; i >= 0; i--) {
                if (low.startsWith(MONTHS[i]) && low.length() <= 9) { monthCols.put(c, i + 1); break; }
            }
        }
        firstGroupCol = groupCols.firstKey();
        if (groupCols.size() >= 2) {
            List<Integer> ks = new ArrayList<>(groupCols.keySet());
            groupWidth = ks.get(1) - ks.get(0);
        }
        res.groups.addAll(groupCols.values());

        // дни недели и слоты
        List<int[]> dayRows = new ArrayList<>(); // {row, dayIndex}
        int slotCol = -1;
        for (int r = headerRow + 1; r < g.rows; r++) {
            for (int c = 0; c < firstGroupCol; c++) {
                String t = g.get(r, c).toUpperCase(Locale.ROOT).replace(" ", "");
                for (int i = 0; i < DAYS.length; i++) if (t.equals(DAYS[i])) dayRows.add(new int[]{r, i});
                if (slotCol < 0 && SLOT.matcher(g.get(r, c)).matches()) slotCol = c;
            }
        }
        if (dayRows.isEmpty() || slotCol < 0) {
            res.warnings.add("Лист «" + g.name + "»: не найдены дни недели или часы занятий");
            return res;
        }
        for (int i = 0; i < dayRows.size(); i++) {
            int from = dayRows.get(i)[0];
            int to = i + 1 < dayRows.size() ? dayRows.get(i + 1)[0] - 1 : g.rows - 1;
            // секция заканчивается на повторной шапке, если она есть
            for (int r = from + 1; r <= to; r++) {
                if (normGroup(g.get(r, firstGroupCol)) != null) { to = r - 1; break; }
            }
            parseSection(from, to, dayRows.get(i)[1], slotCol, res);
        }
        return res;
    }

    private int groupEnd(int c) {
        Integer next = groupCols.higherKey(c);
        if (next != null) return next - 1;
        return groupCols.floorKey(c) + groupWidth - 1;
    }

    private LocalDate makeDate(int day, int month) {
        int y = month >= 7 ? startYear : startYear + 1;
        try { return LocalDate.of(y, month, day); } catch (Exception e) { return null; }
    }

    private static class Block {
        int row, c1, c2;
        String title;
        final List<int[]> cells = new ArrayList<>(); // {row, col}
    }

    private void parseSection(int from, int to, int dayIdx, int slotCol, Result res) {
        // строки слотов: строка -> {первый час, последний час}
        TreeMap<Integer, Integer> slots = new TreeMap<>(); // row -> номер пары
        for (int r = from; r <= to; r++) {
            Matcher m = SLOT.matcher(g.get(r, slotCol));
            if (m.matches()) slots.put(r, (Integer.parseInt(m.group(2)) + 1) / 2);
        }
        if (slots.isEmpty()) return;

        // даты этого дня недели по месячным столбцам (на случай занятия без дат)
        List<LocalDate> weekDates = new ArrayList<>();
        for (int r = from; r <= to; r++)
            for (Map.Entry<Integer, Integer> mc : monthCols.entrySet()) {
                String v = g.get(r, mc.getKey());
                try {
                    int d = (int) Double.parseDouble(v);
                    if (d >= 1 && d <= 31) {
                        LocalDate ld = makeDate(d, mc.getValue());
                        if (ld != null && ld.getDayOfWeek().getValue() == dayIdx + 1) weekDates.add(ld);
                    }
                } catch (NumberFormatException ignored) { }
            }

        // названия дисциплин
        List<Block> blocks = new ArrayList<>();
        int lastCol = groupCols.lastKey() + groupWidth - 1;
        for (int r = from; r <= to; r++)
            for (int c = firstGroupCol; c <= lastCol; c++) {
                String t = g.get(r, c);
                if (!isTitle(t)) continue;
                Block b = new Block();
                b.row = r; b.title = t.replaceAll("\\s+", " ").trim();
                int[] m = g.mergeAt(r, c);
                b.c1 = c;
                b.c2 = (m != null && m[3] > c) ? m[3] : groupEnd(c);
                blocks.add(b);
            }

        // остальные ячейки — к ближайшему названию выше, чей диапазон их накрывает
        for (int r = from; r <= to; r++)
            for (int c = firstGroupCol; c <= lastCol; c++) {
                String t = g.get(r, c);
                if (t.isEmpty() || isTitle(t)) continue;
                Block best = null;
                for (Block b : blocks) {
                    if (b.row > r || c < b.c1 || c > b.c2) continue;
                    if (best == null || b.row > best.row
                            || (b.row == best.row && (b.c2 - b.c1) < (best.c2 - best.c1))) best = b;
                }
                if (best != null) best.cells.add(new int[]{r, c});
            }

        for (Block b : blocks) buildLessons(b, slots, weekDates, dayIdx, res);
    }

    private void buildLessons(Block b, TreeMap<Integer, Integer> slots, List<LocalDate> weekDates,
                              int dayIdx, Result res) {
        Map.Entry<Integer, Integer> se = slots.floorEntry(b.row);
        if (se == null) se = slots.firstEntry();
        int startPair = se.getValue();

        List<String> teachers = new ArrayList<>();
        List<String> rooms = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        // «корзина» = половина столбцов конкретной группы (лекция/лаба в разные недели)
        TreeMap<Integer, String> bType = new TreeMap<>();
        TreeMap<Integer, Integer> bHours = new TreeMap<>();
        TreeMap<Integer, StringBuilder> bDates = new TreeMap<>();
        int maxRow = b.row;

        for (int[] rc : b.cells) {
            String t = g.get(rc[0], rc[1]);
            int bucket = bucketOf(rc[1]);
            maxRow = Math.max(maxRow, rc[0]);
            Matcher tm = TYPE.matcher(t);
            if (HOURS_NOTE.matcher(t).find()) {
                notes.add(t);
            } else if (isDates(t)) {
                bDates.computeIfAbsent(bucket, k -> new StringBuilder()).append(t).append(',');
            } else if (tm.find()) {
                bType.put(bucket, normType(tm.group(1)));
                if (tm.group(2) != null) bHours.put(bucket, Integer.parseInt(tm.group(2)));
            } else if (ROOM.matcher(t).matches()) {
                rooms.add(t.replaceAll("\\s+", " "));
            } else {
                String tt = t.replaceAll("\\s+", " ").replaceAll("[,\\s]+$", "");
                if (!tt.isEmpty() && !containsPerson(teachers, tt)) teachers.add(tt);
            }
        }

        // группы, которые накрывает блок
        List<String> grs = new ArrayList<>();
        List<Integer> grStarts = new ArrayList<>();
        for (Map.Entry<Integer, String> e : groupCols.entrySet()) {
            int gs = e.getKey(), ge = groupEnd(gs);
            if (gs <= b.c2 && ge >= b.c1) { grs.add(e.getValue()); grStarts.add(gs); }
        }

        String anyType = bType.isEmpty() ? "" : bType.firstEntry().getValue();
        int anyHours = 0;
        for (int h : bHours.values()) anyHours = Math.max(anyHours, h);

        // варианты: {тип, часы, даты, группы через \u0001}
        List<Object[]> variants = new ArrayList<>();
        if (bDates.size() <= 1) {
            String ds = bDates.isEmpty() ? "" : bDates.firstEntry().getValue().toString();
            variants.add(new Object[]{anyType, anyHours, ds, grs});
        } else {
            Set<String> covered = new LinkedHashSet<>();
            for (Map.Entry<Integer, StringBuilder> e : bDates.entrySet()) {
                int bk = e.getKey();
                int gs = bk / 2;
                String ty = bType.containsKey(bk) ? bType.get(bk)
                        : bType.containsKey(bk ^ 1) ? bType.get(bk ^ 1) : anyType;
                int hr = bHours.containsKey(bk) ? bHours.get(bk)
                        : bHours.containsKey(bk ^ 1) ? bHours.get(bk ^ 1) : anyHours;
                String gname = groupCols.get(gs);
                List<String> only = new ArrayList<>();
                if (gname != null && grs.contains(gname)) { only.add(gname); covered.add(gname); }
                else only.addAll(grs);
                variants.add(new Object[]{ty, hr, e.getValue().toString(), only});
            }
            // группы без собственных дат получают все варианты
            List<String> rest = new ArrayList<>();
            for (String gname : grs) if (!covered.contains(gname)) rest.add(gname);
            if (!rest.isEmpty()) {
                int n = variants.size();
                for (int i = 0; i < n; i++) {
                    Object[] v = variants.get(i);
                    variants.add(new Object[]{v[0], v[1], v[2], rest});
                }
            }
        }

        // сколько пар подряд
        int extentPairs = 1;
        for (Map.Entry<Integer, Integer> e : slots.subMap(se.getKey(), false, maxRow, true).entrySet()) extentPairs++;


        String teacher = String.join(", ", new LinkedHashSet<>(teachers));
        String room = String.join(", ", new LinkedHashSet<>(rooms));
        String note = String.join("; ", notes);

        for (Object[] v : variants) {
            int hr = (Integer) v[1];
            @SuppressWarnings("unchecked") List<String> vg = (List<String>) v[3];
            int pairs = hr >= 2 ? Math.max(1, hr / 2) : extentPairs;
            pairs = Math.min(pairs, 4);
            List<LocalDate> ds = new ArrayList<>();
            Matcher dm = DATE.matcher((String) v[2]);
            while (dm.find()) {
                LocalDate ld = makeDate(Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)));
                if (ld != null && !ds.contains(ld)) ds.add(ld);
            }
            if (ds.isEmpty()) ds.addAll(weekDates);
            for (LocalDate d : ds) {
                if (d.getDayOfWeek().getValue() != dayIdx + 1) {
                    res.warnings.add(prettyWarn(b.title) + ": дата " + d + " не совпадает с днём недели, пропущена");
                    continue;
                }
                for (String gr : vg) {
                    Lesson l = new Lesson();
                    l.group = gr; l.date = d;
                    l.pairFrom = startPair; l.pairTo = startPair + pairs - 1;
                    l.title = b.title; l.type = (String) v[0];
                    l.teacher = teacher; l.room = room; l.note = note;
                    res.lessons.add(l);
                }
            }
        }
    }

    private int bucketOf(int c) {
        Integer gs = groupCols.floorKey(c);
        if (gs == null) return 0;
        int w = groupEnd(gs) - gs + 1;
        return gs * 2 + ((c - gs) >= w / 2 ? 1 : 0);
    }

    private static String surname(String s) {
        String t = s.replaceAll("^(доц|проф|ст\\.\\s*пр|асс|преп)\\.?\\s*", "").trim();
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    private static boolean containsPerson(List<String> list, String p) {
        for (String x : list) if (surname(x).equalsIgnoreCase(surname(p))) return true;
        return false;
    }

    private static String prettyWarn(String t) { return t.length() > 40 ? t.substring(0, 40) + "…" : t; }

    static String normType(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        if (s.startsWith("лаб")) return "лаб.";
        if (s.startsWith("лекц")) return "лекция";
        if (s.startsWith("практ")) return "практика";
        if (s.startsWith("семин")) return "семинар";
        if (s.startsWith("зач")) return "зачёт";
        if (s.startsWith("экз")) return "экзамен";
        if (s.startsWith("конс")) return "консультация";
        return s;
    }

    static boolean isDates(String t) {
        Matcher m = DATE.matcher(t);
        int n = 0;
        while (m.find()) n++;
        if (n == 0) return false;
        // почти всё — цифры, точки, запятые
        int other = 0;
        for (char ch : t.toCharArray()) if (!Character.isDigit(ch) && ".,; ".indexOf(ch) < 0) other++;
        return other <= 2;
    }

    static boolean isTitle(String t) {
        if (t.length() < 3) return false;
        int letters = 0, upper = 0;
        for (char ch : t.toCharArray()) {
            if (Character.isDigit(ch)) return false;
            if (Character.isLetter(ch)) { letters++; if (Character.isUpperCase(ch)) upper++; }
        }
        if (letters < 3) return false;
        String u = t.toUpperCase(Locale.ROOT).replace(" ", "");
        for (String d : DAYS) if (u.equals(d)) return false;
        return upper >= letters * 0.8;
    }
}
