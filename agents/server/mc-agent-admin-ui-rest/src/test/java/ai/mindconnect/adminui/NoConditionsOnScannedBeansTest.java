package ai.mindconnect.adminui;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin UI's classes are found by component scanning, and scanning runs
 * before any auto-configuration: a {@code @ConditionalOnBean} or
 * {@code @ConditionalOnMissingBean} on a scanned class is asked about beans
 * that do not exist yet, whatever the finished context will hold. It never
 * matches, nothing says so, and the class is simply gone — the namespace guard
 * was, for a whole release. Ask for the bean through an {@code ObjectProvider}
 * at use instead, or register the class from an auto-configuration.
 */
class NoConditionsOnScannedBeansTest {

    @Test
    void no_scanned_class_is_conditional_on_a_bean() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> offenders = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("ai.mindconnect.adminui")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (type.isAnnotationPresent(ConditionalOnBean.class)
                    || type.isAnnotationPresent(ConditionalOnMissingBean.class)) {
                offenders.add(type.getName());
            }
        }
        assertThat(offenders).as("scanned classes with a bean condition that can never see its bean").isEmpty();
    }
}
