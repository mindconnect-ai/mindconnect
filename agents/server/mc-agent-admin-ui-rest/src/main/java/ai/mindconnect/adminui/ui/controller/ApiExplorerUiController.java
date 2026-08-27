package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.ui.model.UiIFrame;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "API" section: the agent runtime's complete REST API, explorable via
 * the bundled springdoc Swagger UI ({@code /swagger-ui/index.html}, fed by
 * {@code /v3/api-docs}). Embedded full-height in a {@link UiIFrame} so it
 * lives inside the admin shell like every other section instead of being a
 * bare page the user has to know the URL of.
 */
@RestController
@RequestMapping("/admin/api/api-explorer")
public class ApiExplorerUiController {

    @GetMapping
    public UiPage view() {
        UiIFrame frame = UiIFrame.of("api-explorer", "/swagger-ui/index.html")
                .title("REST API explorer");
        frame.withCssClass("app-fill-frame");
        return UiPage.of("/admin/api-explorer", frame);
    }
}
