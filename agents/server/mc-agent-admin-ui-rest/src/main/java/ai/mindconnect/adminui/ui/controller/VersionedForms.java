package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;

/**
 * Optimistic locking in the admin forms. An edit form carries the version it was
 * opened with in a hidden {@code version} field; the save passes it on, and a
 * store that finds another version refuses it.
 *
 * <p>The refusal is answered with a toast patch, not with an error status: the
 * client shows only a generic message for a non-2xx answer, and a patch leaves
 * the form — and what the user typed into it — on screen.
 */
class VersionedForms {

    static final String FIELD = "version";

    private VersionedForms() {
    }

    /** The version the form was opened with, or {@code null} when it carries none. */
    static Long version(FormBody body) {
        String raw = body.str(FIELD);
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * What the save answers to a name the runtime could not work with — a
     * skill's, which the model has to be able to type. Like the version
     * refusal, a patch rather than a status: the form and what the user
     * typed stay on screen.
     */
    static UiPatch unusableName(String what, String name) {
        return UiPatch.of().toast(UiToast.error("'" + name + "' cannot be used as a name here: use "
                + "lower-case letters, digits and dashes, starting with a letter or digit.")
                .title(what + " not saved"));
    }

    /**
     * What the save answers to a name another stored entry already has — a
     * skill's, which the runtime looks up by name, so two of them would leave
     * one silently unreachable.
     */
    static UiPatch nameTaken(String what, String name) {
        return UiPatch.of().toast(UiToast.error("There is already a " + what.toLowerCase(java.util.Locale.ROOT)
                        + " named '" + name + "'. Choose another name, or edit that one.")
                .title(what + " not saved"));
    }

    /** What a save answers on a host that wires no store for what it is saving. */
    static UiPatch noStore() {
        return UiPatch.of().toast(UiToast.error("This installation stores no skills. A project or a "
                        + "user can still keep them as SKILL.md files, and agents read those.")
                .title("Not saved"));
    }

    /** What the save answers when {@code what} (e.g. "Agent 'scout'") was saved by someone else first. */
    static UiPatch changedMeanwhile(String what) {
        return UiPatch.of().toast(UiToast.error(what + " was changed by someone else in the meantime — "
                + "your changes were not saved. Reload to see the current version.").title("Not saved"));
    }
}
