package ai.mindconnect.extension.domain;

import java.util.regex.Pattern;

/**
 * The patterns a manifest names tools by: {@code *} matches any run of
 * characters, everything else is literal — {@code acme_*}, {@code crm_export}.
 */
public final class NamePattern {

    private NamePattern() {
    }

    public static boolean matches(String pattern, String name) {
        if (pattern == null || name == null) return false;
        if (!pattern.contains("*")) return pattern.equals(name);
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) regex.append(".*");
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString()).matcher(name).matches();
    }
}
