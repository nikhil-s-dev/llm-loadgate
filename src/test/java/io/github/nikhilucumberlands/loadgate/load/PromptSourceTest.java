package io.github.nikhilucumberlands.loadgate.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptSourceTest {

    @Test
    void readsJsonlWithPromptsAndMessages(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("prompts.jsonl");
        Files.writeString(file, """
                {"prompt": "plain text"}

                {"messages": [{"role": "system", "content": "be brief"}, {"role": "user", "content": "hi"}]}
                """);
        PromptSource source = PromptSource.fromFile(file);
        assertEquals("plain text", source.get(0).text());
        assertEquals(2, source.get(1).messages().size());
        assertEquals("be brief\nhi", source.get(1).text());
        assertEquals("plain text", source.get(2).text(), "prompts are reused in order");
    }

    @Test
    void readsPlainTextOnePromptPerLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("prompts.txt");
        Files.writeString(file, "first\n\nsecond\n");
        PromptSource source = PromptSource.fromFile(file);
        assertEquals("second", source.get(1).text());
        assertEquals("user", source.get(0).messages().get(0).get("role"));
    }

    @Test
    void reportsTheLineOfABadEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.jsonl");
        Files.writeString(file, "{\"prompt\": \"ok\"}\n{\"text\": \"wrong key\"}\n");
        IOException e = assertThrows(IOException.class, () -> PromptSource.fromFile(file));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void syntheticPromptsAreUniqueAndReproducible() {
        PromptSource source = PromptSource.synthetic(50, 42);
        assertNotEquals(source.get(0).text(), source.get(1).text());
        assertEquals(source.get(5).text(), PromptSource.synthetic(50, 42).get(5).text());
        int words = source.get(0).text().split("\\s+").length;
        assertTrue(words >= 50 && words <= 56, "about 50 words, got " + words);
    }
}
