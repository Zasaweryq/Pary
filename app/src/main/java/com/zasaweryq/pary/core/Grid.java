package com.zasaweryq.pary.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Таблица одного листа: текст ячеек и объединённые области. */
public class Grid {
    public final String name;
    private final Map<Long, String> cells = new HashMap<>();
    /** Каждая область: {r1, r2, c1, c2} включительно. */
    public final List<int[]> merges = new ArrayList<>();
    public int rows = 0, cols = 0;

    public Grid(String name) { this.name = name; }

    private static long key(int r, int c) { return ((long) r << 20) | c; }

    public void set(int r, int c, String v) {
        if (v == null) return;
        String t = v.replace(' ', ' ').trim();
        if (t.isEmpty()) return;
        cells.put(key(r, c), t);
        if (r + 1 > rows) rows = r + 1;
        if (c + 1 > cols) cols = c + 1;
    }

    public String get(int r, int c) {
        String s = cells.get(key(r, c));
        return s == null ? "" : s;
    }

    /** Объединённая область, левый верхний угол которой — (r,c); иначе null. */
    public int[] mergeAt(int r, int c) {
        for (int[] m : merges) if (m[0] == r && m[2] == c) return m;
        return null;
    }

    static String numberToText(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }
}
