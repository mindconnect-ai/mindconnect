package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.VectorStoreOverviewPage;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.agentrest.auth.VectorStoreAccess;
import ai.mindconnect.agentrest.service.FileUsageService;
import ai.mindconnect.agentrest.service.VectorStoreContents;
import ai.mindconnect.agentrest.service.VectorStoreService;
import ai.mindconnect.agentrest.service.VectorStoreService.IndexInfo;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the vector stores overview for the signed-in user. Every action on
 * a template, an index, a store or a file answers with it, so the four
 * controllers share this one place that gathers what it shows.
 */
@Component
class VectorStoreOverview {

    private final VectorStoreService vectorStores;
    private final VectorStoreContents contents;
    private final FileUsageService fileUsage;
    private final FileStore fileStore;
    private final CurrentUsers currentUsers;
    private final VectorStoreAccess storeAccess;

    VectorStoreOverview(VectorStoreService vectorStores, VectorStoreContents contents, FileUsageService fileUsage,
                        FileStore fileStore, CurrentUsers currentUsers, VectorStoreAccess storeAccess) {
        this.vectorStores = vectorStores;
        this.contents = contents;
        this.fileUsage = fileUsage;
        this.fileStore = fileStore;
        this.currentUsers = currentUsers;
        this.storeAccess = storeAccess;
    }

    /** The overview as tabs; {@code tab} picks which one opens (after an action, the one it happened in). */
    UiPage page(String tab) {
        List<VectorStoreTemplate> templates = vectorStores.templates();
        UserId caller = currentUsers.require();
        // A chat's upload store is its user's: nobody else sees it listed.
        var stores = contents.summaries(instance -> storeAccess.reachable(instance, caller));
        // What the Files API gives the caller by id: their own files and those
        // stored before creators were recorded — nobody else's names and ids.
        var usages = fileUsage.of(caller);
        var files = fileStore.list().stream().filter(file -> file.readableBy(caller)).toList();
        List<IndexInfo> indexes = List.of();
        String indexesUnavailable = null;
        try {
            indexes = vectorStores.indexes();
        } catch (RuntimeException e) {
            indexesUnavailable = e.getMessage();
        }
        return new VectorStoreOverviewPage(templates, indexes, indexesUnavailable, stores, files, usages, tab)
                .render();
    }
}
