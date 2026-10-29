package io.github.nikhilucumberlands.loadgate.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader and writer, kept in-tree so the tool has no runtime dependencies.
 *
 * <p>Objects parse to {@link LinkedHashMap}, arrays to {@link ArrayList}, integers to {@link Long}
 * (or {@link Double} when they do not fit) and all other numbers to {@link Double}.
 */
public final class Json {

    private static final int MAX_DEPTH = 256;

    private Json() {}

    /** Parses any JSON value. */
    public static Object parse(String text) {
        Reader reader = new Reader(text);
        reader.skipWhitespace();
        Object value = reader.readValue(0);
        reader.skipWhitespace();
        if (!reader.atEnd()) {
            throw reader.error("unexpected trailing content");
        }
        return value;
    }

    /** Parses text that must hold a JSON object. */
    public static Map<String, Object> parseObject(String text) {
        Map<String, Object> map = asObject(parse(text));
        if (map == null) {
            throw new JsonException("expected a JSON object");
        }
        return map;
    }

    /** Returns the value as a JSON object, or {@code null} when it is something else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /** Returns the value as a JSON array, or {@code null} when it is something else. */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List<?> ? (List<Object>) value : null;
    }

    /** Returns the value as a string, or {@code null} when it is something else. */
    public static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    /** Returns the value as a double, or NaN when it is not a number. */
    public static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    /** Compact, single-line JSON. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, false, 0);
        return out.toString();
    }

    /** Indented JSON for files people read. Arrays of plain values stay on one line. */
    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, true, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object value, boolean pretty, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            quote(out, s);
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (value instanceof Double || value instanceof Float) {
            out.append(formatDouble(((Number) value).doubleValue()));
        } else if (value instanceof Number n) {
            out.append(n);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map, pretty, depth);
        } else if (value instanceof Iterable<?> items) {
            writeArray(out, items, pretty, depth);
        } else if (value instanceof double[] array) {
            writeArray(out, Arrays.stream(array).boxed().toList(), pretty, depth);
        } else if (value instanceof int[] array) {
            writeArray(out, Arrays.stream(array).boxed().toList(), pretty, depth);
        } else if (value instanceof Object[] array) {
            writeArray(out, Arrays.asList(array), pretty, depth);
        } else {
            quote(out, value.toString());
        }
    }

    /** Plain decimal notation for everyday magnitudes (0.000124 rather than 1.24E-4). */
    private static String formatDouble(double d) {
        if (!Double.isFinite(d)) {
            return "null";
        }
        double abs = Math.abs(d);
        if (abs == 0 || (abs >= 1e-7 && abs < 1e15)) {
            String plain = new java.math.BigDecimal(Double.toString(d)).toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        return Double.toString(d);
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, boolean pretty, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<?, ?> entry = it.next();
            if (pretty) {
                newline(out, depth + 1);
            }
            quote(out, String.valueOf(entry.getKey()));
            out.append(pretty ? ": " : ":");
            write(out, entry.getValue(), pretty, depth + 1);
            if (it.hasNext()) {
                out.append(',');
            }
        }
        if (pretty) {
            newline(out, depth);
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Iterable<?> items, boolean pretty, int depth) {
        Iterator<?> it = items.iterator();
        if (!it.hasNext()) {
            out.append("[]");
            return;
        }
        boolean inline = !pretty || allScalars(items);
        out.append('[');
        while (it.hasNext()) {
            if (!inline) {
                newline(out, depth + 1);
            }
            write(out, it.next(), pretty, depth + 1);
            if (it.hasNext()) {
                out.append(inline && pretty ? ", " : ",");
            }
        }
        if (!inline) {
            newline(out, depth);
        }
        out.append(']');
    }

    private static boolean allScalars(Iterable<?> items) {
        for (Object item : items) {
            if (item instanceof Map<?, ?> || item instanceof Iterable<?> || item instanceof Object[]) {
                return false;
            }
        }
        return true;
    }

    private static void newline(StringBuilder out, int depth) {
        out.append('\n');
        out.append("  ".repeat(depth));
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
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
        out.append('"');
    }

    private static final class Reader {
        private final String s;
        private int pos;

        Reader(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        JsonException error(String message) {
            return new JsonException(message + " at position " + pos);
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> readObject(int depth) {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error("expected a string key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return map;
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> readArray(int depth) {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    return list;
                } else {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String readString() {
            pos++;
            StringBuilder sb = null;
            int start = pos;
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    String result = sb == null ? s.substring(start, pos) : sb.append(s, start, pos).toString();
                    pos++;
                    return result;
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(s, start, pos);
                    pos++;
                    if (atEnd()) {
                        throw error("unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("bad escape '\\" + e + "'");
                    }
                    start = pos;
                    continue;
                }
                if (c < 0x20) {
                    throw error("control character in string");
                }
                pos++;
            }
        }

        private Object readNumber() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            if (peek('0')) {
                pos++;
            } else if (isDigit()) {
                while (isDigit()) {
                    pos++;
                }
            } else {
                throw error("bad number");
            }
            boolean integral = true;
            if (peek('.')) {
                integral = false;
                pos++;
                if (!isDigit()) {
                    throw error("bad number");
                }
                while (isDigit()) {
                    pos++;
                }
            }
            if (peek('e') || peek('E')) {
                integral = false;
                pos++;
                if (peek('+') || peek('-')) {
                    pos++;
                }
                if (!isDigit()) {
                    throw error("bad number");
                }
                while (isDigit()) {
                    pos++;
                }
            }
            String token = s.substring(start, pos);
            if (integral) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException tooLarge) {
                    return Double.parseDouble(token);
                }
            }
            return Double.parseDouble(token);
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("unexpected token");
            }
            pos += word.length();
            return value;
        }

        private boolean isDigit() {
            return pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9';
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }
    }
}
