package ai.mindconnect.agent.registry.adapter.initialdata;

import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;
import ai.mindconnect.agent.registry.port.out.RegistrySourceRepository;
import ai.mindconnect.initialdata.ImportInitialDataInstaller;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Seeds the registries an application ships with, the way it ships its seed
 * agents and MCP servers: one JSON file per registry under
 * {@code initial-data/registries/}, installed on start when no registry of that
 * id is configured yet.
 *
 * <p>The file is a stored registry — {@code owner} and {@code repo}, and
 * optionally {@code name}, {@code ref}, {@code indexPath}, {@code tokenEnvVar},
 * {@code baseUrl}, {@code enabled}. Its id is the file name, so
 * {@code mindconnect-ai-mc-registry.json} seeds the registry the screen would
 * create for {@code mindconnect-ai/mc-registry}; an {@code id} in the file is
 * ignored rather than trusted to agree with it.
 *
 * <p>A registry that is already configured is left as it is — an operator who
 * pinned a tag or disabled it keeps that. One that was deleted comes back on
 * the next start, as a deleted seed agent does.
 */
public class InitialRegistrySources {

    /** Where applications put their registries. */
    public static final String LOCATION = "classpath*:initial-data/registries/*.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private InitialRegistrySources() {
    }

    /**
     * Installs every registry file at {@code locationPattern} whose id is not
     * configured yet.
     *
     * @return the ids that were newly installed
     */
    public static List<String> install(RegistrySourceRepository repository, String locationPattern) {
        return new ImportInitialDataInstaller(
                id -> repository.findById(RegistrySourceId.of(id)).isPresent(),
                (id, resource) -> {
                    JsonNode tree = MAPPER.readTree(resource.getInputStream());
                    if (!(tree instanceof ObjectNode object)) {
                        throw new IllegalArgumentException("A registry file has to be a JSON object");
                    }
                    object.put("id", id);
                    object.remove("version");
                    repository.save(MAPPER.treeToValue(object, RegistrySource.class));
                })
                .install(locationPattern);
    }
}
