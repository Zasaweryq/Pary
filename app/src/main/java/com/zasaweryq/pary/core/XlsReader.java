package com.zasaweryq.pary.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Минимальный читатель старого формата Excel (.xls, BIFF8) без сторонних библиотек.
 * Достаёт текст/числа ячеек и объединённые области со всех листов.
 */
public class XlsReader {

    public static List<Grid> read(byte[] file) throws IOException {
        byte[] wb = Ole2.readStream(file, "Workbook");
        if (wb == null) wb = Ole2.readStream(file, "Book");
        if (wb == null) throw new IOException("В файле нет книги Excel");
        return new XlsReader(wb).parse();
    }

    private final byte[] d;
    private final List<String> sst = new ArrayList<>();

    private XlsReader(byte[] d) { this.d = d; }

    private int u8(int p) { return d[p] & 0xFF; }
    private int u16(int p) { return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8); }
    private int i32(int p) { return u16(p) | (u16(p + 2) << 16); }

    private List<Grid> parse() throws IOException {
        List<int[]> sheetPos = new ArrayList<>();
        List<String> sheetNames = new ArrayList<>();
        int p = 0;
        // Глобальный поток книги: листы и общая таблица строк
        while (p + 4 <= d.length) {
            int id = u16(p), len = u16(p + 2);
            int body = p + 4;
            if (id == 0x0085) { // BOUNDSHEET
                int pos = i32(body);
                int type = u8(body + 5);
                int cch = u8(body + 6);
                int flags = u8(body + 7);
                String nm = readChars(body + 8, cch, (flags & 1) != 0);
                if (type == 0) { sheetPos.add(new int[]{pos}); sheetNames.add(nm); }
            } else if (id == 0x00FC) { // SST + CONTINUE
                readSst(p);
            } else if (id == 0x000A) { // EOF глобальной части
                break;
            }
            p = body + len;
        }
        List<Grid> out = new ArrayList<>();
        for (int i = 0; i < sheetPos.size(); i++) {
            out.add(readSheet(sheetPos.get(i)[0], sheetNames.get(i)));
        }
        return out;
    }

    private String readChars(int p, int n, boolean wide) {
        if (wide) return new String(d, p, n * 2, StandardCharsets.UTF_16LE);
        return new String(d, p, n, StandardCharsets.ISO_8859_1);
    }

    // ---------- SST: строки могут переходить через записи CONTINUE ----------
    private byte[] sd;            // склеенные данные SST+CONTINUE
    private List<Integer> bounds; // начала фрагментов CONTINUE в sd

    private void readSst(int p) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        bounds = new ArrayList<>();
        int len = u16(p + 2);
        bo.write(d, p + 4, len);
        p += 4 + len;
        while (p + 4 <= d.length && u16(p) == 0x003C) {
            len = u16(p + 2);
            bounds.add(bo.size());
            bo.write(d, p + 4, len);
            p += 4 + len;
        }
        sd = bo.toByteArray();
        int count = s32(sd, 4);
        int[] pos = {8};
        for (int i = 0; i < count && pos[0] < sd.length; i++) sst.add(readSstString(pos));
    }

    private static int s16(byte[] b, int p) { return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8); }
    private static int s32(byte[] b, int p) { return s16(b, p) | (s16(b, p + 2) << 16); }

    private int nextBound(int p) {
        for (int b : bounds) if (b > p) return b;
        return Integer.MAX_VALUE;
    }

    private String readSstString(int[] pp) {
        int p = pp[0];
        int cch = s16(sd, p);
        int flags = sd[p + 2] & 0xFF;
        p += 3;
        int runs = 0, ext = 0;
        if ((flags & 0x08) != 0) { runs = s16(sd, p); p += 2; }
        if ((flags & 0x04) != 0) { ext = s32(sd, p); p += 4; }
        boolean wide = (flags & 1) != 0;
        StringBuilder sb = new StringBuilder(cch);
        int left = cch;
        while (left > 0) {
            int nb = nextBound(p);
            int avail = (nb - p) / (wide ? 2 : 1);
            int take = Math.min(left, Math.max(avail, 0));
            if (wide) sb.append(new String(sd, p, take * 2, StandardCharsets.UTF_16LE));
            else sb.append(new String(sd, p, take, StandardCharsets.ISO_8859_1));
            p += take * (wide ? 2 : 1);
            left -= take;
            if (left > 0) { // начало CONTINUE: новый байт флагов
                wide = (sd[p] & 1) != 0;
                p += 1;
            }
        }
        p += runs * 4 + ext;
        pp[0] = p;
        return sb.toString();
    }

    // ---------- Лист ----------
    private Grid readSheet(int p, String name) {
        Grid g = new Grid(name);
        int pendingFormulaR = -1, pendingFormulaC = -1;
        while (p + 4 <= d.length) {
            int id = u16(p), len = u16(p + 2);
            int b = p + 4;
            switch (id) {
                case 0x00FD: { // LABELSST
                    int idx = i32(b + 6);
                    if (idx >= 0 && idx < sst.size()) g.set(u16(b), u16(b + 2), sst.get(idx));
                    break;
                }
                case 0x0204: { // LABEL
                    int cch = u16(b + 6);
                    boolean wide = (u8(b + 8) & 1) != 0;
                    g.set(u16(b), u16(b + 2), readChars(b + 9, cch, wide));
                    break;
                }
                case 0x0203: // NUMBER
                    g.set(u16(b), u16(b + 2), Grid.numberToText(Double.longBitsToDouble(i64(b + 6))));
                    break;
                case 0x027E: // RK
                    g.set(u16(b), u16(b + 2), Grid.numberToText(rk(i32(b + 6))));
                    break;
                case 0x00BD: { // MULRK
                    int r = u16(b), c = u16(b + 2);
                    int n = (len - 6) / 6;
                    for (int i = 0; i < n; i++) g.set(r, c + i, Grid.numberToText(rk(i32(b + 4 + i * 6 + 2))));
                    break;
                }
                case 0x0006: { // FORMULA (берём закэшированный результат)
                    int r = u16(b), c = u16(b + 2);
                    if (u16(b + 12) == 0xFFFF) {
                        if (u8(b + 6) == 0) { pendingFormulaR = r; pendingFormulaC = c; }
                    } else {
                        g.set(r, c, Grid.numberToText(Double.longBitsToDouble(i64(b + 6))));
                    }
                    break;
                }
                case 0x0207: { // STRING после формулы
                    if (pendingFormulaR >= 0) {
                        int cch = u16(b);
                        boolean wide = (u8(b + 2) & 1) != 0;
                        g.set(pendingFormulaR, pendingFormulaC, readChars(b + 3, cch, wide));
                        pendingFormulaR = -1;
                    }
                    break;
                }
                case 0x00E5: { // MERGEDCELLS
                    int n = u16(b);
                    for (int i = 0; i < n; i++) {
                        int q = b + 2 + i * 8;
                        g.merges.add(new int[]{u16(q), u16(q + 2), u16(q + 4), u16(q + 6)});
                    }
                    break;
                }
                case 0x000A: // EOF листа
                    return g;
            }
            p = b + len;
        }
        return g;
    }

    private long i64(int p) {
        return (i32(p) & 0xFFFFFFFFL) | ((long) i32(p + 4) << 32);
    }

    private static double rk(int v) {
        double r;
        if ((v & 2) != 0) r = v >> 2;
        else r = Double.longBitsToDouble(((long) (v & 0xFFFFFFFC)) << 32);
        if ((v & 1) != 0) r /= 100;
        return r;
    }

    // ---------- Контейнер OLE2 (Compound File) ----------
    static class Ole2 {
        static byte[] readStream(byte[] f, String want) throws IOException {
            if (f.length < 512 || (f[0] & 0xFF) != 0xD0 || (f[1] & 0xFF) != 0xCF)
                throw new IOException("Это не файл .xls");
            int secShift = s16(f, 0x1E);
            int miniShift = s16(f, 0x20);
            int ss = 1 << secShift, ms = 1 << miniShift;
            int numFat = s32(f, 0x2C);
            int dirStart = s32(f, 0x30);
            int cutoff = s32(f, 0x38);
            int miniFatStart = s32(f, 0x3C);
            int difatStart = s32(f, 0x44);
            int numDifat = s32(f, 0x48);

            List<Integer> fatSecs = new ArrayList<>();
            for (int i = 0; i < 109 && fatSecs.size() < numFat; i++) fatSecs.add(s32(f, 0x4C + i * 4));
            int ds = difatStart;
            for (int k = 0; k < numDifat && ds >= 0 && fatSecs.size() < numFat; k++) {
                int off = 512 + ds * ss;
                int per = ss / 4 - 1;
                for (int i = 0; i < per && fatSecs.size() < numFat; i++) fatSecs.add(s32(f, off + i * 4));
                ds = s32(f, off + per * 4);
            }
            int per = ss / 4;
            int[] fat = new int[fatSecs.size() * per];
            for (int i = 0; i < fatSecs.size(); i++) {
                int off = 512 + fatSecs.get(i) * ss;
                for (int j = 0; j < per && off + j * 4 + 4 <= f.length; j++) fat[i * per + j] = s32(f, off + j * 4);
            }
            byte[] dir = chain(f, fat, dirStart, ss, -1);
            byte[] root = null;
            int rootStart = -1; long rootSize = 0;
            for (int e = 0; e + 128 <= dir.length; e += 128) {
                int nl = s16(dir, e + 0x40);
                int type = dir[e + 0x42];
                if (nl < 2) continue;
                String nm = new String(dir, e, nl - 2, StandardCharsets.UTF_16LE);
                int start = s32(dir, e + 0x74);
                long size = s32(dir, e + 0x78) & 0xFFFFFFFFL;
                if (type == 5) { rootStart = start; rootSize = size; }
                if (type == 2 && nm.equals(want)) {
                    if (size >= cutoff) return chain(f, fat, start, ss, (int) size);
                    // малый поток: внутри mini stream корневой записи
                    root = chain(f, fat, rootStart, ss, (int) rootSize);
                    byte[] mfRaw = chain(f, fat, miniFatStart, ss, -1);
                    int[] mf = new int[mfRaw.length / 4];
                    for (int i = 0; i < mf.length; i++) mf[i] = s32(mfRaw, i * 4);
                    return chain(root, mf, start, ms, (int) size, 0);
                }
            }
            return null;
        }

        private static byte[] chain(byte[] f, int[] fat, int start, int ss, int size) {
            return chain(f, fat, start, ss, size, 512);
        }

        private static byte[] chain(byte[] f, int[] fat, int start, int ss, int size, int base) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            int s = start, guard = 0;
            while (s >= 0 && s < fat.length && guard++ < 1_000_000) {
                int off = base + s * ss;
                if (off + ss > f.length) { if (off < f.length) bo.write(f, off, f.length - off); break; }
                bo.write(f, off, ss);
                s = fat[s];
            }
            byte[] r = bo.toByteArray();
            if (size >= 0 && size < r.length) r = java.util.Arrays.copyOf(r, size);
            return r;
        }
    }
}
