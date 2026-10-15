package ai.mindconnect.adminui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An extension switched off in the namespace takes its sidebar entries with
 * it: the ids its manifest declares are left out before the layout sees
 * them, links and group members alike. And an id contributed twice — by a
 * module's bean and by its manifest — appears once, as the first said.
 */
class AdminLayoutFactoryVisibleEntriesTest {

    private static final AdminMenuContribution.Entry USAGE =
            AdminMenuContribution.Entry.of("nav-usage", "Usage", "/admin/usage", "chart");
    private static final AdminMenuContribution.Entry ACME =
            AdminMenuContribution.Entry.of("nav-acme", "Acme", "/admin/acme", "briefcase");

    @Test
    void a_hidden_link_goes_the_rest_stays() {
        var kept = AdminLayoutFactory.visible(List.of(USAGE, ACME), Set.of("nav-acme")::contains);

        assertThat(kept).containsExactly(USAGE);
    }

    @Test
    void a_group_loses_hidden_members_and_goes_once_empty() {
        var reports = AdminMenuContribution.Entry.group("nav-reports", "Reports", "bar-chart", List.of(USAGE, ACME));

        var partly = AdminLayoutFactory.visible(List.of(reports), Set.of("nav-acme")::contains);
        assertThat(partly).singleElement().satisfies(group -> {
            assertThat(group.id()).isEqualTo("nav-reports");
            assertThat(group.children()).containsExactly(USAGE);
        });

        var none = AdminLayoutFactory.visible(List.of(reports), Set.of("nav-acme", "nav-usage")::contains);
        assertThat(none).isEmpty();

        var whole = AdminLayoutFactory.visible(List.of(reports), Set.of("nav-reports")::contains);
        assertThat(whole).isEmpty();
    }

    @Test
    void with_nothing_hidden_the_entries_are_as_contributed() {
        assertThat(AdminLayoutFactory.visible(List.of(USAGE, ACME), id -> false)).containsExactly(USAGE, ACME);
    }

    @Test
    void an_id_contributed_twice_appears_once_as_the_first_said() {
        var fromManifest = AdminMenuContribution.Entry.of("nav-acme", "Acme (manifest)", "/admin/acme-2", null);

        var kept = AdminLayoutFactory.distinct(List.of(ACME, fromManifest, USAGE));

        assertThat(kept).containsExactly(ACME, USAGE);
    }

    @Test
    void groups_with_one_id_merge_members_once_each() {
        var bean = AdminMenuContribution.Entry.group("nav-reports", "Reports", "bar-chart", List.of(USAGE));
        var manifest = AdminMenuContribution.Entry.group("nav-reports", "nav-reports", null, List.of(USAGE, ACME));

        var kept = AdminLayoutFactory.distinct(List.of(bean, manifest));

        assertThat(kept).singleElement().satisfies(group -> {
            assertThat(group.label()).isEqualTo("Reports");
            assertThat(group.icon()).isEqualTo("bar-chart");
            assertThat(group.children()).containsExactly(USAGE, ACME);
        });
    }
}
