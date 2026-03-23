package io.github.kaluchi.jdtbridge;

/**
 * Lightweight JSON builder for constructing JSON objects and arrays.
 * All string values are automatically escaped per RFC 8259.
 */
class Json {

    private final StringBuilder sb;
    private boolean first = true;
    private final char close;

    private Json(char open, char close) {
        this.sb = new StringBuilder();
        this.sb.append(open);
        this.close = close;
    }

    static Json object() {
        return new Json('{', '}');
    }

    static Json array() {
        return new Json('[', ']');
    }

    /** Convenience: creates {"error":"message"} */
    static String error(String message) {
        return object().put("error", message).toString();
    }

    // ---- Object methods ----

    Json put(String key, String value) {
        comma();
        appendKey(key);
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
        return this;
    }

    Json put(String key, int value) {
        comma();
        appendKey(key);
        sb.append(value);
        return this;
    }

    Json put(String key, long value) {
        comma();
        appendKey(key);
        sb.append(value);
        return this;
    }

    Json put(String key, double value) {
        comma();
        appendKey(key);
        sb.append(value);
        return this;
    }

    Json put(String key, boolean value) {
        comma();
        appendKey(key);
        sb.append(value);
        return this;
    }

    Json put(String key, Json nested) {
        comma();
        appendKey(key);
        sb.append(nested != null ? nested.toString() : "null");
        return this;
    }

    /** Add key:value only if condition is true. */
    Json putIf(boolean condition, String key, String value) {
        if (condition) put(key, value);
        return this;
    }

    Json putIf(boolean condition, String key, boolean value) {
        if (condition) put(key, value);
        return this;
    }

    // ---- Array methods ----

    Json add(String value) {
        comma();
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
        return this;
    }

    Json add(Json nested) {
        comma();
        sb.append(nested != null ? nested.toString() : "null");
        return this;
    }

    Json add(int value) {
        comma();
        sb.append(value);
        return this;
    }

    // ---- Output ----

    @Override
    public String toString() {
        return sb.toString() + close;
    }

    // ---- Internals ----

    private void comma() {
        if (!first) sb.append(',');
        first = false;
    }

    private void appendKey(String key) {
        sb.append('"').append(escape(key)).append("\":");
    }

    // ---- Parsing ----

    /** Parse a flat JSON object into a string map. Handles strings, numbers, booleans, null. */
    static java.util.Map<String, Object> parse(String json) {
        var map = new java.util.LinkedHashMap<String, Object>();
        if (json == null) return map;
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return map;
        int pos = 1; // skip '{'
        int len = json.length() - 1; // skip '}'
        while (pos < len) {
            pos = skipWhitespace(json, pos, len);
            if (pos >= len) break;
            if (json.charAt(pos) != '"') break;
            // parse key
            int keyStart = pos + 1;
            int keyEnd = findClosingQuote(json, keyStart);
            if (keyEnd < 0) break;
            String key = unescape(json, keyStart, keyEnd);
            pos = keyEnd + 1;
            // skip : with whitespace
            pos = skipWhitespace(json, pos, len);
            if (pos >= len || json.charAt(pos) != ':') break;
            pos = skipWhitespace(json, pos + 1, len);
            if (pos >= len) break;
            // parse value
            char c = json.charAt(pos);
            if (c == '"') {
                int valStart = pos + 1;
                int valEnd = findClosingQuote(json, valStart);
                if (valEnd < 0) break;
                map.put(key, unescape(json, valStart, valEnd));
                pos = valEnd + 1;
            } else if (c == 't' || c == 'f') {
                if (json.startsWith("true", pos)) {
                    map.put(key, Boolean.TRUE);
                    pos += 4;
                } else if (json.startsWith("false", pos)) {
                    map.put(key, Boolean.FALSE);
                    pos += 5;
                } else break;
            } else if (c == 'n') {
                if (json.startsWith("null", pos)) {
                    map.put(key, null);
                    pos += 4;
                } else break;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                int numStart = pos;
                boolean isFloat = false;
                if (c == '-') pos++;
                while (pos < len && ((json.charAt(pos) >= '0'
                        && json.charAt(pos) <= '9')
                        || json.charAt(pos) == '.')) {
                    if (json.charAt(pos) == '.') isFloat = true;
                    pos++;
                }
                String num = json.substring(numStart, pos);
                if (isFloat) {
                    map.put(key, Double.parseDouble(num));
                } else {
                    long val = Long.parseLong(num);
                    map.put(key, val <= Integer.MAX_VALUE
                            && val >= Integer.MIN_VALUE
                            ? (int) val : val);
                }
            } else if (c == '{' || c == '[') {
                // nested object/array — store as raw string
                int depth = 1;
                int start = pos;
                char open = c, close = (c == '{') ? '}' : ']';
                pos++;
                boolean inStr = false;
                while (pos < len && depth > 0) {
                    char ch = json.charAt(pos);
                    if (inStr) {
                        if (ch == '\\') pos++;
                        else if (ch == '"') inStr = false;
                    } else {
                        if (ch == '"') inStr = true;
                        else if (ch == open) depth++;
                        else if (ch == close) depth--;
                    }
                    pos++;
                }
                map.put(key, json.substring(start, pos));
            } else break;
            // skip comma
            pos = skipWhitespace(json, pos, len);
            if (pos < len && json.charAt(pos) == ',') pos++;
        }
        return map;
    }

    /** Get a string value from parsed map, or null. */
    static String getString(java.util.Map<String, Object> map,
            String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    /** Get an int value from parsed map, or defaultValue. */
    static int getInt(java.util.Map<String, Object> map,
            String key, int defaultValue) {
        Object v = map.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        return defaultValue;
    }

    /** Get a boolean value from parsed map, or defaultValue. */
    static boolean getBool(java.util.Map<String, Object> map,
            String key, boolean defaultValue) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : defaultValue;
    }

    private static int skipWhitespace(String s, int pos, int len) {
        while (pos < len && Character.isWhitespace(s.charAt(pos)))
            pos++;
        return pos;
    }

    /** Find closing quote, handling escape sequences. */
    private static int findClosingQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    /** Unescape a JSON string segment between two indices. */
    private static String unescape(String s, int from, int to) {
        StringBuilder out = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < to) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (i + 4 < to) {
                            String hex = s.substring(i + 1, i + 5);
                            out.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                    }
                    default -> { out.append('\\'); out.append(next); }
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** JSON string escaping — handles all control characters per RFC 8259. */
    static String escape(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
