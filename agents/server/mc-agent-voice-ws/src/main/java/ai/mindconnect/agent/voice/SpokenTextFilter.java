package ai.mindconnect.agent.voice;

import java.util.regex.Pattern;

/**
 * Takes out of a sentence what must not be read aloud: Markdown marks
 * (emphasis, headings, list bullets, code ticks, link targets) and control
 * markers in double square brackets, such as {@code [[END]]}, which a prompt
 * may ask an agent to write for its caller rather than for the listener.
 */
final class SpokenTextFilter {

    private static final Pattern MARKER = Pattern.compile("\\[\\[[^\\]]*\\]\\]");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern MARKS = Pattern.compile("[*_`#>]+");
    private static final Pattern BULLET = Pattern.compile("^\\s*([-+]|\\d+\\.)\\s+");

    private SpokenTextFilter() {
    }

    static String spoken(String sentence) {
        String text = MARKER.matcher(sentence).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = BULLET.matcher(text).replaceAll("");
        text = MARKS.matcher(text).replaceAll("");
        return text.replaceAll("\\s+", " ").strip();
    }
}
