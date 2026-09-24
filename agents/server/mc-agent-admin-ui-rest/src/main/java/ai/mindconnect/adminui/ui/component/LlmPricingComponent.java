package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Pricing" section under an LLM config: its price periods as a small
 * table, each with Edit and Remove, and "Add price…" above it. Its own form
 * and its own endpoints, because a price is an entity of its own and not a
 * field of the config.
 *
 * <p>An alias has no prices — its calls are served, and paid for, by the
 * config it points at — so its section is one sentence saying which.
 */
public final class LlmPricingComponent {

    /** The whole section — what a change replaces in place. */
    public static final String ID = "llm-pricing";
    public static final String TABLE_ID = ID + "-table";
    public static final String FORM_ID = "llm-price-form";
    public static final String DIALOG_ID = "llm-price-dialog";

    private LlmPricingComponent() {
    }

    /** Where a config's prices are answered: {@code /admin/api/llm-configs/<id>/prices}. */
    public static String api(LlmConfig config) {
        return "/admin/api/llm-configs/" + config.id().value() + "/prices";
    }

    /** The section for {@code config}, its periods oldest first; {@code today} marks the current one. */
    public static UiNode render(LlmConfig config, List<LlmPrice> prices, LocalDate today) {
        UiStack stack = UiStack.of(ID).gap(8);
        if (config.isAlias()) {
            stack.child(hint(ID + "-alias", "Priced by " + config.delegatesTo()
                    + " — an alias has no prices of its own; its calls cost what the config it points at costs."));
            return stack;
        }
        stack.child(table(config, prices, today));
        stack.child(hint(ID + "-note", "Rates per million tokens. A period runs from its first day up to, "
                + "not including, its end (UTC); leave the end empty while the price holds. Usage reports "
                + "price every call with the rate valid on its day, so a correction here applies to past calls too."));
        return stack;
    }

    public static UiTable table(LlmConfig config, List<LlmPrice> prices, LocalDate today) {
        String api = api(config);
        UiTable table = UiTable.of(TABLE_ID, "Pricing").stackOnMobile(true).icon("coins")
                .column(UiTable.Column.text("validFrom", "Valid from"))
                .column(UiTable.Column.text("validTo", "Valid to"))
                .column(UiTable.Column.text("currency", "Currency"))
                .column(UiTable.Column.text("input", "Input / 1M"))
                .column(UiTable.Column.text("cachedInput", "Cached input / 1M"))
                .column(UiTable.Column.text("output", "Output / 1M"))
                .column(UiTable.Column.text("status", "Status"))
                .rowAction(UiAction.secondary("edit", "Edit").icon("edit")
                        .dispatch("GET", api + "/{id}/edit"))
                .rowAction(UiAction.danger("remove", "Remove").icon("delete")
                        .confirm("Remove this price period? Calls in it are then shown without a price.")
                        .dispatch("DELETE", api + "/{id}"))
                .action(UiAction.primary(ID + "-add", "Add price…").icon("add")
                        .dispatch("GET", api + "/new"));
        for (LlmPrice price : prices) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", price.id().value());
            row.put("validFrom", price.validFrom().toString());
            row.put("validTo", price.validTo() == null ? "open" : price.validTo().toString());
            row.put("currency", price.currency());
            row.put("input", rate(price.inputPerMillion()));
            row.put("cachedInput", price.cachedInputPerMillion() == null
                    ? "as input" : rate(price.cachedInputPerMillion()));
            row.put("output", rate(price.outputPerMillion()));
            row.put("status", status(price, today));
            table.row(row);
        }
        return table;
    }

    private static String status(LlmPrice price, LocalDate today) {
        if (price.isValidOn(today)) return "current";
        return price.validFrom().isAfter(today) ? "upcoming" : "past";
    }

    /** What the form shows: the stored period, or what was typed when a save was refused. */
    public record Draft(String validFrom, String validTo, String currency, String input, String output,
                        String cachedInput) {

        public static Draft empty(LocalDate today) {
            return new Draft(today.toString(), null, LlmPrice.DEFAULT_CURRENCY, null, null, null);
        }

        public static Draft of(LlmPrice price) {
            return new Draft(price.validFrom().toString(),
                    price.validTo() == null ? null : price.validTo().toString(),
                    price.currency(), rate(price.inputPerMillion()), rate(price.outputPerMillion()),
                    price.cachedInputPerMillion() == null ? null : rate(price.cachedInputPerMillion()));
        }
    }

    /**
     * The add/edit dialog's form.
     *
     * @param target where Save posts to — the collection for a new period, the period itself for an edit
     * @param error  why the last save was refused, or null
     */
    public static UiForm form(LlmConfig config, Draft draft, String target, boolean isNew, String error) {
        UiForm form = UiForm.of(FORM_ID, null)
                .field(UiField.date("validFrom", "Valid from", draft.validFrom()).asEditable().asRequired()
                        .hint("The first day (UTC) this price applies."))
                .field(UiField.date("validTo", "Valid to", draft.validTo()).asEditable()
                        .hint("The first day it no longer applies. Empty: until further notice."))
                .field(UiField.text("currency", "Currency", draft.currency()).asEditable().asRequired()
                        .placeholder(LlmPrice.DEFAULT_CURRENCY)
                        .hint("Three-letter ISO code. Reports sum each currency on its own."))
                .field(UiField.text("inputPerMillion", "Input per 1M tokens", draft.input())
                        .asEditable().asRequired().placeholder("3.00"))
                .field(UiField.text("cachedInputPerMillion", "Cached input per 1M tokens", draft.cachedInput())
                        .asEditable().placeholder("0.30")
                        .hint("Input the provider read from its prompt cache. Empty: priced as input."))
                .field(UiField.text("outputPerMillion", "Output per 1M tokens", draft.output())
                        .asEditable().asRequired().placeholder("15.00"))
                .action(UiAction.primary(FORM_ID + "-save", isNew ? "Add" : "Save").icon("check")
                        .dispatch("POST", target, FORM_ID))
                .action(UiAction.secondary(FORM_ID + "-cancel", "Cancel")
                        .dispatch("POST", "/admin/api/llm-configs/prices/dialog/close"));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    /** A rate as typed: no trailing zeros, never in exponent notation. */
    public static String rate(BigDecimal value) {
        if (value == null) return null;
        BigDecimal plain = value.stripTrailingZeros();
        return (plain.scale() < 0 ? plain.setScale(0) : plain).toPlainString();
    }

    private static UiText hint(String id, String text) {
        UiText node = UiText.of(id, text);
        node.withCssClass("sui-hint");
        return node;
    }
}
