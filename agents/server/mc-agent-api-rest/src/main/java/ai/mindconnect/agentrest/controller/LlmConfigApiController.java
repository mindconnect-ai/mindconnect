package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agentrest.service.LlmConfigTestService;
import ai.mindconnect.llm.domain.LlmConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * External REST API for LLM configs. Persistence goes through the
 * {@link LlmConfigRepository} the host wires — an encrypting decorator in
 * both server apps, so a plaintext {@code apiKey} in the payload is stored
 * {@code enc:}-tagged, same as a UI save. Test calls share
 * {@link LlmConfigTestService} with the admin UI's Test dialog.
 */
@Tag(name = "LLM Configs", description = "Provider configurations (model, base URL, API "
        + "key, limits) that agents and embeddings reference by name.")
@RestController
@RequestMapping("/api/llm-configs")
public class LlmConfigApiController {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigApiController.class);

    public record TestRequest(String message) {}

    private final LlmConfigRepository repository;
    private final LlmConfigTestService testService;

    public LlmConfigApiController(LlmConfigRepository repository, LlmConfigTestService testService) {
        this.repository = repository;
        this.testService = testService;
    }

    /** One provider with the additional-parameter fields its gateway understands. */
    public record ProviderInfo(String name,
                               List<ai.mindconnect.llm.domain.AdditionalParamSpec> additionalParams) {}

    @Operation(summary = "List providers and their additional parameters",
            description = "Every supported provider plus the catalog of keys its gateway reads "
                    + "from a config's generic additionalParams map (label, kind, allowed "
                    + "values, config type they apply to).")
    @GetMapping("/providers")
    public List<ProviderInfo> providers() {
        return java.util.Arrays.stream(ai.mindconnect.llm.domain.LlmProvider.values())
                .map(p -> new ProviderInfo(p.name(), p.additionalParams()))
                .toList();
    }

    @Operation(summary = "List LLM configs")
    @GetMapping
    public List<LlmConfig> findAll() {
        log.info("GET /api/llm-configs");
        return repository.findAll();
    }

    @Operation(summary = "Get an LLM config")
    @GetMapping("/{id}")
    public ResponseEntity<LlmConfig> findById(@PathVariable UUID id) {
        log.info("GET /api/llm-configs/{}", id);
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create or replace an LLM config",
            description = "Saves the config under its id. A plaintext apiKey is encrypted "
                    + "(enc: prefix) by the repository before it hits disk — same as a UI "
                    + "save; enc:/plain:-tagged and ${VAR} values are stored as-is.")
    @PostMapping
    public ResponseEntity<Void> save(@RequestBody LlmConfig config) {
        log.info("POST /api/llm-configs name={} provider={}", config.name(), config.provider());
        repository.save(config);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Delete an LLM config")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        log.info("DELETE /api/llm-configs/{}", id);
        if (repository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sends {@code message} as a one-shot user turn through the config
     * (aliases followed) and returns the reply with token counts — the same
     * check as the admin UI's Test button.
     */
    @Operation(summary = "Test an LLM config",
            description = "Sends the message as a one-shot user turn through the config "
                    + "(aliases followed) and returns the reply with token counts — the "
                    + "same check as the admin UI's Test button.")
    @PostMapping("/{id}/test")
    public ResponseEntity<LlmConfigTestService.Result> test(@PathVariable UUID id,
                                                            @RequestBody TestRequest request) {
        log.info("POST /api/llm-configs/{}/test", id);
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return repository.findById(id)
                .map(config -> ResponseEntity.ok(testService.test(config, request.message())))
                .orElse(ResponseEntity.notFound().build());
    }
}
