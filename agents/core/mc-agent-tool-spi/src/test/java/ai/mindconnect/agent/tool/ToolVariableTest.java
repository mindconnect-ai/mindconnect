package ai.mindconnect.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolVariableTest {

    @Test
    void a_name_that_could_not_be_a_variable_is_refused_at_the_declaration() {
        // Not at first use: a tool that declares "MC EMAIL HOST" is broken, and
        // the moment to say so is the one where the class name is still in the stack.
        assertThatThrownBy(() -> ToolVariable.required("MC EMAIL", "Mail", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MC EMAIL");
        assertThatThrownBy(() -> ToolVariable.required("1_HOST", "Mail", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_variable_without_a_title_is_titled_by_its_name() {
        assertThat(new ToolVariable("MC_EMAIL_HOST", null, null, true, false, null, null).title())
                .isEqualTo("MC_EMAIL_HOST");
        assertThat(new ToolVariable("MC_EMAIL_HOST", "  ", null, true, false, null, null).title())
                .isEqualTo("MC_EMAIL_HOST");
    }

    @Test
    void a_blank_default_is_no_default() {
        // A blank value cannot be stored (EnvVarResolver.requireValid), so a
        // declaration carrying one would provision something that is refused.
        assertThat(ToolVariable.withDefault("MC_EMAIL_PORT", "Port", null, "  ").hasDefault()).isFalse();
        assertThat(ToolVariable.withDefault("MC_EMAIL_PORT", "Port", null, "993").hasDefault()).isTrue();
    }

    @Test
    void the_factories_say_what_they_mean() {
        assertThat(ToolVariable.required("A", "a", null).required()).isTrue();
        assertThat(ToolVariable.required("A", "a", null).secret()).isFalse();
        assertThat(ToolVariable.secret("B", "b", null).required()).isTrue();
        assertThat(ToolVariable.secret("B", "b", null).secret()).isTrue();
        assertThat(ToolVariable.optionalSecret("C", "c", null).required()).isFalse();
        assertThat(ToolVariable.optionalSecret("C", "c", null).secret()).isTrue();
        assertThat(ToolVariable.optional("D", "d", null).required()).isFalse();
        assertThat(ToolVariable.withDefault("E", "e", null, "1").required()).isFalse();
    }

    @Test
    void declared_by_is_stamped_without_touching_anything_else() {
        ToolVariable declared = ToolVariable.secret("MC_EMAIL_PASSWORD", "Password", "the one").declaredBy("email");

        assertThat(declared.declaredBy()).isEqualTo("email");
        assertThat(declared.name()).isEqualTo("MC_EMAIL_PASSWORD");
        assertThat(declared.title()).isEqualTo("Password");
        assertThat(declared.description()).isEqualTo("the one");
        assertThat(declared.secret()).isTrue();
        assertThat(declared.required()).isTrue();
    }
}
