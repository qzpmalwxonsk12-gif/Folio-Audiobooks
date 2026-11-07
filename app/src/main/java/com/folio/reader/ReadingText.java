package com.folio.reader;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Platform-independent segmentation, shared by import and tests. */
public final class ReadingText {
    public static final int MAX_CHUNK = 3200;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*(?:['’][\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*)*");
    private static final Pattern ABBREVIATION = Pattern.compile("(?i).*(?:\\b(?:mr|mrs|ms|dr|prof|sr|jr|st|vs|etc|fig|no)|\\b[A-Z])\\.$");

    public static final class Unit {
        public final int page;
        public final String text;
        public final int words;
        public final boolean sentenceEnd;
        public Unit(int page, String text, boolean sentenceEnd) {
            this.page = page; this.text = text; this.sentenceEnd = sentenceEnd; this.words = countWords(text);
        }
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace("\u0000", "").replace("\u00ad", "")
            .replaceAll("(?<=\\p{L})-\\s*\\r?\\n\\s*(?=\\p{Ll})", "")
            .replaceAll("[\\p{Z}\\s]+", " ").trim();
    }

    public static int countWords(String text) {
        Matcher m = WORD.matcher(text); int count = 0;
        while (m.find()) count++;
        return count;
    }

    public static List<Unit> split(String raw, int page) {
        String text = normalize(raw);
        ArrayList<Unit> units = new ArrayList<>();
        if (text.isEmpty()) return units;
        BreakIterator breaks = BreakIterator.getSentenceInstance(Locale.US);
        breaks.setText(text);
        int start = breaks.first(); String pending = "";
        for (int end = breaks.next(); end != BreakIterator.DONE; start = end, end = breaks.next()) {
            String sentence = (pending + text.substring(start, end)).trim();
            if (ABBREVIATION.matcher(sentence).matches() && end < text.length()) {
                pending = sentence + " "; continue;
            }
            pending = "";
            appendChunks(units, sentence, page);
        }
        if (!pending.trim().isEmpty()) appendChunks(units, pending.trim(), page);
        return units;
    }

    private static void appendChunks(List<Unit> out, String sentence, int page) {
        while (sentence.length() > MAX_CHUNK) {
            int cut = sentence.lastIndexOf(' ', MAX_CHUNK);
            if (cut < MAX_CHUNK / 2) cut = MAX_CHUNK;
            if (Character.isHighSurrogate(sentence.charAt(cut - 1))) cut--;
            String part = sentence.substring(0, cut).trim();
            if (!part.isEmpty()) out.add(new Unit(page, part, false));
            sentence = sentence.substring(cut).trim();
        }
        if (!sentence.isEmpty()) out.add(new Unit(page, sentence, true));
    }

    private ReadingText() { }
}
