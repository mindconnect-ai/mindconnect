package ai.mindconnect.llm.management.api.port.out;

/**
 * The namespace the current call works in. Every repository adapter asks it on
 * every call instead of being built for one namespace, so one repository serves
 * all of them; ports, services and domain objects carry no namespace.
 *
 * <p>A host that serves several namespaces answers per call, from where the
 * call came in; an embedding with one namespace takes {@link #fixed}.
 */
@FunctionalInterface
public interface LlmScopeSupplier {

    /** The namespace of the current call. Never blank. */
    String namespace();

    /** Always the same namespace: the library case. */
    static LlmScopeSupplier fixed(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("A fixed scope needs its namespace");
        }
        return () -> namespace;
    }

    /** The one namespace of an installation that has only one. */
    static LlmScopeSupplier local() {
        return fixed("local");
    }
}
