package ai.mindconnect.adminui;

import ai.mindconnect.adminui.namespaces.NamespaceAccessConfig;
import ai.mindconnect.adminui.namespaces.NamespaceWriteGuardConfig;
import ai.mindconnect.adminui.ui.controller.NotificationUiController;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real app, started: the pieces the admin UI's own packages contribute on
 * top of beans from auto-configurations are there. Each of them used to carry
 * a {@code @ConditionalOnBean} on a component-scanned class, which is
 * evaluated before any auto-configuration has run — so the namespace guard,
 * the write guard and the bell's panel were silently missing from every
 * installation while the beans they waited for existed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mindconnect.encryption.secret-key=test-key-32-characters-long-abcd",
        "mindconnect.auth.enabled=false"
})
class ScannedGuardsWiringTest {

    @DynamicPropertySource
    static void tempDataDir(DynamicPropertyRegistry registry) {
        try {
            var dir = Files.createTempDirectory("mc-guards-test");
            registry.add("mindconnect.data.base-dir", dir::toString);
            registry.add("mindconnect.tools.base-dir", dir::toString);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    TestRestTemplate http;

    @Test
    void the_beans_the_scanned_pieces_depend_on_exist() {
        assertThat(context.getBeansOfType(NamespaceService.class)).isNotEmpty();
        assertThat(context.getBeansOfType(NotificationService.class)).isNotEmpty();
    }

    @Test
    void the_namespace_guard_and_the_write_guard_are_installed() {
        assertThat(context.getBeansOfType(NamespaceAccessConfig.class)).hasSize(1);
        assertThat(context.getBeansOfType(NamespaceWriteGuardConfig.class)).hasSize(1);
    }

    @Test
    void the_bell_opens_its_panel() {
        assertThat(context.getBeansOfType(NotificationUiController.class)).hasSize(1);
        var response = http.getForEntity("/admin/api/notifications", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Notifications");
    }
}
