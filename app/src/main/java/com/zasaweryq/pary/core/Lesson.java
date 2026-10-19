package com.zasaweryq.pary.core;

import java.time.LocalDate;

/** Одно занятие группы в конкретный день. */
public class Lesson implements Comparable<Lesson> {
    public String group = "";
    public LocalDate date;
    public int pairFrom, pairTo;   // номера пар, 1..6
    public String title = "", type = "", teacher = "", room = "", note = "";

    /** Звонки ВолгГТУ (номер пары -> начало и конец). */
    public static final String[][] TIMES = {
            {"8:30", "10:00"}, {"10:10", "11:40"}, {"11:50", "13:20"},
            {"13:40", "15:10"}, {"15:20", "16:50"}, {"17:00", "18:30"},
            {"18:40", "20:10"}, {"20:20", "21:50"}
    };

    public String timeRange() {
        int a = Math.max(1, Math.min(pairFrom, TIMES.length));
        int b = Math.max(1, Math.min(pairTo, TIMES.length));
        return TIMES[a - 1][0] + "–" + TIMES[b - 1][1];
    }

    public String pairsLabel() {
        return pairFrom == pairTo ? pairFrom + " пара" : pairFrom + "–" + pairTo + " пары";
    }

    /** Короткая строка для уведомления. */
    public String shortLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(timeRange()).append("  ").append(title);
        if (!type.isEmpty()) sb.append(" (").append(type).append(")");
        if (!room.isEmpty()) sb.append(", ").append(room);
        return sb.toString();
    }

    @Override
    public int compareTo(Lesson o) {
        int c = date.compareTo(o.date);
        if (c != 0) return c;
        return Integer.compare(pairFrom, o.pairFrom);
    }

    @Override
    public String toString() {
        return group + " " + date + " " + pairsLabel() + " " + title + " [" + type + "] "
                + teacher + " " + room + (note.isEmpty() ? "" : " {" + note + "}");
    }
}
