package ai.mindconnect.common;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long total) {

    public int totalPages() {
        return size == 0 ? 0 : (int) Math.ceil((double) total / size);
    }

    public boolean hasNext() { return page < totalPages(); }
    public boolean hasPrev() { return page > 1; }

    public static <T> Page<T> of(List<T> all, int page, int size) {
        int from  = Math.min((page - 1) * size, all.size());
        int to    = Math.min(from + size, all.size());
        return new Page<>(all.subList(from, to), page, size, all.size());
    }
}
