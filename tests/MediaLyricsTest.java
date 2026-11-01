package com.jiusis.mistep;

public final class MediaLyricsTest {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        String original = "[00:10.000]原文一\n[00:20.000]原文二\n[00:30.000]原文三";
        String translation = "[00:10.500]译文一\n[00:20.500]译文二\n[00:30.500]译文三";
        MediaLyrics merged = MediaLyrics.parse(MediaLyrics.combineTranslation(original, translation));
        check(merged.lineAt(20_000).equals("原文二\n译文二"), "offset translation is paired with its nearest source line");

        MediaLyrics.Window window = merged.windowAt(20_000, 9);
        check(window.lineCount == 6, "paired lyrics expose their actual rendered row count");
        check(window.currentLine == 2, "current highlight starts on the current source line");
        check(window.text.equals("原文一\n译文一\n原文二\n译文二\n原文三\n译文三"),
                "translated rows stay adjacent to their source rows");

        MediaLyrics exact = MediaLyrics.parse(original + "\n[00:10.000]译文一\n[00:20.000]译文二\n[00:30.000]译文三");
        MediaLyrics.Window exactWindow = exact.windowAt(20_000, 9);
        check(exactWindow.currentLine == 2, "exact-timestamp translations do not shift the highlight");
        System.out.println("MediaLyrics: " + checks + " checks passed");
    }
}
