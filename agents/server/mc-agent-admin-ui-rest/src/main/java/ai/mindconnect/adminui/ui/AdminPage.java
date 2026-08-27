package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;

/**
 * Base class for full Admin-UI pages composed of {@link UiComponent}s.
 *
 * <p>A page binds a domain model to a tree of components and exposes
 * two kinds of outputs:
 * <ul>
 *   <li>{@link #render()} — full page, returned on the initial GET or
 *       after navigation. Composes child components into a
 *       {@link UiPage} that the front-end renders into its root.</li>
 *   <li>Per-event {@code UiPatch} methods (e.g. {@code chatTurnComplete()},
 *       {@code streamToken(...)}) — incremental updates that target
 *       individual component ids without re-shipping the whole page.</li>
 * </ul>
 *
 * <p>The {@link #patch(UiPatch.Operation...)} helper exists so concrete
 * pages can bundle several component-level operations into a single
 * {@link UiPatch} without boilerplate. Components themselves only
 * return single {@link UiPatch.Operation} values from their helper
 * methods — the page is the unit of composition.
 */
public abstract class AdminPage {

    /** Render the full page (initial GET, after navigation). */
    public abstract UiPage render();

    /**
     * Bundles one or more component-level operations into a {@link UiPatch}.
     * Concrete pages use this to compose patches from several components
     * (e.g. {@code messages.replaceAll()} + {@code chatForm.reset()}) in a
     * single SSE event.
     */
    protected final UiPatch patch(UiPatch.Operation... ops) {
        var p = UiPatch.of();
        for (var op : ops) p.patch(op);
        return p;
    }
}
