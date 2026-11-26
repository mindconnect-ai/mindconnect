package ai.mindconnect.llm.gateway.app;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.service.LlmConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Managing the configs of a namespace, by name. Keys are shown as stored: prefer {@code ${VAR}} placeholders. */
@RestController
@RequestMapping("/api/ns/{namespace}/configs")
@Tag(name = "Configs", description = "Manage the LLM configs of a namespace")
class LlmConfigController {

    private final LlmConfigRepository configs;
    private final LlmConfigService service;

    LlmConfigController(LlmConfigRepository configs, LlmConfigService service) {
        this.configs = configs;
        this.service = service;
    }

    @Operation(summary = "Every config of the namespace")
    @GetMapping
    List<LlmConfig> list(@PathVariable String namespace) {
        return configs.findAll();
    }

    @Operation(summary = "One config by name")
    @GetMapping("/{name}")
    LlmConfig get(@PathVariable String namespace, @PathVariable String name) {
        return configs.findByName(name).orElseThrow(() -> DomainException.notFound("LLM config", name));
    }

    /**
     * Creates or replaces a config. Without an id it replaces the config of the
     * same name, or is a new one; with a version it saves only if that is still
     * the stored one.
     */
    @Operation(summary = "Create or replace a config")
    @PostMapping
    LlmConfig save(@PathVariable String namespace, @RequestBody LlmConfig config) {
        return service.save(config.id() != null ? config : withId(config,
                configs.findByName(config.name()).map(LlmConfig::id).orElseGet(LlmConfigId::random)));
    }

    @Operation(summary = "Delete a config and its prices")
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String namespace, @PathVariable String name) {
        LlmConfig existing = get(namespace, name);
        service.delete(existing.id());
    }

    private static LlmConfig withId(LlmConfig c, LlmConfigId id) {
        return new LlmConfig(id, c.name(), c.provider(), c.model(), c.baseUrl(), c.apiKey(),
                c.defaultTemperature(), c.maxOutputTokens(), c.additionalParams(), c.contextWindowTokens(),
                c.isAlias(), c.delegatesTo(), c.retry(), c.rateLimit(), c.type(), c.capabilities(),
                c.fallbackModels(), c.version());
    }
}
