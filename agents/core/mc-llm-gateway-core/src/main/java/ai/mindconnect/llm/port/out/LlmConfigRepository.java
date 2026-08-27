package ai.mindconnect.llm.port.out;

import ai.mindconnect.llm.domain.LlmConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LlmConfigRepository {
    void save(LlmConfig config);
    Optional<LlmConfig> findById(UUID id);
    Optional<LlmConfig> findByName(String name);
    List<LlmConfig> findAll();
    void deleteById(UUID id);
}
