package ai.mindconnect.common;

public record Namespace(String value) {

    public static final Namespace DEFAULT = new Namespace("default");

    public Namespace {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Namespace must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
