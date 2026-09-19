package ai.mindconnect.adminui.ui.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring decides at request time, not at start-up, whether two handlers
 * claim the same route — so {@code POST /{provider}} beside {@code POST /{id}}
 * boots without a word and answers 500 to every save. Two path variables in
 * the same position are the same route whatever they are called. This reads
 * every controller's mappings and says so before a user does.
 */
@DisplayName("The admin UI's routes")
class AdminUiRoutesTest {

    @Test
    @DisplayName("are distinct once the names of the path variables are ignored")
    void noTwoHandlersOnOneRoute() throws Exception {
        List<String> routes = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = onClass == null || onClass.path().length == 0 ? new String[]{""} : onClass.path();
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                RequestMethod[] verbs = mapping.method().length == 0
                        ? new RequestMethod[]{RequestMethod.GET} : mapping.method();
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        for (RequestMethod verb : verbs) {
                            routes.add(verb + " " + (prefix + path).replaceAll("\\{[^}]*}", "{}"));
                        }
                    }
                }
            }
        }
        assertThat(routes).hasSizeGreaterThan(20).doesNotHaveDuplicates();
    }

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("ai.mindconnect.adminui")) {
            found.add(Class.forName(definition.getBeanClassName()));
        }
        return found;
    }
}
