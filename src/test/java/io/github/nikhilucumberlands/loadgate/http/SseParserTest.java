package io.github.nikhilucumberlands.loadgate.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseParserTest {

    private static List<String> events(String... lines) {
        SseParser parser = new SseParser();
        List<String> events = new ArrayList<>();
        for (String line : lines) {
            String data = parser.feed(line);
            if (data != null) {
                events.add(data);
            }
        }
        String last = parser.finish();
        if (last != null) {
            events.add(last);
        }
        return events;
    }

    @Test
    void emitsOneEventPerBlankLine() {
        assertEquals(List.of("{\"a\":1}", "{\"a\":2}", "[DONE]"),
                events("data: {\"a\":1}", "", "data: {\"a\":2}", "", "data: [DONE]", ""));
    }

    @Test
    void joinsMultiLineDataAndSkipsOtherFields() {
        assertEquals(List.of("first\nsecond"),
                events(": keep-alive comment", "event: message", "id: 7", "data: first", "data:second", ""));
    }

    @Test
    void flushesAFinalEventWithoutTrailingBlankLine() {
        assertEquals(List.of("tail"), events("data: tail"));
    }

    @Test
    void ignoresEventsWithoutData() {
        SseParser parser = new SseParser();
        assertNull(parser.feed("event: ping"));
        assertNull(parser.feed(""));
        assertNull(parser.finish());
    }

    @Test
    void recognisesSseLines() {
        assertTrue(SseParser.isSseLine("data: x"));
        assertTrue(SseParser.isSseLine(""));
        assertTrue(SseParser.isSseLine(": comment"));
        assertFalse(SseParser.isSseLine("{\"id\":\"cmpl-1\"}"));
    }
}
