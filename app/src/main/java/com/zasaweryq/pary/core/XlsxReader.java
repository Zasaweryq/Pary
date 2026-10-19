package com.zasaweryq.pary.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/** Чтение нового формата Excel (.xlsx) через стандартные zip и XML. */
public class XlsxReader {

    public static List<Grid> read(byte[] file) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (n.startsWith("xl/")) parts.put(n, readAll(zin));
            }
        }
        try {
            List<String> shared = new ArrayList<>();
            byte[] ss = parts.get("xl/sharedStrings.xml");
            if (ss != null) {
                NodeList sis = xml(ss).getElementsByTagName("si");
                for (int i = 0; i < sis.getLength(); i++) {
                    NodeList ts = ((Element) sis.item(i)).getElementsByTagName("t");
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < ts.getLength(); j++) sb.append(ts.item(j).getTextContent());
                    shared.add(sb.toString());
                }
            }
            // имена листов по порядку
            Map<String, String> names = new HashMap<>();
            byte[] wbx = parts.get("xl/workbook.xml");
            byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
            Map<String, String> relTarget = new HashMap<>();
            if (rels != null) {
                NodeList rl = xml(rels).getElementsByTagName("Relationship");
                for (int i = 0; i < rl.getLength(); i++) {
                    Element r = (Element) rl.item(i);
                    String t = r.getAttribute("Target");
                    if (t.startsWith("/")) t = t.substring(1); else t = "xl/" + t;
                    relTarget.put(r.getAttribute("Id"), t);
                }
            }
            List<String> order = new ArrayList<>();
            if (wbx != null) {
                NodeList sh = xml(wbx).getElementsByTagName("sheet");
                for (int i = 0; i < sh.getLength(); i++) {
                    Element s = (Element) sh.item(i);
                    String rid = s.getAttribute("r:id");
                    String path = relTarget.get(rid);
                    if (path != null) { order.add(path); names.put(path, s.getAttribute("name")); }
                }
            }
            if (order.isEmpty()) {
                TreeMap<String, byte[]> t = new TreeMap<>();
                for (String k : parts.keySet()) if (k.startsWith("xl/worksheets/sheet")) t.put(k, null);
                order.addAll(t.keySet());
            }
            List<Grid> out = new ArrayList<>();
            for (String path : order) {
                byte[] data = parts.get(path);
                if (data == null) continue;
                String nm = names.get(path);
                out.add(sheet(xml(data), nm == null ? path : nm, shared));
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Не удалось прочитать .xlsx: " + e.getMessage(), e);
        }
    }

    private static Grid sheet(Document doc, String name, List<String> shared) {
        Grid g = new Grid(name);
        NodeList cs = doc.getElementsByTagName("c");
        for (int i = 0; i < cs.getLength(); i++) {
            Element c = (Element) cs.item(i);
            int[] rc = ref(c.getAttribute("r"));
            if (rc == null) continue;
            String t = c.getAttribute("t");
            String val = null;
            if ("inlineStr".equals(t)) {
                NodeList ts = c.getElementsByTagName("t");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < ts.getLength(); j++) sb.append(ts.item(j).getTextContent());
                val = sb.toString();
            } else {
                NodeList vs = c.getElementsByTagName("v");
                if (vs.getLength() == 0) continue;
                String v = vs.item(0).getTextContent();
                if ("s".equals(t)) {
                    try { val = shared.get(Integer.parseInt(v.trim())); } catch (Exception ignored) { }
                } else if ("str".equals(t) || "b".equals(t) || "e".equals(t)) {
                    val = v;
                } else {
                    try { val = Grid.numberToText(Double.parseDouble(v)); } catch (Exception ex) { val = v; }
                }
            }
            g.set(rc[0], rc[1], val);
        }
        NodeList ms = doc.getElementsByTagName("mergeCell");
        for (int i = 0; i < ms.getLength(); i++) {
            String r = ((Element) ms.item(i)).getAttribute("ref");
            String[] p = r.split(":");
            if (p.length != 2) continue;
            int[] a = ref(p[0]), b = ref(p[1]);
            if (a != null && b != null) g.merges.add(new int[]{a[0], b[0], a[1], b[1]});
        }
        return g;
    }

    /** "AB12" -> {11, 27} (с нуля). */
    static int[] ref(String s) {
        if (s == null || s.isEmpty()) return null;
        int i = 0, col = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(s.charAt(i)) - 'A' + 1);
            i++;
        }
        if (i == 0 || i == s.length()) return null;
        try { return new int[]{Integer.parseInt(s.substring(i)) - 1, col - 1}; }
        catch (NumberFormatException e) { return null; }
    }

    private static Document xml(byte[] b) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(b));
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
