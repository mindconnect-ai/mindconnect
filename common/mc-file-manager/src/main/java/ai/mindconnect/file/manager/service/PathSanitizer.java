package ai.mindconnect.file.manager.service;

public class PathSanitizer {

    /**
     * Sanitize the given file path by removing all instances of "../" and "/" from the beginning
     * and in between the path.
     *
     * @param path The input file path to sanitize.
     * @return A sanitized file path.
     */
    public static String sanitizePath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }

        // Remove all instances of "../" and leading "/"
        String sanitizedPath = path.replaceAll("\\.\\.\\/", "").replaceAll("^\\/+", "");

        // Split the path by "/" to further clean any remaining instances of ".."
        String[] pathSegments = sanitizedPath.split("/");
        StringBuilder cleanPath = new StringBuilder();

        for (String segment : pathSegments) {
            if (!segment.equals("..") && !segment.isEmpty()) {
                if (cleanPath.length() > 0) {
                    cleanPath.append("/");
                }
                cleanPath.append(segment);
            }
        }

        return cleanPath.toString();
    }

    public static void main(String[] args) {
        // Example usage
        String unsafePath = "../../unsafe/directory/../../../path/file.txt";
        String safePath = sanitizePath(unsafePath);
        System.out.println("Sanitized Path: " + safePath);
    }
}
