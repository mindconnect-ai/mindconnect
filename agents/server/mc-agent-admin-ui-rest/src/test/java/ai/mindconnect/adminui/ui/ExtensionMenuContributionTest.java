package ai.mindconnect.adminui.ui;

import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.service.ExtensionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionMenuContributionTest {

    private static ExtensionManifest.Ui.MenuEntry entry(String id, String label, String href, String group,
                                                        String groupLabel, List<String> roles) {
        return new ExtensionManifest.Ui.MenuEntry(id, label, href, null, group, groupLabel, roles);
    }

    private static ExtensionService.Status status(String id, boolean enabled, ExtensionManifest.Ui.MenuEntry... menu) {
        var manifest = new ExtensionManifest(ExtensionId.of(id), null, null, null, null, null, null, null, null,
                new ExtensionManifest.Contributes(null, null, null,
                        new ExtensionManifest.Ui(List.of(menu), null, null), null, null, null, null));
        return new ExtensionService.Status(new Extension(manifest, id + ".jar"), enabled, Optional.empty());
    }

    @Test
    void a_renderable_entry_of_an_enabled_extension_becomes_a_link_a_declaration_only_does_not() {
        var entries = ExtensionMenuContribution.entries(List.of(
                status("acme-crm", true, entry("nav-acme", "Acme", "/admin/acme", null, null, null),
                        entry("nav-acme-hidden", null, null, null, null, null))), true);

        assertThat(entries).singleElement().satisfies(link -> {
            assertThat(link.id()).isEqualTo("nav-acme");
            assertThat(link.href()).isEqualTo("/admin/acme");
            assertThat(link.isGroup()).isFalse();
        });
    }

    @Test
    void a_switched_off_extension_contributes_nothing() {
        assertThat(ExtensionMenuContribution.entries(List.of(
                status("acme-crm", false, entry("nav-acme", "Acme", "/admin/acme", null, null, null))), true)).isEmpty();
    }

    @Test
    void a_plain_user_sees_only_entries_that_name_the_user_role() {
        var statuses = List.of(status("acme-crm", true,
                entry("nav-acme", "Acme", "/admin/acme", null, null, null),
                entry("nav-my-acme", "My Acme", "/admin/profile/acme", null, null, List.of("ADMIN", "USER"))));

        assertThat(ExtensionMenuContribution.entries(statuses, false)).extracting(AdminMenuContribution.Entry::id)
                .containsExactly("nav-my-acme");
        assertThat(ExtensionMenuContribution.entries(statuses, true)).extracting(AdminMenuContribution.Entry::id)
                .containsExactly("nav-acme", "nav-my-acme");
    }

    @Test
    void entries_with_a_group_are_grouped_under_the_first_label_given() {
        var entries = ExtensionMenuContribution.entries(List.of(
                status("acme-crm", true, entry("nav-acme", "Acme", "/admin/acme", "nav-group-tools", null, null)),
                status("demo", true, entry("nav-demo", "Demo", "/admin/demo", "nav-reports", "Reports", null),
                        entry("nav-demo-2", "Demo 2", "/admin/demo2", "nav-reports", "Ignored", null))), true);

        assertThat(entries).extracting(AdminMenuContribution.Entry::id).containsExactly("nav-group-tools", "nav-reports");
        assertThat(entries.get(0).label()).isEqualTo("nav-group-tools");
        assertThat(entries.get(0).children()).extracting(AdminMenuContribution.Entry::id).containsExactly("nav-acme");
        assertThat(entries.get(1).label()).isEqualTo("Reports");
        assertThat(entries.get(1).children()).extracting(AdminMenuContribution.Entry::id)
                .containsExactly("nav-demo", "nav-demo-2");
    }
}
