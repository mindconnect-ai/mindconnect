package ai.mindconnect.common;

public record PageRequest(int page, int size) {

    public static final PageRequest DEFAULT = new PageRequest(0, 20);

    public PageRequest {
        if (page < 0) throw new IllegalArgumentException("Page must be >= 0");
        if (size < 1 ) {
            throw new IllegalArgumentException("PageRequest Size must be > 0 ");
        }
    }

    public int offset() {
        return page * size;
    }
}
