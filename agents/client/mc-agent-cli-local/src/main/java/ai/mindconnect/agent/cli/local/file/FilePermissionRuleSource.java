package ai.mindconnect.agent.cli.local.file;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRule;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionRuleSource;

/**
 * Permission rules from the {@code permissions} of the settings files: the
 * working directory's before the home directory's, and within one file
 * deny before ask before allow — the narrower place and the stricter word
 * win.
 */
public final class FilePermissionRuleSource implements PermissionRuleSource {

    private final HarnessDirectories directories;
    private final ObjectMapper json;

    public FilePermissionRuleSource(HarnessDirectories directories, ObjectMapper json) {
        this.directories = directories;
        this.json = json;
    }

    @Override
    public List<PermissionRule> rules(Session session) {
        List<PermissionRule> rules = new ArrayList<>();
        for (Path config : directories.configDirectories(session)) {
            SettingsFile settings = SettingsFile.in(config, json);
            settings.permissions("deny").forEach(tool -> rules.add(new PermissionRule(tool, PermissionEffect.DENY)));
            settings.permissions("ask").forEach(tool -> rules.add(new PermissionRule(tool, PermissionEffect.ASK)));
            settings.permissions("allow").forEach(tool -> rules.add(new PermissionRule(tool, PermissionEffect.ALLOW)));
        }
        return rules;
    }
}
