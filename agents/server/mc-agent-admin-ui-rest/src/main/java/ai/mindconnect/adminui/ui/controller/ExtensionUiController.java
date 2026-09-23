package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.ExtensionsPage;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.security.SecurityCurrentUserResolver;
import ai.mindconnect.extension.adapter.classpath.ContributionChecker;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.extension.spring.ExtensionAudit;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Extensions screen: what the classpath brought, and the switches —
 * per namespace, and from a brand's own namespace for the whole brand.
 * Answers JSON on the page URL — a browser asking for HTML gets the shell
 * from the same-URL filter, and the shell fetches this.
 *
 * <p>Admin-only like every screen not on the access interceptor's open
 * list; switching is what an admin of the namespace does. Deciding for a
 * brand is offered only while working in the brand's own namespace, whose
 * admins are the brand's admins.
 */
@RestController
@RequestMapping(ExtensionsPage.NAVIGATE)
public class ExtensionUiController {

    private final ExtensionService extensions;
    private final ObjectProvider<ExtensionAudit> audit;
    private final ObjectProvider<ContributionChecker> checker;
    private final ObjectProvider<ScopeSupplier> scope;

    public ExtensionUiController(ExtensionService extensions, ObjectProvider<ExtensionAudit> audit,
                                 ObjectProvider<ContributionChecker> checker, ObjectProvider<ScopeSupplier> scope) {
        this.extensions = extensions;
        this.audit = audit;
        this.checker = checker;
        this.scope = scope;
    }

    @GetMapping
    public UiPage list() {
        ExtensionAudit found = audit.getIfAvailable(ExtensionAudit::clean);
        List<ExtensionService.Status> statuses = extensions.list();
        ContributionChecker check = checker.getIfAvailable();
        Map<String, ContributionChecker.Report> reports = check == null ? Map.of()
                : check.checkAll(statuses.stream().map(ExtensionService.Status::manifest).toList());
        return new ExtensionsPage(statuses, extensions.registry().problems(), found.unmanaged(), reports,
                inBrandNamespace()).render();
    }

    /** Whether the request works in a brand's own namespace — the one whose id is the brand. */
    private boolean inBrandNamespace() {
        ScopeSupplier current = scope.getIfAvailable();
        Optional<String> brand = extensions.currentBrand();
        return current != null && brand.isPresent() && brand.get().equals(current.namespace().value());
    }

    @PostMapping("/{id}/enable")
    public UiPage enable(@PathVariable("id") String id) {
        return decide(id, true);
    }

    @PostMapping("/{id}/disable")
    public UiPage disable(@PathVariable("id") String id) {
        return decide(id, false);
    }

    /** Forgets the namespace's decision: the extension is back to the brand's, or the manifest's. */
    @PostMapping("/{id}/reset")
    public UiPage reset(@PathVariable("id") String id) {
        return attempt(id, "Reset", "Not reset", extensionId -> {
            extensions.reset(extensionId);
            return "'" + name(extensionId) + "' is back to its default here.";
        });
    }

    @PostMapping("/{id}/brand/enable")
    public UiPage enableForBrand(@PathVariable("id") String id) {
        return decideForBrand(id, true);
    }

    @PostMapping("/{id}/brand/disable")
    public UiPage disableForBrand(@PathVariable("id") String id) {
        return decideForBrand(id, false);
    }

    @PostMapping("/{id}/brand/lock")
    public UiPage lockForBrand(@PathVariable("id") String id) {
        return lock(id, true);
    }

    @PostMapping("/{id}/brand/unlock")
    public UiPage unlockForBrand(@PathVariable("id") String id) {
        return lock(id, false);
    }

    @PostMapping("/{id}/brand/reset")
    public UiPage resetForBrand(@PathVariable("id") String id) {
        return brandAttempt(id, "Reset", "Not reset", extensionId -> {
            extensions.resetBrand(extensionId);
            return "'" + name(extensionId) + "' is back to its default for the brand.";
        });
    }

    private UiPage decide(String id, boolean enabled) {
        return attempt(id, "Changed", "Not changed", extensionId -> {
            UserId me = me();
            if (enabled) extensions.enable(extensionId, me);
            else extensions.disable(extensionId, me);
            return "'" + name(extensionId) + "' is now " + (enabled ? "on" : "off") + " in this namespace.";
        });
    }

    private UiPage decideForBrand(String id, boolean enabled) {
        return brandAttempt(id, "Changed", "Not changed", extensionId -> {
            boolean locked = extensions.find(extensionId)
                    .flatMap(ExtensionService.Status::brandDecision).map(d -> d.locked()).orElse(false);
            extensions.decideForBrand(extensionId, enabled, locked, me());
            return "'" + name(extensionId) + "' is now " + (enabled ? "on" : "off") + " for the brand "
                    + extensions.currentBrand().orElse("") + ".";
        });
    }

    private UiPage lock(String id, boolean locked) {
        return brandAttempt(id, locked ? "Locked" : "Unlocked", "Not changed", extensionId -> {
            extensions.lockForBrand(extensionId, locked, me());
            return locked
                    ? "'" + name(extensionId) + "' is locked for the brand: its namespaces cannot override."
                    : "'" + name(extensionId) + "' is unlocked: the brand's namespaces may decide for themselves.";
        });
    }

    private UiPage brandAttempt(String id, String okTitle, String failTitle, Act act) {
        if (!inBrandNamespace()) {
            return list().toast(UiToast.error("Deciding for a brand is done from the brand's own namespace.").title(failTitle));
        }
        return attempt(id, okTitle, failTitle, act);
    }

    private UiPage attempt(String id, String okTitle, String failTitle, Act act) {
        try {
            String message = act.apply(ExtensionId.of(id));
            return list().toast(UiToast.success(message).title(okTitle));
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            return list().toast(UiToast.error(e.getMessage()).title(failTitle));
        }
    }

    @FunctionalInterface
    private interface Act {
        String apply(ExtensionId id);
    }

    private static UserId me() {
        return SecurityCurrentUserResolver.userIdOf(SecurityContextHolder.getContext().getAuthentication()).orElse(null);
    }

    private String name(ExtensionId id) {
        return extensions.find(id).map(status -> status.manifest().name()).orElse(id.value());
    }
}
