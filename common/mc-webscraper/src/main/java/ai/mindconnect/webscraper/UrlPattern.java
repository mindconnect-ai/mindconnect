package ai.mindconnect.webscraper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UrlPattern {
    public enum Mode {
        EQUALS, EQUALS_IGNORECASE, CONTAINS, STARTSWITH, ENDSWITH, REGEX, WILDCARD
    }

    String pattern;
    Mode mode = Mode.STARTSWITH;

    public boolean matches(String url) {

        switch (mode) {
        case EQUALS:
            return url.equals(pattern);
        case EQUALS_IGNORECASE:
            return url.equalsIgnoreCase(pattern);
        case CONTAINS:
            return url.contains(pattern);
        case STARTSWITH:
            return url.startsWith(pattern);
        case ENDSWITH:
            return url.endsWith(pattern);
        case REGEX:
            return url.matches(pattern);
        case WILDCARD:
            return StringWildcardMatcher.isMatch(url, pattern);
        default:
            return false;
        }
    }
}
