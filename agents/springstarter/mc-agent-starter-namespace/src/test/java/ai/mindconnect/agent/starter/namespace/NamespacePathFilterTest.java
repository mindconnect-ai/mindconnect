package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.agent.Namespace;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NamespacePathFilterTest {

    private final NamespacePathFilter filter = new NamespacePathFilter();

    private HttpServletRequest filtered(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setServletPath(uri);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    @Test
    void cutsThePrefixAndKeepsTheNamespaceAsAnAttribute() throws Exception {
        HttpServletRequest seen = filtered("/ns/acme/api/v1/sessions");

        assertThat(seen.getRequestURI()).isEqualTo("/api/v1/sessions");
        assertThat(seen.getServletPath()).isEqualTo("/api/v1/sessions");
        assertThat(seen.getAttribute(NamespacePathFilter.ATTRIBUTE)).isEqualTo(new Namespace("acme"));
        assertThat(seen.getRequestURL().toString()).isEqualTo("http://localhost/api/v1/sessions");
    }

    @Test
    void thePrefixAloneIsTheRoot() throws Exception {
        HttpServletRequest seen = filtered("/ns/acme");

        assertThat(seen.getRequestURI()).isEqualTo("/");
        assertThat(seen.getAttribute(NamespacePathFilter.ATTRIBUTE)).isEqualTo(new Namespace("acme"));
    }

    @Test
    void leavesOtherPathsAndMalformedIdsAlone() throws Exception {
        assertThat(filtered("/api/v1/sessions").getAttribute(NamespacePathFilter.ATTRIBUTE)).isNull();
        assertThat(filtered("/nsx/acme/api").getRequestURI()).isEqualTo("/nsx/acme/api");

        HttpServletRequest bad = filtered("/ns/Acme%20Corp/api");
        assertThat(bad.getAttribute(NamespacePathFilter.ATTRIBUTE)).isNull();
        assertThat(bad.getRequestURI()).isEqualTo("/ns/Acme%20Corp/api");
    }
}
