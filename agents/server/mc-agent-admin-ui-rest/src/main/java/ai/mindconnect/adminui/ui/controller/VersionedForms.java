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
final class VersionedForms {

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

    /** What the save answers when {@code what} (e.g. "Agent 'scout'") was saved by someone else first. */
    static UiPatch changedMeanwhile(String what) {
        return UiPatch.of().toast(UiToast.error(what + " was changed by someone else in the meantime — "
                + "your changes were not saved. Reload to see the current version.").title("Not saved"));
    }
}
