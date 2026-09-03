package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.BuildInfo;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The About dialog behind the header's version label: what build this is
 * and what it changes. Opened and closed as patches on the body-level dialog
 * host, the same way the tool-test dialog works — the page underneath is
 * left alone.
 */
@RestController
@RequestMapping("/admin/api/about")
public class AboutUiController {

    static final String DIALOG_ID = "about-dialog";

    private final BuildInfo info;

    public AboutUiController(BuildInfo info) {
        this.info = info;
    }

    @GetMapping
    public UiPatch open() {
        UiDialog dialog = UiDialog.of(info.isKnown() ? "Version " + info.version() : "About", null, body());
        dialog.setId(DIALOG_ID);
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(DIALOG_ID))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }

    @PostMapping("/close")
    public UiPatch close() {
        return UiPatch.of().patch(UiPatch.Operation.remove(DIALOG_ID));
    }

    private UiStack body() {
        var build = UiDetail.of("about-build", "This server");
        if (info.isKnown()) {
            build.field(UiField.text("version", "Version", info.version()));
            if (info.builtAt() != null) build.field(UiField.text("built", "Built", info.builtAt()));
            if (info.commit() != null) build.field(UiField.text("commit", "Commit", info.commit()));
            if (info.branch() != null) build.field(UiField.text("branch", "Branch", info.branch()));
        } else {
            build.field(UiField.text("version", "Version",
                    "unknown — not a packaged build (started from the IDE?)"));
        }
        var stack = UiStack.of("about-stack").child(build);
        String section = info.changelogSection();
        stack.child(section != null
                ? UiMarkdown.of("about-changelog", section)
                : UiText.of("This build carries no changelog."));
        // Close at the very end, after the changelog — the natural exit once
        // one has read down to it; the dialog's own X covers the impatient.
        stack.child(UiAction.secondary("about-close", "Close").icon("close")
                .dispatch("POST", "/admin/api/about/close"));
        return stack;
    }
}
