package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolVariable;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the installed tools want from one user, and what of it they already
 * have.
 *
 * <p>A tool source declares its variables ({@link ToolVariable}); this is the
 * one place that reads those declarations against a person. Three answers per
 * declaration:
 *
 * <ul>
 *   <li><b>They have it</b> — a variable of their own with that name. Nothing to do.</li>
 *   <li><b>Somebody else has it</b> — the namespace they work in, or the process.
 *       A shared installation whose operator put the value in the environment for
 *       everyone has nothing to ask of anybody, and asking anyway would be noise.
 *       Only the {@linkplain EnvVarResolver#shared() shared} sources count here:
 *       the personal one is what we are deciding about.</li>
 *   <li><b>Nobody has it</b> — the variable is {@link #provision provisioned} when
 *       it has a default, and is otherwise {@link #missing missing}: the user has
 *       to type it.</li>
 * </ul>
 *
 * <p>A variable is never created empty. A blank value is not a value
 * ({@link EnvVarResolver#requireValid}), and worse, a user variable that
 * existed-but-was-empty would <em>answer</em> the lookup and cut off the
 * namespace and the process behind it. So what can be filled in is filled in,
 * and what cannot is named — in the profile's list and, when it is required,
 * in a notice.
 */
@Service
public class ToolVariables {

    private static final Logger log = LoggerFactory.getLogger(ToolVariables.class);

    private final ObjectProvider<ToolRegistry> tools;
    private final UserService users;
    private final ObjectProvider<EnvVarResolver> environment;

    public ToolVariables(ObjectProvider<ToolRegistry> tools, UserService users,
                         ObjectProvider<EnvVarResolver> environment) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.users = Objects.requireNonNull(users, "users");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** Every variable the installed tools declare, in the registry's order. */
    public List<ToolVariable> declared() {
        ToolRegistry registry = tools.getIfAvailable();
        return registry == null ? List.of() : registry.declaredVariables();
    }

    /**
     * Writes the declared variables that have a default and that nobody has a
     * value for yet, so the common case needs no typing. A user who set the
     * variable themselves — or whose namespace did — is left alone: a default
     * must never overwrite a decision.
     *
     * @return the variables actually written
     */
    public List<ToolVariable> provision(UserId user) {
        List<ToolVariable> written = new ArrayList<>();
        Map<String, String> own = users.environment(user);
        for (ToolVariable variable : declared()) {
            if (!variable.hasDefault() || own.containsKey(variable.name()) || resolvesElsewhere(variable.name())) {
                continue;
            }
            try {
                users.putVariable(user, variable.name(), variable.defaultValue());
                written.add(variable);
            } catch (RuntimeException e) {
                log.warn("Could not create '{}' for {}: {}", variable.name(), user.value(), e.getMessage());
            }
        }
        if (!written.isEmpty()) {
            log.info("Created {} tool variable(s) for {}: {}", written.size(), user.value(),
                    written.stream().map(ToolVariable::name).toList());
        }
        return written;
    }

    /**
     * The declared variables {@code user} has no value for — neither their own
     * nor one from the namespace or the process. In declaration order, so the
     * required ones of a source stay together as they were written.
     */
    public List<ToolVariable> missing(UserId user) {
        Map<String, String> own = users.environment(user);
        return declared().stream()
                .filter(variable -> !own.containsKey(variable.name()))
                .filter(variable -> !resolvesElsewhere(variable.name()))
                .toList();
    }

    /** {@link #missing} narrowed to the ones a tool cannot work without — what a notice is about. */
    public List<ToolVariable> missingRequired(UserId user) {
        return missing(user).stream().filter(ToolVariable::required).toList();
    }

    /** True when the user has a variable of that name of their own. */
    public boolean isSetByUser(UserId user, String name) {
        return users.environment(user).containsKey(name);
    }

    /**
     * Whether the namespace or the process answers this name. Reads the
     * {@linkplain EnvVarResolver#shared() shared} chain — the user's own
     * values are deliberately not consulted, because whether they have one is
     * the question, not the answer.
     */
    public boolean resolvesElsewhere(String name) {
        EnvVarResolver resolver = environment.getIfAvailable();
        if (resolver == null) return false;
        try {
            return resolver.shared().get(name).filter(value -> !value.isBlank()).isPresent();
        } catch (RuntimeException e) {
            // A source that cannot answer right now (no scope bound, a store
            // down) must not turn into "the user has to configure this".
            log.debug("Could not look up '{}': {}", name, e.getMessage());
            return false;
        }
    }
}
