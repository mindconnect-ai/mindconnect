package ai.mindconnect.webscraper;

public class StringWildcardMatcher {

    /**
     * Method to check if the given string matches the pattern.
     *
     * @param text    The input string to be checked for matching.
     * @param pattern The pattern to be matched against the input string. The pattern is a simple * wildcard pattern
     * @return True if the string matches the pattern, false otherwise.
     */
    public static boolean isMatch(String text, String pattern) {
        int tLen = text.length();
        int pLen = pattern.length();

        // Two pointers for text and pattern respectively
        int tIdx = 0, pIdx = 0;

        // Wildcard matching support
        int lastStarIdx = -1;
        int match = 0;

        // Iterate over the text
        while (tIdx < tLen) {
            // If characters match or pattern has '?' (universal match for one char)
            if (pIdx < pLen && (pattern.charAt(pIdx) == text.charAt(tIdx)
                    || pattern.charAt(pIdx) == '*')) {
                // Move both pointers forward
                if (pattern.charAt(pIdx) == '*') {
                    // Mark position of '*', and match can happen from text position tIdx
                    lastStarIdx = pIdx;
                    match = tIdx;
                    pIdx++;  // Skip '*'
                } else {
                    pIdx++;
                    tIdx++;
                }
            }
            // If mismatch happens after '*' found, backtrack to the next possible match
            else if (lastStarIdx != -1) {
                // Backtrack: pattern pointer back to '*' and match text after that
                pIdx = lastStarIdx + 1;
                match++;
                tIdx = match;
            }
            // Mismatch without possibility to backtrack
            else {
                return false;
            }
        }

        // Process the remaining pattern (it can only be trailing '*')
        while (pIdx < pLen && pattern.charAt(pIdx) == '*') {
            pIdx++;
        }

        // If pattern is completely processed, it's a match
        return pIdx == pLen;
    }

    // Test cases to check the matcher functionality
    public static void main(String[] args) {
        // Sample test cases
        System.out.println(isMatch("hello", "he*o"));  // True
        System.out.println(isMatch("hello", "*lo"));   // True
        System.out.println(isMatch("hello", "he*"));   // True
        System.out.println(isMatch("hello", "he*la")); // False
        System.out.println(isMatch("hello", "h*l*o")); // True
        System.out.println(isMatch("hello", "*"));     // True
        System.out.println(isMatch("hello", "*x"));    // False
    }
}
