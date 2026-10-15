package ai.mindconnect.adminui.branding;

import ai.mindconnect.agent.Email;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The namespace a brand works in, and who shapes it.
 *
 * <p>A brand that carries this block owns a namespace: whoever comes in under
 * one of its hosts works there, and the addresses named here are its admins —
 * from configuration, rather than from whoever happened to arrive first. The
 * <em>first</em> admin is its creator, the one who may delete it, which is why
 * this is a list and not a set.
 *
 * <pre>{@code
 * mindconnect:
 *   branding:
 *     switch:
 *       acme:
 *         url-pattern: acme.example.com
 *         title: ACME AI
 *         namespace:
 *           admins: [chief@acme.example, david@acme.example]
 * }</pre>
 *
 * <p>{@code creator: <address>} is the short form of a single admin. The
 * namespace's id is the {@code switch} entry's own name ({@code acme} above)
 * unless {@code id} says otherwise, and its display name is the brand's
 * {@code title} — a brand and its namespace should not need two names.
 *
 * <p>Nothing here is inherited from the top-level branding: an installation
 * that names one brand's namespace does not give every other host one.
 */
public class BrandingNamespace {

    /** The namespace id; unset it is the {@code switch} entry's name. */
    private String id;

    /** The addresses that shape it, the first one its creator. */
    private List<String> admins = new ArrayList<>();

    /** The short form of one admin — the same setting as a single-entry {@link #admins}. */
    private String creator;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getAdmins() {
        return admins;
    }

    public void setAdmins(List<String> admins) {
        this.admins = admins == null ? new ArrayList<>() : admins;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    /**
     * The admins as addresses, in the order they were written, the creator
     * first: {@code creator} ahead of {@code admins}, duplicates and blanks
     * dropped.
     */
    public List<Email> addresses() {
        Set<Email> out = new LinkedHashSet<>();
        Email first = Email.parse(creator).orElse(null);
        if (first != null) out.add(first);
        for (String admin : admins) {
            Email.parse(admin).ifPresent(out::add);
        }
        return List.copyOf(out);
    }
}
