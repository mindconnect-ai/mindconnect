package ai.mindconnect.scope;

/**
 * In which namespace, and for whom, the current call works. A store asks it
 * on every call instead of being built for one namespace, so one store serves
 * all of them.
 *
 * <p>A host that serves several namespaces answers per call, from where the
 * call came in; an embedding with one namespace takes {@link #fixed}.
 */
@FunctionalInterface
public interface TenantScope {

    Tenant tenant();

    /** Always the same tenant. */
    static TenantScope fixed(Tenant tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("A fixed scope needs its tenant");
        }
        return () -> tenant;
    }

    /** Always this namespace, for nobody in particular. */
    static TenantScope fixed(String namespace) {
        return fixed(Tenant.of(namespace));
    }

    /** The one namespace of an installation that has only one. */
    static TenantScope local() {
        return fixed(Tenant.LOCAL);
    }
}
