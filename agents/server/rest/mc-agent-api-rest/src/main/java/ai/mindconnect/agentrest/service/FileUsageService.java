package ai.mindconnect.agentrest.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agentrest.auth.VectorStoreAccess;
import ai.mindconnect.vectorstore.embedding.EntityType;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import ai.mindconnect.vectorstore.tools.VectorStoreRegistry;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Says where a user's stored files are used, so that nobody deletes a file a
 * chat or a knowledge base still lists without knowing it.
 */
@Service
public class FileUsageService {

    /**
     * Where files are used, by file id.
     *
     * @param chats  the caller's chats that have the file attached or indexed for them
     * @param stores the stores the caller can reach that list the file
     */
    public record FileUsage(Map<String, List<ManagedSession>> chats, Map<String, List<VectorStoreInstance>> stores) {

        public List<ManagedSession> chatsOf(String fileId) {
            return chats.getOrDefault(fileId, List.of());
        }

        public List<VectorStoreInstance> storesOf(String fileId) {
            return stores.getOrDefault(fileId, List.of());
        }
    }

    private final ObjectProvider<VectorStores> stores;
    private final VectorStoreAccess storeAccess;
    private final AgentRuntime runtime;

    public FileUsageService(ObjectProvider<VectorStores> stores, VectorStoreAccess storeAccess, AgentRuntime runtime) {
        this.stores = stores;
        this.storeAccess = storeAccess;
        this.runtime = runtime;
    }

    /**
     * Where the caller's files are used: the caller's chats that have them
     * attached — images included, which are never indexed — and the stores the
     * caller can reach that list them. A chat's upload store counts as its chat.
     */
    public FileUsage of(UserId caller) {
        Map<String, Map<String, ManagedSession>> chats = new HashMap<>();
        Map<String, ManagedSession> ownChats = new HashMap<>();
        for (ManagedSession session : runtime.service(SessionManagement.class).list(caller)) {
            ownChats.put(session.id().value(), session);
            session.attachments().forEach(attached -> chats
                    .computeIfAbsent(attached.id(), id -> new LinkedHashMap<>()).put(session.id().value(), session));
        }
        Map<String, List<VectorStoreInstance>> inStores = new HashMap<>();
        try {
            VectorStoreRegistry registry = stores.getObject().registry();
            for (VectorStoreInstance instance : registry.instances()) {
                if (!storeAccess.reachable(instance, caller)) {
                    continue;
                }
                ManagedSession chat = instance.scope() == VectorStoreInstance.Scope.SESSION
                        && instance.scopeRef() != null ? ownChats.get(instance.scopeRef()) : null;
                registry.members(instance.name()).stream()
                        .filter(ref -> ref.type().equals(EntityType.FILE))
                        .forEach(ref -> {
                            if (chat != null) {
                                chats.computeIfAbsent(ref.id(), id -> new LinkedHashMap<>())
                                        .put(chat.id().value(), chat);
                            } else {
                                inStores.computeIfAbsent(ref.id(), id -> new ArrayList<>()).add(instance);
                            }
                        });
            }
        } catch (RuntimeException e) {
            // Vector stores unreadable: the chats still say where a file is used.
        }
        Map<String, List<ManagedSession>> chatsByFile = new HashMap<>();
        chats.forEach((fileId, sessions) -> chatsByFile.put(fileId, List.copyOf(sessions.values())));
        return new FileUsage(chatsByFile, inStores);
    }
}
