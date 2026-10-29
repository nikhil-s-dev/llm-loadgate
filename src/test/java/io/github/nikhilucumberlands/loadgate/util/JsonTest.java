package io.github.nikhilucumberlands.loadgate.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesNestedStructures() {
        Map<String, Object> obj = Json.parseObject(
                "{\"a\": 1, \"b\": [true, null, 2.5], \"c\": {\"d\": \"x\"}, \"e\": -3e2}");
        assertEquals(1L, obj.get("a"));
        assertEquals(List.of(true, 2.5), Json.asList(obj.get("b")).stream().filter(v -> v != null).toList());
        assertNull(Json.asList(obj.get("b")).get(1));
        assertEquals("x", Json.asObject(obj.get("c")).get("d"));
        assertEquals(-300.0, obj.get("e"));
    }

    @Test
    void decodesEscapesAndUnicode() {
        Object value = Json.parse("\"line\\nbreak \\\"quoted\\\" \\u00e9 \\ud83d\\ude00\"");
        assertEquals("line\nbreak \"quoted\" é 😀", value);
    }

    @Test
    void writesWhatItReads() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("text", "tab\there \"q\" \u0001");
        original.put("list", List.of(1, 2.5, false));
        original.put("nested", Map.of("k", "v"));
        original.put("none", null);
        Object roundTrip = Json.parse(Json.write(original));
        Map<String, Object> back = Json.asObject(roundTrip);
        assertEquals(original.get("text"), back.get("text"));
        assertEquals(List.of(1L, 2.5, false), back.get("list"));
        assertEquals(Map.of("k", "v"), back.get("nested"));
        assertTrue(back.containsKey("none"));
        assertEquals(Json.write(original), Json.write(Json.parse(Json.pretty(original))));
    }

    @Test
    void writesNonFiniteNumbersAsNull() {
        assertEquals("[null,null]", Json.write(List.of(Double.NaN, Double.POSITIVE_INFINITY)));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(JsonException.class, () -> Json.parse("[1, 2"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": 1} trailing"));
        assertThrows(JsonException.class, () -> Json.parse("01"));
        assertThrows(JsonException.class, () -> Json.parseObject("[1]"));
    }
}
