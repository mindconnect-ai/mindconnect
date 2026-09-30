package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The {@link PromptSection}s the features contributed, as one thing to hand
 * around. A section that throws is left out of that round's prompt rather
 * than failing the turn: the model does without, say, the memory index, and
 * the log says why.
 */
public final class PromptSections {

    private static final Logger log = LoggerFactory.getLogger(PromptSections.class);

    private static final PromptSections NONE = new PromptSections(List.of());

    private final List<PromptSection> sections;

    private PromptSections(List<PromptSection> sections) {
        this.sections = List.copyOf(sections);
    }

    public static PromptSections of(List<PromptSection> sections) {
        return sections == null || sections.isEmpty() ? NONE : new PromptSections(sections);
    }

    public static PromptSections none() {
        return NONE;
    }

    /** Every section in order, concatenated; empty when none has anything to say. */
    public String render(AgentDefinition def, AgentSession session) {
        if (sections.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (PromptSection section : sections) {
            try {
                String text = section.render(def, session);
                if (text != null) out.append(text);
            } catch (RuntimeException e) {
                log.warn("Prompt section {} failed and is left out: {}",
                        section.getClass().getSimpleName(), e.toString());
            }
        }
        return out.toString();
    }
}
