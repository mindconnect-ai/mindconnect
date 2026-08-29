package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiNode;

/**
 * How full the context window is: a compact bar plus a readout, for the
 * conversation header's extra slot.
 *
 * <p>Tints warning at 75% and error at 90% — the point where a long chat
 * starts losing its early turns to compression, which is worth seeing before
 * it happens rather than after.
 */
public final class TokenUsageComponent implements UiComponent {

    private final String idPrefix;
    private final WorkingMemory memory;

    public TokenUsageComponent(String idPrefix, WorkingMemory memory) {
        this.idPrefix = idPrefix;
        this.memory = memory;
    }

    @Override
    public String id() {
        return idPrefix + ":tok";
    }

    /** The bar, or {@code null} when there is nothing to show. */
    @Override
    public UiNode render() {
        return tokenUsageBar(memory);
    }

    /** The readout on its own, for a title suffix. */
    public String suffix() {
        return tokenUsageSuffix(memory);
    }

    /**
     * Context-window usage as a compact progress bar + readout for the
     * header's extra slot. Tints warning at 75%%, error at 90%%. Null (no
     * bar) when there is no memory snapshot.
     */
    private ai.mindconnect.ui.model.UiNode tokenUsageBar(WorkingMemory memory) {
        if (memory == null) return null;
        int used = memory.totalTokens();
        Integer max = memory.contextWindowTokens();
        var label = ai.mindconnect.ui.model.UiText
                .of(idPrefix + ":tok-label", tokenUsageSuffix(memory).replace("  —  ", ""))
                .withCssClass("chat-token-text");
        if (max == null || max <= 0) {
            return used > 0 ? label : null;
        }
        double pct = 100.0 * used / max;
        var bar = ai.mindconnect.ui.model.UiProgress.of(used, max).showValue(false);
        bar.setId(idPrefix + ":tok-bar");
        if (pct >= 90) bar.status(ai.mindconnect.ui.model.UiProgress.Status.ERROR);
        else if (pct >= 75) bar.status(ai.mindconnect.ui.model.UiProgress.Status.WARNING);
        var wrap = ai.mindconnect.ui.model.UiStack.of(id());
        wrap.direction(ai.mindconnect.ui.model.UiStack.Direction.HORIZONTAL);
        wrap.gap(8);
        wrap.withCssClass("chat-token-usage");
        wrap.child(bar).child(label);
        return wrap;
    }

    /** " — 1,234 / 200,000 tok (0.6%)" or empty when no memory snapshot. */
    private String tokenUsageSuffix(WorkingMemory memory) {
        if (memory == null) return "";
        int used = memory.totalTokens();
        Integer max = memory.contextWindowTokens();
        if (max == null || max <= 0) {
            return used > 0 ? String.format("  —  %,d tok", used) : "";
        }
        double pct = 100.0 * used / max;
        return String.format("  —  %,d / %,d tok (%.1f%%)", used, max, pct);
    }}
