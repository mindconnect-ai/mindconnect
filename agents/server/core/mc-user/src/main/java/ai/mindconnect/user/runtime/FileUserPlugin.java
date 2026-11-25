package ai.mindconnect.user.runtime;

import java.nio.file.Path;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.user.adapter.file.FileApiTokenRepository;
import ai.mindconnect.user.adapter.file.FileNotificationRepository;
import ai.mindconnect.user.adapter.file.FilePreferenceRepository;
import ai.mindconnect.user.adapter.file.FileUserRepository;
import ai.mindconnect.user.adapter.file.FileUserToolRepository;
import ai.mindconnect.user.port.out.ApiTokenRepository;
import ai.mindconnect.user.port.out.NotificationRepository;
import ai.mindconnect.user.port.out.PreferenceRepository;
import ai.mindconnect.user.port.out.UserRepository;
import ai.mindconnect.user.port.out.UserToolRepository;

/** The users on files, under {@code <dataDir>/system}, written by the runtime's mapper factory. */
public final class FileUserPlugin extends UserPlugin {

    private final Path dataDir;

    /** @param dataDir the installation's data directory ({@code mindconnect.data.base-dir}) */
    public FileUserPlugin(Path dataDir, EncryptionHelper encryption, String installationZone) {
        super(encryption, installationZone);
        this.dataDir = dataDir;
    }

    @Override
    protected Stores stores(PluginRegistry registry) {
        JsonMapperFactory json = registry.get(JsonMapperFactory.class);
        return new Stores() {
            @Override public UserRepository users() { return new FileUserRepository(dataDir, json); }
            @Override public ApiTokenRepository apiTokens() { return new FileApiTokenRepository(dataDir, json); }
            @Override public UserToolRepository userTools() { return new FileUserToolRepository(dataDir, json); }
            @Override public NotificationRepository notifications() {
                return new FileNotificationRepository(dataDir, json);
            }
            @Override public PreferenceRepository preferences() { return new FilePreferenceRepository(dataDir, json); }
        };
    }
}
