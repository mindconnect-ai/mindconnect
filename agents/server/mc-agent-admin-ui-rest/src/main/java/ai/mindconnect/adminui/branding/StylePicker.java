package ai.mindconnect.adminui.branding;

import java.util.ArrayList;
import java.util.List;

/**
 * What the header's theme picker offers, if anything.
 *
 * <p>Both values are "unset means inherit": a {@code switch} entry that says
 * nothing about the picker gets what the top-level branding says, and that in
 * turn defaults to a picker with every shipped theme in it.
 *
 * <pre>{@code
 * style-picker:
 *   disabled: true          # no picker at all
 *   themes: amethyst, dark  # or: only these, in this order
 * }</pre>
 */
public class StylePicker {

    /** null means "inherit"; the resolved default is false, i.e. the picker is there. */
    private Boolean disabled;

    /** Empty means "inherit", and unset all the way down means every theme the app ships. */
    private List<String> themes = new ArrayList<>();

    public Boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(Boolean disabled) {
        this.disabled = disabled;
    }

    public List<String> getThemes() {
        return themes;
    }

    public void setThemes(List<String> themes) {
        this.themes = themes == null ? new ArrayList<>() : themes;
    }

    /** The named themes, blanks dropped — a comma-separated line easily carries one. */
    public List<String> themeIds() {
        return themes.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).toList();
    }
}
