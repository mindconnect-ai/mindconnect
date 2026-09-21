package ai.mindconnect.adminui.branding;

import ai.mindconnect.ui.assets.SuiAsset;
import ai.mindconnect.ui.assets.SuiAssetRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SPA shell carries the asset registry's stylesheets — and ahead of the
 * branding ones. Linked by the browser module instead, they would land at the
 * end of the head, and an extension's default rule would beat a branding rule
 * of the same weight.
 */
class BrandingShellControllerTest {

    @Test
    void extension_stylesheets_come_before_the_branding_ones_so_branding_still_wins() {
        BrandingProperties properties = new BrandingProperties();
        properties.setStylesheets(List.of("/branding/acme.css"));
        SuiAssetRegistry registry = new SuiAssetRegistry(getClass().getClassLoader(),
                List.of(() -> List.of(SuiAsset.css("probe.css", "/sui-ext/probe/probe.css"))));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("suiAssetRegistry", registry);

        String html = new BrandingShellController(properties, beans.getBeanProvider(SuiAssetRegistry.class))
                .index(new MockHttpServletRequest()).getBody();

        assertThat(html).contains("href=\"/sui-ext/probe/probe.css\" data-sui-asset=\"probe.css\"");
        assertThat(html.indexOf("/sui-ext/probe/probe.css")).isLessThan(html.indexOf("/branding/acme.css"));
        assertThat(html.indexOf("/branding/acme.css")).isLessThan(html.indexOf("</head>"));
    }

    @Test
    void without_a_registry_the_shell_is_served_as_before() {
        String html = new BrandingShellController(new BrandingProperties())
                .index(new MockHttpServletRequest()).getBody();

        assertThat(html).doesNotContain("data-sui-asset");
        assertThat(html).contains("/js/app.js");
    }
}
