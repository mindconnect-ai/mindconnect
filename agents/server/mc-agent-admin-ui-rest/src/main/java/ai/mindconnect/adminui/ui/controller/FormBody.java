package ai.mindconnect.adminui.ui.controller;

import java.util.List;
import java.util.Map;

/** Wraps the JSON body sent by collectNode(), providing typed accessors. */
record FormBody(Map<String, Object> data) {

    String str(String key) {
        Object v = data.get(key);
        return v instanceof String s ? s : v != null ? v.toString() : null;
    }

    @SuppressWarnings("unchecked")
    List<String> strList(String key) {
        Object v = data.get(key);
        if (v instanceof List<?> list) return (List<String>) list;
        if (v instanceof String s && !s.isBlank()) return List.of(s.split("\\s*,\\s*"));
        return List.of();
    }

    double dbl(String key, double fallback) {
        Object v = data.get(key);
        if (v instanceof Number n) return n.doubleValue();
        try { return v != null ? Double.parseDouble(v.toString()) : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }

    int num(String key, int fallback) {
        Object v = data.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return v != null ? Integer.parseInt(v.toString()) : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }

    long longNum(String key, long fallback) {
        Object v = data.get(key);
        if (v instanceof Number n) return n.longValue();
        try { return v != null ? Long.parseLong(v.toString()) : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }

    Boolean bool(String key) {
        Object v = data.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return null;
    }

    boolean bool(String key, boolean fallback) {
        Boolean v = bool(key);
        return v != null ? v : fallback;
    }

    Integer numOrNull(String key) {
        Object v = data.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        String s = v.toString();
        if (s.isBlank()) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }
}
