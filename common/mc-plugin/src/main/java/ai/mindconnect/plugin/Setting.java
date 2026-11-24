package ai.mindconnect.plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * One named value of the installation that a plugin reads instead of being
 * handed it: where things are kept, how long a step may take. A plugin
 * declares the settings it reads ({@link Plugin#settings()}), so that the
 * host can say which exist, check them once when it starts and tell a
 * misspelt key from a known one.
 *
 * <p>A setting has a default or it is required — there is no "maybe". The
 * one exception is a setting only one case needs, a data directory when
 * things are kept on files: it is {@link #whenNeeded()}, not checked at the
 * start and an error where it is read and missing.
 *
 * <p>A setting can stand in for a shared one: {@code skills.persistence}
 * {@link #sharedWith shared with} {@code persistence} is its own value where
 * one is set and the installation's otherwise.
 *
 * <p>Values are text, numbers, switches and paths. An object — a data
 * source, a clock — is a service of the registry, not a setting.
 *
 * @param key          the name it is set under, e.g. {@code skills.persistence}
 * @param description  what it decides, for whoever sets it
 * @param parser       turns the text that was set into the value; throws
 *                     {@link IllegalArgumentException} for text it cannot read
 * @param defaultValue the value when nothing is set; {@code null} for none
 * @param sharedKeys   the keys asked, in order, when {@code key} is not set
 * @param required     whether the host must not start without a value
 */
public record Setting<T>(String key, String description, Function<String, T> parser, T defaultValue,
                         List<String> sharedKeys, boolean required) {

    public Setting {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A setting needs a key");
        }
        sharedKeys = List.copyOf(sharedKeys);
    }

    /** A required setting read with {@code parser}. */
    public static <T> Setting<T> of(String key, String description, Function<String, T> parser) {
        return new Setting<>(key, description, parser, null, List.of(), true);
    }

    public static Setting<String> text(String key, String description) {
        return of(key, description, Function.identity());
    }

    public static Setting<Integer> number(String key, String description) {
        return of(key, description, text -> {
            try {
                return Integer.valueOf(text.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a whole number");
            }
        });
    }

    /** {@code true} or {@code false}, in any case; anything else is refused rather than read as off. */
    public static Setting<Boolean> flag(String key, String description) {
        return of(key, description, text -> switch (text.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("neither true nor false");
        });
    }

    public static Setting<Path> path(String key, String description) {
        return of(key, description, text -> Path.of(text.strip()));
    }

    /** The same setting with a value for when nothing is set; it is no longer required. */
    public Setting<T> defaultsTo(T value) {
        return new Setting<>(key, description, parser, value, sharedKeys, false);
    }

    /** The same setting, also asking {@code sharedKey} when its own key is not set. */
    public Setting<T> sharedWith(String sharedKey) {
        List<String> keys = new ArrayList<>(sharedKeys);
        keys.add(sharedKey);
        return new Setting<>(key, description, parser, defaultValue, keys, required);
    }

    /** The same setting, needed in one case only: not checked at the start, an error where it is read and missing. */
    public Setting<T> whenNeeded() {
        return new Setting<>(key, description, parser, defaultValue, sharedKeys, false);
    }

    /** Its own key, then the shared ones: where a value is looked for, in order. */
    public List<String> keys() {
        List<String> keys = new ArrayList<>();
        keys.add(key);
        keys.addAll(sharedKeys);
        return keys;
    }
}
