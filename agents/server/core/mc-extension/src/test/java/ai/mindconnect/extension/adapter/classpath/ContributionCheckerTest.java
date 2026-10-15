package ai.mindconnect.extension.adapter.classpath;

import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContributionCheckerTest {

    private static ExtensionManifest manifest(List<String> providers, List<String> names, List<String> features) {
        return new ExtensionManifest(ExtensionId.of("acme-crm"), null, null, null, null, null, null, null, null,
                new ExtensionManifest.Contributes(new ExtensionManifest.Tools(providers, names), features,
                        null, null, null, null, null, null));
    }

    private final ContributionChecker checker = new ContributionChecker(getClass().getClassLoader(),
            () -> Set.of("acme_contacts", "acme_leads", "bash"));

    @Test
    void a_pattern_says_how_many_tools_it_matches_or_that_it_matches_none() {
        var report = checker.check(manifest(null, List.of("acme_*", "bash", "crm_export"), null));

        assertThat(report.tools()).extracting(ContributionChecker.Check::item, ContributionChecker.Check::found,
                        ContributionChecker.Check::detail)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("acme_*", true, "2 tools"),
                        org.assertj.core.groups.Tuple.tuple("bash", true, "1 tool"),
                        org.assertj.core.groups.Tuple.tuple("crm_export", false, "no tool of that name in the catalog"));
        assertThat(report.allFound()).isFalse();
    }

    @Test
    void a_class_is_registered_present_or_missing() {
        // Nothing on the test classpath registers services; a JDK class is present but unregistered.
        var report = checker.check(manifest(List.of("java.lang.String", "com.acme.Nowhere"), null,
                List.of("java.util.ArrayList")));

        assertThat(report.providers()).extracting(ContributionChecker.Check::found, ContributionChecker.Check::detail)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(false, "on the classpath, but not registered in META-INF/services"),
                        org.assertj.core.groups.Tuple.tuple(false, "not on the classpath"));
        assertThat(report.features()).singleElement().extracting(ContributionChecker.Check::found).isEqualTo(false);
    }

    @Test
    void nothing_declared_is_nothing_to_check() {
        var report = checker.check(manifest(null, null, null));

        assertThat(report).isEqualTo(ContributionChecker.Report.EMPTY);
        assertThat(report.allFound()).isTrue();
    }
}
