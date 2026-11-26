package ai.mindconnect.plugin;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where the installation's settings are read from: a configuration file the
 * host loaded, a map a program built. The host provides one as a service
 * before it installs its plugins; without one every setting has its default.
 *
 * <p>Settings hold for the whole installation and are fixed when it starts.
 * What changes while it runs, or differs from one user to the next, is not
 * a setting.
 */
@FunctionalInterface
public interface SettingSource {

    /** The text set under {@code key}; empty when nothing is. */
    Optional<String> get(String key);

    /**
     * Every key that is set, for a source that can say — the registry then
     * names the ones no plugin declared, which is how a misspelt key is
     * found. A source that cannot tell its keys from somebody else's says
     * none, and nothing is checked.
     */
    default Set<String> keys() {
        return Set.of();
    }

    /** The settings of a map, all of whose keys are settings. */
    static SettingSource of(Map<String, String> settings) {
        Map<String, String> held = Map.copyOf(settings);
        return new SettingSource() {
            @Override
            public Optional<String> get(String key) {
                return Optional.ofNullable(held.get(key));
            }

            @Override
            public Set<String> keys() {
                return held.keySet();
            }
        };
    }
}
