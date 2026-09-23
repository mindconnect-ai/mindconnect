package ai.mindconnect.extension.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

/**
 * What an extension says about itself — the one contract between it and the
 * host. For a jar it is {@code META-INF/mindconnect/extension.json} inside
 * the jar; the host reads every one of them at start, before any
 * {@code ServiceLoader} runs, and wires only what a manifest declares.
 *
 * <p>Declared, not discovered: what is not in the manifest the host does not
 * wire, and the Extensions screen can tell an admin what an extension brings
 * without executing any of it. Fields the host does not know are ignored, so
 * a manifest written for a newer host still loads on an older one; what the
 * older host cannot honour it simply does not see.
 *
 * <p>The shape follows concept 43. Only the parts step 0 acts on are typed
 * here; the rest of {@code contributes} is kept as declared data for the
 * screen and the steps that follow.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtensionManifest(
        ExtensionId id,
        String name,
        String version,
        String description,
        Vendor vendor,
        ExtensionRuntime runtime,
        Requires requires,
        /** Whether a namespace has the extension on before an admin decided; true unless the manifest says otherwise. */
        Boolean enabledByDefault,
        List<String> permissions,
        Contributes contributes) {

    public ExtensionManifest {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) name = id.value();
        if (version == null || version.isBlank()) version = "0";
        vendor = vendor == null ? Vendor.NONE : vendor;
        runtime = runtime == null ? ExtensionRuntime.JAR : runtime;
        requires = requires == null ? Requires.NONE : requires;
        enabledByDefault = enabledByDefault == null || enabledByDefault;
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        contributes = contributes == null ? Contributes.NONE : contributes;
    }

    /** Whether a namespace that never decided has this extension on. */
    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    /** Who publishes the extension. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vendor(String id, String name, String homepage) {
        public static final Vendor NONE = new Vendor(null, null, null);

        /** What to print for the vendor: its name, else its id, else nothing. */
        public String label() {
            if (name != null && !name.isBlank()) return name;
            return id == null ? "" : id;
        }
    }

    /** What the extension needs from the host and from other extensions. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Requires(String mindconnect, String protocol, List<Dependency> extensions) {
        public static final Requires NONE = new Requires(null, null, List.of());

        public Requires {
            extensions = extensions == null ? List.of() : List.copyOf(extensions);
        }

        /** Another extension this one builds on; optional when it works without it. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Dependency(ExtensionId id, Boolean optional) {
            public Dependency {
                Objects.requireNonNull(id, "id");
                optional = optional != null && optional;
            }

            public boolean isOptional() {
                return optional;
            }
        }
    }

    /** Everything the extension brings, by extension point. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contributes(
            Tools tools,
            /** {@code RuntimeFeature} implementations, by class name. */
            List<String> features,
            Content content,
            Ui ui,
            /** Path patterns of the REST controllers the extension serves ({@code /admin/api/acme/**}). */
            List<String> rest,
            /** Ports the extension decorates ({@code FeatureContext.decorate}), by simple or qualified name. */
            List<String> decorates,
            /** Beans the extension replaces outright, by simple or qualified name — one extension per seam. */
            List<String> replaces,
            Persistence persistence) {

        public static final Contributes NONE = new Contributes(null, null, null, null, null, null, null, null);

        public Contributes {
            tools = tools == null ? Tools.NONE : tools;
            features = features == null ? List.of() : List.copyOf(features);
            content = content == null ? Content.NONE : content;
            ui = ui == null ? Ui.NONE : ui;
            rest = rest == null ? List.of() : List.copyOf(rest);
            decorates = decorates == null ? List.of() : List.copyOf(decorates);
            replaces = replaces == null ? List.of() : List.copyOf(replaces);
            persistence = persistence == null ? Persistence.NONE : persistence;
        }
    }

    /**
     * The tools an extension brings: the provider classes ({@code ToolFactory},
     * {@code MultiToolProvider}) the classpath finds, and the tool names they
     * carry as patterns ({@code acme_*}) — the names are what a namespace
     * that switched the extension off stops seeing.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tools(List<String> providers, List<String> names) {
        public static final Tools NONE = new Tools(List.of(), List.of());

        public Tools {
            providers = providers == null ? List.of() : List.copyOf(providers);
            names = names == null ? List.of() : List.copyOf(names);
        }

        public boolean isEmpty() {
            return providers.isEmpty() && names.isEmpty();
        }
    }

    /** Agents, skills and workflows the extension installs, by name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(List<String> agents, List<String> skills, List<String> workflows) {
        public static final Content NONE = new Content(List.of(), List.of(), List.of());

        public Content {
            agents = agents == null ? List.of() : List.copyOf(agents);
            skills = skills == null ? List.of() : List.copyOf(skills);
            workflows = workflows == null ? List.of() : List.copyOf(workflows);
        }

        public boolean isEmpty() {
            return agents.isEmpty() && skills.isEmpty() && workflows.isEmpty();
        }
    }

    /** What the extension puts into the admin UI. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ui(List<MenuEntry> menu, List<Route> routes, List<String> assets) {
        public static final Ui NONE = new Ui(List.of(), List.of(), List.of());

        public Ui {
            menu = menu == null ? List.of() : List.copyOf(menu);
            routes = routes == null ? List.of() : List.copyOf(routes);
            assets = assets == null ? List.of() : List.copyOf(assets);
        }

        public boolean isEmpty() {
            return menu.isEmpty() && routes.isEmpty() && assets.isEmpty();
        }

        /**
         * A sidebar entry the extension contributes. With {@code label} and
         * {@code href} the host renders it — into the group {@code group}
         * names (a shipped one like {@code nav-group-tools}, or a new one
         * called {@code groupLabel}), for admins of the namespace and, when
         * {@code roles} names {@code USER}, for its plain users too. Without
         * them it only declares the id of an entry the jar's own
         * {@code AdminMenuContribution} registers. Either way, an entry whose
         * id a switched-off extension declares is left out of the menu.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record MenuEntry(String id, String label, String href, String icon, String group, String groupLabel,
                                List<String> roles) {
            public MenuEntry {
                Objects.requireNonNull(id, "id");
                roles = roles == null ? List.of() : List.copyOf(roles);
            }

            /** Whether the host can render this entry itself. */
            public boolean isRenderable() {
                return label != null && !label.isBlank() && href != null && !href.isBlank();
            }

            /** Whether a plain user of the namespace (not an admin) sees it. */
            public boolean forUsers() {
                return roles.stream().anyMatch("USER"::equalsIgnoreCase);
            }
        }

        /**
         * A route the extension serves, and who may open it. The path is a
         * prefix pattern: {@code /admin/acme/**} covers {@code /admin/acme}
         * and everything under it.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Route(String path, List<String> roles) {
            public Route {
                Objects.requireNonNull(path, "path");
                roles = roles == null ? List.of() : List.copyOf(roles);
            }

            /** The path without its trailing {@code /**} or {@code /*}. */
            public String prefix() {
                String p = path;
                if (p.endsWith("/**")) p = p.substring(0, p.length() - 3);
                else if (p.endsWith("/*")) p = p.substring(0, p.length() - 2);
                return p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
            }

            /** Whether a request path falls under this route. */
            public boolean covers(String requestPath) {
                if (requestPath == null) return false;
                String prefix = prefix();
                return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
            }
        }
    }

    /** The extension's own storage: a schema of its own, never the core's tables. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Persistence(String schema, String migrations) {
        public static final Persistence NONE = new Persistence(null, null);

        public boolean isEmpty() {
            return schema == null && migrations == null;
        }
    }
}
