package ai.mindconnect.chatui.ui;

import ai.mindconnect.ui.model.UiNode;

/**
 * A reusable UI building block that knows both its DOM id and how to
 * render itself as a {@link UiNode}.
 *
 * <p>Components are <b>app-specific</b> (they bind a domain model to a
 * UI tree) and intentionally <b>stateless</b>: the model goes in via
 * the constructor, every method returns a fresh node or patch
 * operation. The same component instance can therefore be used to
 * render a full page (via {@link #render()}) <i>and</i> to produce
 * patch operations (via component-level helpers like
 * {@code MessageListComponent.appendUserMessage(...)}) without ever
 * mutating its own state.
 *
 * <p>The {@link #id()} contract is important: patches target elements
 * by id, so every component must surface the same id it uses when
 * rendering its root node. Pages compose components and routinely
 * use their ids as patch targets.
 */
public interface UiComponent {

    /**
     * The DOM id this component renders with. Patch operations targeting
     * this component (REPLACE / CLEAR / REMOVE) use this id, so it must
     * match the id on the root {@link UiNode} returned by {@link #render()}.
     */
    String id();

    /**
     * Full render — emit the component as a {@link UiNode}. Used both
     * inside a page's full render and as the payload of a REPLACE patch.
     */
    UiNode render();
}
