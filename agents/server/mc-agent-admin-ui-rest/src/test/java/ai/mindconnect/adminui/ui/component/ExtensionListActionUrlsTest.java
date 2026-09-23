package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.adapter.classpath.ClasspathAudit;
import ai.mindconnect.extension.adapter.classpath.ContributionChecker;
import ai.mindconnect.extension.domain.BrandActivation;
import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.service.ExtensionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The screen's buttons point where the controller listens, and say what the manifest said. */
class ExtensionListActionUrlsTest {

    private static final Extension ACME = new Extension(new ExtensionManifest(ExtensionId.of("acme-crm"),
            "Acme CRM", "1.4.0", "Contacts and leads", new ExtensionManifest.Vendor("acme", "Acme GmbH", null),
            null, null, true, List.of("tools:call"),
            new ExtensionManifest.Contributes(new ExtensionManifest.Tools(List.of("ai.acme.CrmTools"), List.of("acme_*")),
                    null, null,
                    new ExtensionManifest.Ui(List.of(new ExtensionManifest.Ui.MenuEntry("nav-acme", "Acme", "/admin/acme", null, null, null, null)),
                            null, null),
                    null, List.of("LlmCallTraceRepository"), null, new ExtensionManifest.Persistence("ext_acme", null))),
            "acme-crm-1.4.0.jar");

    private static String json(ExtensionService.Status status, List<ExtensionRegistry.Problem> problems,
                               List<ClasspathAudit.UnmanagedJar> unmanaged) throws Exception {
        return new ObjectMapper().writeValueAsString(
                new ExtensionListComponent(List.of(status), problems, unmanaged).render());
    }

    @Test
    void an_extension_that_is_on_offers_the_switch_off() throws Exception {
        String json = json(new ExtensionService.Status(ACME, true, Optional.empty()), List.of(), List.of());

        assertThat(json).contains("\"url\":\"/admin/extensions/acme-crm/disable\"")
                .doesNotContain("/acme-crm/enable").doesNotContain("/acme-crm/reset");
        assertThat(json).contains("Acme CRM 1.4.0").contains("Acme GmbH").contains("acme_*")
                .contains("LlmCallTraceRepository").contains("schema ext_acme").contains("acme-crm-1.4.0.jar")
                .contains("the manifest's default");
        // About and Brings are two tables, in that order.
        assertThat(json.indexOf("\"title\":\"About\"")).isPositive().isLessThan(json.indexOf("\"title\":\"Brings\""));
    }

    @Test
    void the_classpath_s_verdict_stands_beside_each_item() throws Exception {
        var report = new ContributionChecker.Report(
                List.of(new ContributionChecker.Check("ai.acme.CrmTools", false, "not on the classpath")),
                List.of(),
                List.of(new ContributionChecker.Check("acme_*", true, "3 tools")));
        String json = new ObjectMapper().writeValueAsString(new ExtensionListComponent(
                List.of(new ExtensionService.Status(ACME, true, Optional.empty())), List.of(), List.of(),
                Map.of("acme-crm", report)).render());

        assertThat(json).contains("acme_* ✓ 3 tools").contains("ai.acme.CrmTools ✗ not on the classpath")
                .contains("not everything declared was found");
    }

    @Test
    void a_decided_off_extension_offers_switch_on_and_back_to_default() throws Exception {
        var decision = ExtensionActivation.of(ExtensionId.of("acme-crm"), false, UserId.of("david"));
        String json = json(new ExtensionService.Status(ACME, false, Optional.of(decision)), List.of(), List.of());

        assertThat(json).contains("\"url\":\"/admin/extensions/acme-crm/enable\"")
                .contains("\"url\":\"/admin/extensions/acme-crm/reset\"")
                .contains("Acme CRM 1.4.0  (off)").contains("decided here by david");
    }

    @Test
    void problems_and_unmanaged_jars_are_listed_when_there_are_any() throws Exception {
        String clean = json(new ExtensionService.Status(ACME, true, Optional.empty()), List.of(), List.of());
        assertThat(clean).doesNotContain("extension-problems").doesNotContain("extension-unmanaged");

        String noisy = json(new ExtensionService.Status(ACME, true, Optional.empty()),
                List.of(new ExtensionRegistry.Problem(null, "x.jar: manifest unreadable")),
                List.of(new ClasspathAudit.UnmanagedJar("old-module.jar", "com.example", List.of("com.example.Tools"))));
        assertThat(noisy).contains("extension-problems").contains("x.jar: manifest unreadable")
                .contains("extension-unmanaged").contains("old-module.jar").contains("com.example.Tools");
    }

    @Test
    void in_the_brand_s_namespace_the_brand_s_switches_are_offered() throws Exception {
        var brandDecision = BrandActivation.of("erni", ExtensionId.of("acme-crm"), false, false, UserId.of("david"));
        var status = new ExtensionService.Status(ACME, false, ExtensionService.Origin.BRAND, Optional.empty(),
                Optional.of(brandDecision), Optional.of("erni"));
        String json = new ObjectMapper().writeValueAsString(new ExtensionListComponent(
                List.of(status), List.of(), List.of(), Map.of(), true).render());

        assertThat(json).contains("\"url\":\"/admin/extensions/acme-crm/brand/enable\"")
                .contains("\"url\":\"/admin/extensions/acme-crm/brand/lock\"")
                .contains("\"url\":\"/admin/extensions/acme-crm/brand/reset\"")
                .contains("\"url\":\"/admin/extensions/acme-crm/enable\"")   // the namespace may still override
                .contains("as decided for the brand erni").contains("For the brand erni");

        String elsewhere = new ObjectMapper().writeValueAsString(new ExtensionListComponent(
                List.of(status), List.of(), List.of(), Map.of(), false).render());
        assertThat(elsewhere).doesNotContain("/brand/");
    }

    @Test
    void a_locked_brand_decision_and_the_operator_s_list_take_the_namespace_s_switch_away() throws Exception {
        var locked = new ExtensionService.Status(ACME, false, ExtensionService.Origin.BRAND_LOCKED, Optional.empty(),
                Optional.of(BrandActivation.of("erni", ExtensionId.of("acme-crm"), false, true, null)), Optional.of("erni"));
        String json = new ObjectMapper().writeValueAsString(new ExtensionListComponent(
                List.of(locked), List.of(), List.of(), Map.of(), true).render());
        assertThat(json).doesNotContain("/acme-crm/enable\"").doesNotContain("/acme-crm/disable\"")
                .contains("locked for the brand erni").contains("/brand/unlock");

        var operator = new ExtensionService.Status(ACME, false, ExtensionService.Origin.OPERATOR, Optional.empty(),
                Optional.empty(), Optional.of("erni"));
        String off = new ObjectMapper().writeValueAsString(new ExtensionListComponent(
                List.of(operator), List.of(), List.of(), Map.of(), true).render());
        assertThat(off).doesNotContain("/acme-crm/enable\"").doesNotContain("/brand/")
                .contains("switched off by the operator");
    }
}
