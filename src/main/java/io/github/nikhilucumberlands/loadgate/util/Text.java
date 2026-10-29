package io.github.nikhilucumberlands.loadgate.util;

/** String helpers for log and report output. */
public final class Text {

    private Text() {}

    /** Collapses whitespace and cuts the text to {@code max} characters. */
    public static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
