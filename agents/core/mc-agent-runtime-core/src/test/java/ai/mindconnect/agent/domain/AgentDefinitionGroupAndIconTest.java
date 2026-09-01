package ai.mindconnect.agent.domain;

import ai.mindconnect.common.Namespace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group and icon are machine names that a person types into a form, so what is
 * stored has to be case-folded. Otherwise "Assistants" files an agent under a
 * second rubric that renders under the same heading as "assistants", and
 * "Telescope" looks up a sprite symbol that does not exist and draws nothing.
 */
class AgentDefinitionGroupAndIconTest {

    private static AgentDefinition withGroup(String group) {
        return withGroupAndIcon(group, null);
    }

    private static AgentDefinition withGroupAndIcon(String group, String icon) {
        return new AgentDefinition(UUID.randomUUID(), new Namespace("local"), "n", "d", group, icon,
                "prompt", null, "cfg", 5, null, AgentDefinitionStatus.ACTIVE,
                List.of(), List.of(), null, null, null, null);
    }

    @Test
    void foldsCaseAndTrims() {
        assertThat(withGroup("Assistants").group()).isEqualTo("assistants");
        assertThat(withGroup("  SUB-Agents ").group()).isEqualTo("sub-agents");
    }

    @Test
    void blankIsNoGroupAtAll() {
        assertThat(withGroup("   ").group()).isNull();
        assertThat(withGroup("").group()).isNull();
        assertThat(withGroup(null).group()).isNull();
    }

    @Test
    void anAgentFiledUnderNothingIsFiledUnderGeneral() {
        assertThat(withGroup(null).groupOrDefault()).isEqualTo("general");
        assertThat(withGroup("  ").groupOrDefault()).isEqualTo("general");
        assertThat(withGroup("Utilities").groupOrDefault()).isEqualTo("utilities");
    }

    @Test
    void withGroupRefilesAndNormalisesToo() {
        assertThat(withGroup("assistants").withGroup("Sub-Agents").group()).isEqualTo("sub-agents");
    }

    /**
     * The icon is a sprite symbol id, and those are lower-case: "Telescope"
     * would resolve to nothing and draw an empty box.
     */
    @Test
    void theIconIsFoldedTheSameWay() {
        assertThat(withGroupAndIcon(null, "Telescope").icon()).isEqualTo("telescope");
        assertThat(withGroupAndIcon(null, "  Book-Open ").icon()).isEqualTo("book-open");
        assertThat(withGroupAndIcon(null, "  ").icon()).isNull();
        assertThat(withGroupAndIcon(null, null).icon()).isNull();
        assertThat(withGroupAndIcon(null, "bot").withIcon("Wand-Sparkles").icon())
                .isEqualTo("wand-sparkles");
    }

    /** The pre-group constructor still exists for callers that predate the field. */
    @Test
    void legacyConstructorLeavesTheAgentUngrouped() {
        var def = new AgentDefinition(UUID.randomUUID(), new Namespace("local"), "n", "d",
                "prompt", null, "cfg", 5, null, AgentDefinitionStatus.ACTIVE,
                List.of(), List.of(), null, null);
        assertThat(def.group()).isNull();
        assertThat(def.icon()).isNull();
        assertThat(def.groupOrDefault()).isEqualTo("general");
    }
}
