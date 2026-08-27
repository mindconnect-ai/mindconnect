package ai.mindconnect.file.manager.service;

import java.util.Locale;

public enum SortDirection {
    ASC,
    DESC;

    public boolean isAscending() {
        return this.equals(ASC);
    }

    public boolean isDescending() {
        return this.equals(DESC);
    }

    public static SortDirection fromString(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.US));
        } catch (Exception e) {
            throw new IllegalArgumentException(String.format("Invalid value '%s' for orders given; Has to be either 'desc' or 'asc' (case insensitive)", value), e);
        }
    }
}
