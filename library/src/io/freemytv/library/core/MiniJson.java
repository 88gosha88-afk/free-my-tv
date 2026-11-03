package io.freemytv.library.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader (TMDB answers) and writer (the saved catalog), with no dependencies so it runs anywhere. */
public final class MiniJson {
    private MiniJson() {}

    /** Parses JSON into Map, List, String, Double, Boolean or null. Returns null for broken input. */
    public static Object parse(String text) {
        if (text == null) return null;
        try {
            Reader r = new Reader(text);
            Object v = r.value();
            r.skip();
            return r.i == text.length() ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String str(Object o, String key) {
        Object v = map(o).get(key);
        return v instanceof String ? (String) v : "";
    }

    public static double num(Object o, String key) {
        Object v = map(o).get(key);
        return v instanceof Double ? (Double) v : 0;
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) { this.s = s; }

        void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalStateException("bad json at " + i);
            return Double.valueOf(s.substring(start, i));
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skip();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skip();
                String k = string();
                skip();
                if (s.charAt(i++) != ':') throw new IllegalStateException("colon");
                m.put(k, value());
                skip();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalStateException("comma");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            skip();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                skip();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalStateException("comma");
            }
        }

        String string() {
            if (s.charAt(i) != '"') throw new IllegalStateException("quote");
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }
    }
}
