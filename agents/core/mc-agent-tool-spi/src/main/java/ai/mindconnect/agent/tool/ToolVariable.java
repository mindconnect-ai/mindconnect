package ai.mindconnect.agent.tool;

import ai.mindconnect.common.env.EnvVarResolver;

import java.util.Objects;

/**
 * A variable a tool source needs somebody to fill in — the IMAP host of the
 * mailbox a mail tool reads, the account it signs in as, its password.
 *
 * <p>A {@link ToolFactory} or {@link MultiToolProvider} <em>declares</em> these;
 * it does not read them here. At call time the value comes from the
 * {@link EnvVarResolver} chain as every other {@code ${VAR}} does — the
 * user's own first, then the namespace's, then the process's — so a
 * declaration says <em>what</em> a tool needs, never <em>whose</em> value it
 * gets. A host that puts the value in the process environment for everyone
 * has nothing to fill in and sees no request.
 *
 * <p>What a declaration is for: a user signing in to a shared installation
 * cannot know that {@code MC_EMAIL_HOST} is the name the mail tool looks up.
 * Declared, the name is listed on their profile with a place to put the value,
 * a missing required one becomes a notice on their next sign-in, and one with
 * a {@link #defaultValue()} is written for them so the common case needs no
 * typing at all.
 *
 * <p><b>{@link #secret()} is about the value, not the name.</b> A secret is
 * masked in every form and never shown again once stored, which is what an
 * encrypting repository does with it anyway; a host, a port or a folder is
 * plain text and shown, because a user correcting a typo has to see it.
 *
 * @param name         the variable's name, as a {@code ${VAR}} refers to it
 * @param title        a short label for a form — "IMAP host"; never null after construction
 * @param description  what to put there, for the hint under the field; null when the title says it
 * @param required     true when the tool cannot work without it. A required
 *                     variable nobody has set is what a sign-in notice is about;
 *                     an optional one is only ever an offer
 * @param secret       true for a value that is masked on entry and never shown again
 * @param defaultValue what to write for a user who has none — {@code "993"} for an
 *                     IMAPS port. Null means there is no sensible default, which is
 *                     the case for every secret
 * @param declaredBy   the tool source that declared it, as the registry stamps it
 *                     ({@code ToolFactory.name()} / {@code MultiToolProvider.group()});
 *                     null on a declaration that has not passed a registry yet
 */
public record ToolVariable(
        String name,
        String title,
        String description,
        boolean required,
        boolean secret,
        String defaultValue,
        String declaredBy
) {

    public ToolVariable {
        Objects.requireNonNull(name, "A tool variable needs a name");
        if (!EnvVarResolver.isValidName(name)) {
            throw new IllegalArgumentException(
                    "'" + name + "' is not a variable name: letters, digits and '_', not starting with a digit");
        }
        if (title == null || title.isBlank()) {
            title = name;
        }
        if (defaultValue != null && defaultValue.isBlank()) {
            defaultValue = null;          // blank is not a value; see EnvVarResolver.requireValid
        }
    }

    /** A variable the tool cannot work without, in plain text. */
    public static ToolVariable required(String name, String title, String description) {
        return new ToolVariable(name, title, description, true, false, null, null);
    }

    /** A variable the tool cannot work without, whose value is a secret. */
    public static ToolVariable secret(String name, String title, String description) {
        return new ToolVariable(name, title, description, true, true, null, null);
    }

    /** A secret the tool can do without — a second password for the case where one account is two. */
    public static ToolVariable optionalSecret(String name, String title, String description) {
        return new ToolVariable(name, title, description, false, true, null, null);
    }

    /** A variable with a sensible default — written for a user who has none, so nobody types it. */
    public static ToolVariable withDefault(String name, String title, String description, String defaultValue) {
        return new ToolVariable(name, title, description, false, false, defaultValue, null);
    }

    /** A variable the tool works without: an offer, never a notice. */
    public static ToolVariable optional(String name, String title, String description) {
        return new ToolVariable(name, title, description, false, false, null, null);
    }

    /** This declaration as it comes from {@code source} — what the registry stamps on it. */
    public ToolVariable declaredBy(String source) {
        return new ToolVariable(name, title, description, required, secret, defaultValue, source);
    }

    /** True when a user who has none of this variable can be given one without asking. */
    public boolean hasDefault() {
        return defaultValue != null;
    }
}
