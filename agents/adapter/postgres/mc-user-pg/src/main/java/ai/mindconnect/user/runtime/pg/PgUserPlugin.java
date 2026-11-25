package ai.mindconnect.user.runtime.pg;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.user.adapter.pg.PgApiTokenRepository;
import ai.mindconnect.user.adapter.pg.PgNotificationRepository;
import ai.mindconnect.user.adapter.pg.PgPreferenceRepository;
import ai.mindconnect.user.adapter.pg.PgUserRepository;
import ai.mindconnect.user.adapter.pg.PgUserToolRepository;
import ai.mindconnect.user.port.out.ApiTokenRepository;
import ai.mindconnect.user.port.out.NotificationRepository;
import ai.mindconnect.user.port.out.PreferenceRepository;
import ai.mindconnect.user.port.out.UserRepository;
import ai.mindconnect.user.port.out.UserToolRepository;
import ai.mindconnect.user.runtime.UserPlugin;

/**
 * The users in Postgres, on the runtime's {@link Sql}; each store creates its
 * table when it is first asked for.
 */
public final class PgUserPlugin extends UserPlugin {

    public PgUserPlugin(EncryptionHelper encryption, String installationZone) {
        super(encryption, installationZone);
    }

    @Override
    protected Stores stores(PluginRegistry registry) {
        Sql sql = registry.get(Sql.class);
        return new Stores() {
            @Override public UserRepository users() { return new PgUserRepository(sql).initSchema(); }
            @Override public ApiTokenRepository apiTokens() { return new PgApiTokenRepository(sql).initSchema(); }
            @Override public UserToolRepository userTools() { return new PgUserToolRepository(sql).initSchema(); }
            @Override public NotificationRepository notifications() {
                return new PgNotificationRepository(sql).initSchema();
            }
            @Override public PreferenceRepository preferences() { return new PgPreferenceRepository(sql).initSchema(); }
        };
    }
}
