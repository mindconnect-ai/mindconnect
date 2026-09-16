package ai.mindconnect.agent.runtime.feature.fileupload;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.NamespaceRouting;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.feature.tools.ToolsFeature;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.UserHome;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreFactory;
import ai.mindconnect.filestore.SpiFileStoreFactory;
import ai.mindconnect.filestore.adapter.pg.PgFileStoreFactory;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.vectorstore.tools.VectorStores;

import java.util.Map;
import java.util.function.Function;
import java.util.Set;

/**
 * Files attached to a chat: the file store that keeps them — following the
 * runtime's persistence: a directory (the {@code filesystem} backend, or the
 * one the {@code fileStoreBackend} property names) or Postgres — and the
 * attach path that indexes a document into the session's vector store or
 * hands an image to the model. Needs the tools feature for the
 * {@code vector_search} and {@code view_attachment} tools it activates.
 */
public class FileUploadFeature implements RuntimeFeature {

    @Override
    public String name() {
        return "file-upload";
    }

    @Override
    public Set<Class<? extends RuntimeFeature>> dependsOn() {
        return Set.of(ToolsFeature.class);
    }

    @Override
    public void configure(FeatureContext ctx) {
        // The factory per namespace: the persistence setting looked at once, the routing builds the rest.
        Function<Namespace, FileStoreFactory> factories = switch (ctx.persistence()) {
            // In the database — unless fileStoreBackend names another backend: a host may keep
            // its records in Postgres and its files on a volume, as it always could.
            case Persistence.Postgres p -> ctx.property("fileStoreBackend").filter(b -> !b.isBlank() && !"postgres".equals(b)).isPresent()
                    ? ns -> spiFactory(ctx, p, ns)
                    : ns -> new PgFileStoreFactory(ctx.require(ai.mindconnect.jdbc.Sql.class), ns);
            case Persistence.File f -> ns -> spiFactory(ctx, f, ns);
            case Persistence.InMemory m -> ns -> spiFactory(ctx, m, ns);   // the side-channel directory
        };
        ctx.bean(FileStore.class, () -> ctx.require(NamespaceRouting.class).route(
                FileStore.class, ns -> factories.apply(ns).fileStore()));
        // The vector stores the attach path indexes into — and the vector tools search — from the same settings.
        ctx.bean(VectorStores.class, () -> VectorStores.fromEnvironment(ctx.require(ToolEnvironment.class))
                .orElseThrow(() -> new ai.mindconnect.agent.runtime.feature.FeatureException(
                        "The vector stores could not be opened from the runtime's settings — see VectorStores.fromEnvironment")));
        ctx.bean(AttachSupport.class, () -> AttachSupport.create(
                ctx.properties(),
                ctx.require(DynamicToolActivations.class),
                ctx.require(AgentSessionRepository.class),
                ctx.require(VectorStores.class),
                ctx.runtime().beans(),
                ctx.require(FileStore.class),
                ctx.require(Namespace.class),
                ctx.require(UserHome.class)));
    }

    private static FileStoreFactory spiFactory(FeatureContext ctx, Persistence persistence, Namespace namespace) {
        return new SpiFileStoreFactory(ctx.property("fileStoreBackend").orElse("filesystem"), Map.of(
                "baseDir", persistence.dataDir().toString(),
                "namespace", namespace.value()));
    }
}
