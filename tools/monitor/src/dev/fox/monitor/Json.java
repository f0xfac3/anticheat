package dev.fox.monitor;

import java.util.*;

/** Small, bounded JSON reader for registry records; no executable deserialization. */
final class Json {
    private final String text;
    private int index;
    private Json(String text) {
        this.text = text;
    }
    static Object read(String text) {
        if (text == null || text.length() > 2_000_000)
            throw new IllegalArgumentException("JSON size");
        Json parser = new Json(text);
        Object result = parser.value(0);
        parser.space();
        if (parser.index != text.length())
            throw new IllegalArgumentException("Trailing JSON");
        return result;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        return (Map<String, Object>) read(text);
    }
    static String string(Map<String, Object> row, String key) {
        return String.valueOf(row.getOrDefault(key, ""));
    }
    static double number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }
    private void space() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index)))
            index++;
    }
    private char take() {
        if (index >= text.length())
            throw new IllegalArgumentException("Truncated JSON");
        return text.charAt(index++);
    }
    private void expect(char c) {
        space();
        if (take() != c)
            throw new IllegalArgumentException("Expected " + c);
    }
    private Object value(int depth) {
        if (depth > 24)
            throw new IllegalArgumentException("JSON depth");
        space();
        char c = take();
        if (c == '"')
            return quoted();
        if (c == '{') {
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (index < text.length() && text.charAt(index) == '}') {
                index++;
                return map;
            }
            do {
                expect('"');
                String key = quoted();
                expect(':');
                if (map.containsKey(key))
                    throw new IllegalArgumentException("Duplicate JSON key");
                map.put(key, value(depth + 1));
                space();
                c = take();
            } while (c == ',');
            if (c != '}')
                throw new IllegalArgumentException("Object terminator");
            return map;
        }
        if (c == '[') {
            List<Object> list = new ArrayList<>();
            space();
            if (index < text.length() && text.charAt(index) == ']') {
                index++;
                return list;
            }
            do {
                list.add(value(depth + 1));
                space();
                c = take();
            } while (c == ',');
            if (c != ']')
                throw new IllegalArgumentException("Array terminator");
            return list;
        }
        int start = index - 1;
        while (index < text.length() && ",]} \t\r\n".indexOf(text.charAt(index)) < 0)
            index++;
        String token = text.substring(start, index);
        if (token.equals("true"))
            return true;
        if (token.equals("false"))
            return false;
        if (token.equals("null"))
            return null;
        if (!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
            throw new IllegalArgumentException("Invalid JSON number");
        double d = Double.parseDouble(token);
        if (!Double.isFinite(d))
            throw new IllegalArgumentException("Nonfinite JSON");
        return d;
    }
    private String quoted() {
        StringBuilder result = new StringBuilder();
        while (true) {
            char c = take();
            if (c == '"')
                return result.toString();
            if (c < 32)
                throw new IllegalArgumentException("Control in string");
            if (c == '\\') {
                c = take();
                if (c == 'u') {
                    if (index + 4 > text.length())
                        throw new IllegalArgumentException("Unicode escape");
                    c = (char) Integer.parseInt(text.substring(index, index + 4), 16);
                    index += 4;
                } else {
                    int n = "\"\\/bfnrt".indexOf(c);
                    if (n < 0)
                        throw new IllegalArgumentException("Escape");
                    c = "\"\\/\b\f\n\r\t".charAt(n);
                }
            }
            result.append(c);
        }
    }
}
