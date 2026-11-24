package ai.mindconnect.agent.voice;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.llm.audio.port.in.LlmSpeech;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

/**
 * Everything a voice conversation reaches out to, handed over as one so a
 * conversation per connection stays cheap to create.
 */
public record VoicePorts(AgentDefinitionRepository agents,
                         AgentSessionRepository sessions,
                         AgentSessionService sessionService,
                         AgentChatService chat,
                         LlmTranscription transcription,
                         LlmSpeech speech,
                         LlmConfigRepository llmConfigs,
                         ScopeSupplier scope,
                         VoiceSettings settings) {
}
