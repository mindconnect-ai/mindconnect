package ai.mindconnect.adminui;

import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A screen an extension's manifest names is a same-URL section: a browser
 * navigation gets the SPA shell. Its REST API under {@code /api/<id>/} is
 * not — a browser that opens it gets the JSON the controller answers, the
 * same a script does.
 */
class AdminSameUrlFilterExtensionRoutesTest {

    private final ExtensionRegistry registry = new ExtensionRegistry(List.of(new Extension(
            new ExtensionManifest(ExtensionId.of("scheduler"), null, null, null, null, null, null, null, null,
                    new ExtensionManifest.Contributes(null, null, null, new ExtensionManifest.Ui(null, List.of(
                            new ExtensionManifest.Ui.Route("/admin/scheduler/**", List.of("ADMIN")),
                            new ExtensionManifest.Ui.Route("/api/scheduler/jobs/**", List.of("ADMIN", "USER"))),
                            null), null, null, null, null)), "scheduler.jar")));

    private final AdminSameUrlFilter filter = new AdminSameUrlFilter(new ObjectProvider<>() {
        @Override public ExtensionRegistry getIfAvailable() { return registry; }
    });

    @Test
    void a_browser_on_an_extension_s_screen_gets_the_shell() throws Exception {
        MockHttpServletResponse response = navigate("/admin/scheduler/jobs");

        assertThat(response.getForwardedUrl()).isEqualTo("/index.html");
    }

    @Test
    void a_browser_on_an_extension_s_api_gets_what_the_controller_answers() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(htmlGet("/api/scheduler/jobs"), response, chain);

        assertThat(response.getForwardedUrl()).isNull();
        assertThat(chain.getRequest()).as("passed on to the controller").isNotNull();
    }

    private MockHttpServletResponse navigate(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(htmlGet(path), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest htmlGet(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Accept", "text/html,application/xhtml+xml");
        return request;
    }
}
