package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiStack;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advice wraps every page an admin controller returns with the app shell,
 * and it decides that on the body. It used to decide on the declared return
 * type — and a save that answers a page on success and a toast patch on a
 * stale version is declared {@code ResponseEntity<?>}, so the detail page
 * came back without the menu after every save of an agent, an LLM config or
 * a skill.
 */
class AdminLayoutAdviceTest {

    private static AdminLayoutAdvice advice() {
        return new AdminLayoutAdvice(new AdminLayoutFactory(false,
                new BuildInfo(Optional.empty(), Optional.empty()), Optional.empty(), none(), none()));
    }

    /** An ObjectProvider with nothing in it — the factory asks it only {@code getIfAvailable}. */
    private static <T> ObjectProvider<T> none() {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return null; }
        };
    }

    @Test
    void every_handler_is_looked_at() {
        assertThat(advice().supports(null, null)).isTrue();
    }

    @Test
    void a_patch_passes_through_untouched() {
        UiPatch patch = UiPatch.of();

        Object out = advice().beforeBodyWrite(patch, null, null, null, null, null);

        assertThat(out).isSameAs(patch);
    }

    @Test
    void a_page_gets_the_shell() {
        UiPage page = UiPage.of("/admin/agents/a1", UiStack.of("body"));

        Object out = advice().beforeBodyWrite(page, null, null, null, null, null);

        assertThat(out).isInstanceOf(UiPage.class);
        assertThat(((UiPage) out).getNode().getId()).isEqualTo(AdminLayoutAdvice.LAYOUT_ID);
    }

    @Test
    void a_page_already_wrapped_is_left_alone() {
        UiPage page = UiPage.of("/admin/agents/a1", UiStack.of("body"));
        Object once = advice().beforeBodyWrite(page, null, null, null, null, null);

        Object twice = advice().beforeBodyWrite(once, null, null, null, null, null);

        assertThat(twice).isSameAs(once);
    }
}
