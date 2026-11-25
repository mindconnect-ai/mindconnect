package ai.mindconnect.user.runtime;

import java.time.ZoneId;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.common.env.EnvVarSource;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.user.adapter.env.EncryptingUserRepository;
import ai.mindconnect.user.adapter.env.UserEnvVarResolver;
import ai.mindconnect.user.port.out.ApiTokenRepository;
import ai.mindconnect.user.port.out.NotificationRepository;
import ai.mindconnect.user.port.out.PreferenceRepository;
import ai.mindconnect.user.port.out.UserRepository;
import ai.mindconnect.user.port.out.UserToolRepository;
import ai.mindconnect.user.service.UserService;
import ai.mindconnect.user.service.UserTimeZones;

/**
 * The users of an installation, in the runtime they feed: the runtime asks
 * their time zones for the prompt and their own variables for a
 * configuration's {@code ${VAR}}, so the stores live where the runtime is
 * built, not in the host around it — a host bean would need the runtime's
 * mapper before the runtime exists. A subclass says where the users are kept.
 *
 * <p>Installation-wide, not per namespace: a user is the same person in every
 * namespace. A user's own variables are secrets, kept encrypted when the
 * installation has a key.
 */
public abstract class UserPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(UserPlugin.class);

    /** The stores of one place; each is asked once, when the runtime first needs it. */
    protected interface Stores {

        UserRepository users();

        ApiTokenRepository apiTokens();

        UserToolRepository userTools();

        NotificationRepository notifications();

        PreferenceRepository preferences();
    }

    private final EncryptionHelper encryption;
    private final String installationZone;

    /**
     * @param encryption       what a user's own variables are encrypted with; {@code null} keeps them plain
     * @param installationZone the zone of a user without one of their own ({@code mindconnect.time-zone});
     *                         blank or {@code null}: the JVM's
     */
    protected UserPlugin(EncryptionHelper encryption, String installationZone) {
        this.encryption = encryption;
        this.installationZone = installationZone;
    }

    @Override
    public String name() {
        return "users";
    }

    /** The stores, made once the plugins are installed: what they need is in {@code registry} by then. */
    protected abstract Stores stores(PluginRegistry registry);

    @Override
    public void install(PluginRegistry registry) {
        // Read now, not on the first prompt: a zone that is no zone is an error while starting.
        ZoneId zone = installationZone == null || installationZone.isBlank() ? null
                : TimeZones.parse(installationZone).orElseThrow(() -> new IllegalStateException(
                        "mindconnect.time-zone is not a time zone: \"" + installationZone
                                + "\". Use an id such as Europe/Zurich."));
        // One set of stores, made when the first one is asked for.
        Supplier<Stores> stores = new Supplier<>() {
            private Stores made;

            @Override
            public synchronized Stores get() {
                if (made == null) {
                    made = stores(registry);
                }
                return made;
            }
        };
        registry.provide(UserRepository.class, () -> encrypting(stores.get().users()));
        registry.provide(ApiTokenRepository.class, () -> stores.get().apiTokens());
        registry.provide(UserToolRepository.class, () -> stores.get().userTools());
        registry.provide(NotificationRepository.class, () -> stores.get().notifications());
        registry.provide(PreferenceRepository.class, () -> stores.get().preferences());
        registry.provide(UserService.class, () -> new UserService(registry.get(UserRepository.class)));
        registry.provide(TimeZones.class, () -> zone == null
                ? new UserTimeZones(registry.get(UserService.class))
                : new UserTimeZones(registry.get(UserService.class), zone));
        registry.contribute(EnvVarSource.class, new EnvVarSource(() -> new UserEnvVarResolver(
                registry.get(UserRepository.class), registry.get(ScopeSupplier.class))));
    }

    private UserRepository encrypting(UserRepository users) {
        if (encryption == null) {
            log.warn("No EncryptionHelper — users' own variables are stored unencrypted");
            return users;
        }
        EncryptingUserRepository encrypting = new EncryptingUserRepository(users, encryption);
        int rewritten = encrypting.rewriteEarlierScheme();
        if (rewritten > 0) {
            log.info("{} user record(s) rewritten from the earlier encryption scheme to AES-GCM", rewritten);
        }
        return encrypting;
    }
}
