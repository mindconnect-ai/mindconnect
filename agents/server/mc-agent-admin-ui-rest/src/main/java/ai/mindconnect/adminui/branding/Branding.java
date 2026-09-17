package ai.mindconnect.adminui.branding;

import ai.mindconnect.agent.Email;

import java.util.List;

/**
 * One request's branding: what {@link BrandingProperties} resolves to once the
 * host has picked a {@code switch} entry, its unset values have fallen back to
 * the top-level settings, and those to what the app ships.
 *
 * <p>Everything downstream — the header, the shell, the picker — reads this
 * and not the configuration, so nothing else has to know that branding can
 * differ per request.
 *
 * @param logo     the mark beside the heading, or null for none
 * @param favicon  the tab icon, or null
 * @param pickerDisabled whether the header offers no theme picker at all
 * @param pickerThemes   the themes it offers; empty means every theme the app ships
 * @param namespace       the namespace this brand works in, or null when it has none —
 *                        the {@code switch} entry's own name unless it says otherwise
 * @param namespaceAdmins who shapes that namespace, the creator first; empty without one
 */
public record Branding(String title, String documentTitle, String logo, String logoHref,
                       String favicon, String theme, List<String> stylesheets,
                       boolean pickerDisabled, List<String> pickerThemes,
                       String namespace, List<Email> namespaceAdmins) {

    public Branding {
        stylesheets = stylesheets == null ? List.of() : List.copyOf(stylesheets);
        pickerThemes = pickerThemes == null ? List.of() : List.copyOf(pickerThemes);
        namespaceAdmins = namespaceAdmins == null ? List.of() : List.copyOf(namespaceAdmins);
    }

    /** Whether this brand brings a namespace of its own that somebody shapes. */
    public boolean hasNamespace() {
        return namespace != null && !namespaceAdmins.isEmpty();
    }

    /** The value of {@code off} in the picker attribute: no picker on this host. */
    public static final String PICKER_OFF = "off";

    /**
     * What the shell writes into {@code <html data-theme-picker>}: {@code off},
     * a space-separated list of theme ids, or the empty string for "all of
     * them", which is what the picker assumes when the attribute is missing.
     */
    public String pickerAttribute() {
        if (pickerDisabled) return PICKER_OFF;
        return String.join(" ", pickerThemes);
    }
}
