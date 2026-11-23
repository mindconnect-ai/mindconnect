package ai.mindconnect.agent.cli.local.file;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.ModelSettingsResolver;

/**
 * Model settings from {@code .mc/settings.json}: the working directory's as
 * the stronger layer, the home's below it.
 */
public final class FileModelSettingsResolver implements ModelSettingsResolver {

    private final HarnessDirectories directories;
    private final ObjectMapper json;

    public FileModelSettingsResolver(HarnessDirectories directories, ObjectMapper json) {
        this.directories = directories;
        this.json = json;
    }

    @Override
    public List<ModelSettings> layers(Session session) {
        return directories.configDirectories(session).stream()
                .map(config -> SettingsFile.in(config, json).modelSettings())
                .toList();
    }
}
