package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.vectorstore.embedding.EntityRef;

import java.util.Map;

/**
 * Picks entries of a store by what a caller knows of them: the id, and as much
 * of type, source and container as it takes to tell them apart — a file needs
 * only its id, a mail may need its folder as well. What a search hit prints
 * ({@link #describe}) is enough to select it again.
 *
 * @param id        the entity's id (required)
 * @param type      {@code file}, {@code document}, {@code mail}, …; null for any
 * @param source    the account or store it comes from; null for any
 * @param container the folder, calendar or list it lies in; null for any
 */
public record EntitySelector(String id, String type, String source, String container) {

    public EntitySelector {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An entity selector needs an id");
        }
    }

    public static EntitySelector id(String id) {
        return new EntitySelector(id, null, null, null);
    }

    /** From a tool argument: an object with {@code id} and optionally the rest, or a bare id. */
    static EntitySelector of(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return new EntitySelector(str(map.get("id")), str(map.get("type")), str(map.get("source")),
                    str(map.get("container")));
        }
        return id(String.valueOf(raw));
    }

    public boolean matches(EntityRef ref) {
        return id.equals(ref.id())
                && (type == null || type.equals(ref.type().value()))
                && (source == null || source.equals(ref.source()))
                && (container == null || container.equals(ref.container()));
    }

    /** How a hit names its entity — {@code file file-1}, {@code mail email.freemail/INBOX 7-1}. */
    public static String describe(EntityRef ref) {
        String where = ref.type().value().equals("file") || ref.type().value().equals("document") ? ""
                : ref.source() + (ref.container().isEmpty() ? "" : "/" + ref.container()) + " ";
        return ref.type().value() + " " + where + ref.id();
    }

    private static String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
