package ai.mindconnect.adminui.ui.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring decides at request time, not at start-up, whether two handlers
 * claim the same path — so {@code POST /{toolName}} beside {@code POST /{id}}
 * boots fine and answers 500 to every add. Two path variables in the same
 * position are the same route whatever they are called; this reads the
 * annotations and says so before a user does.
 */
@DisplayName("The user-tool routes")
class UserToolUiControllerMappingsTest {

    @Test
    @DisplayName("are distinct once the names of the path variables are ignored")
    void noTwoHandlersOnOneRoute() {
        List<String> routes = new ArrayList<>();
        for (Method method : UserToolUiController.class.getDeclaredMethods()) {
            for (String path : paths(method)) {
                routes.add(verb(method) + " " + path.replaceAll("\\{[^}]*}", "{}"));
            }
        }
        assertThat(routes).isNotEmpty().doesNotHaveDuplicates();
    }

    private static String[] paths(Method m) {
        if (m.isAnnotationPresent(GetMapping.class)) return m.getAnnotation(GetMapping.class).value();
        if (m.isAnnotationPresent(PostMapping.class)) return m.getAnnotation(PostMapping.class).value();
        if (m.isAnnotationPresent(PutMapping.class)) return m.getAnnotation(PutMapping.class).value();
        if (m.isAnnotationPresent(DeleteMapping.class)) return m.getAnnotation(DeleteMapping.class).value();
        return new String[0];
    }

    private static String verb(Method m) {
        if (m.isAnnotationPresent(GetMapping.class)) return "GET";
        if (m.isAnnotationPresent(PostMapping.class)) return "POST";
        if (m.isAnnotationPresent(PutMapping.class)) return "PUT";
        return "DELETE";
    }
}
