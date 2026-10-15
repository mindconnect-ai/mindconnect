package ai.mindconnect.agent.runtime.feature.fileupload;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.kernel.FeatureContext;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.kernel.Feature;
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
public class FileUploadFeature implements Feature {

    @Override
    public String name() {
        return "file-upload";
    }

    @Override
    public Set<Class<? extends Feature>> dependsOn() {
        return Set.of(ToolsFeature.class);
    }

    @Override
    public void configure(FeatureContext ctx) {
        // The factory: the persistence setting looked at once; the store works in the namespace the scope names per call.
        Function<ScopeSupplier, FileStoreFactory> factories = switch (ctx.require(Persistence.class)) {
            // In the database — unless fileStoreBackend names another backend: a host may keep
            // its records in Postgres and its files on a volume, as it always could.
            case Persistence.Postgres p -> ctx.property("fileStoreBackend").filter(b -> !b.isBlank() && !"postgres".equals(b)).isPresent()
                    ? scope -> spiFactory(ctx, p, scope)
                    : scope -> new PgFileStoreFactory(ctx.require(ai.mindconnect.jdbc.Sql.class), scope);
            case Persistence.File f -> scope -> spiFactory(ctx, f, scope);
            case Persistence.InMemory m -> scope -> spiFactory(ctx, m, scope);   // the side-channel directory
        };
        ctx.bean(FileStore.class, () -> factories.apply(ctx.require(ScopeSupplier.class)).fileStore());
        // The vector stores the attach path indexes into — and the vector tools search — from the same settings.
        ctx.bean(VectorStores.class, () -> VectorStores.fromEnvironment(ctx.require(ToolEnvironment.class))
                .orElseThrow(() -> new ai.mindconnect.kernel.FeatureException(
                        "The vector stores could not be opened from the runtime's settings — see VectorStores.fromEnvironment")));
        ctx.bean(AttachSupport.class, () -> AttachSupport.create(
                ctx.properties(),
                ctx.require(DynamicToolActivations.class),
                ctx.require(AgentSessionRepository.class),
                ctx.require(VectorStores.class),
                ctx.kernel().beans(),
                ctx.require(FileStore.class),
                ctx.require(UserHome.class)));
    }

    private static FileStoreFactory spiFactory(FeatureContext ctx, Persistence persistence, ScopeSupplier scope) {
        return new SpiFileStoreFactory(ctx.property("fileStoreBackend").orElse("filesystem"), Map.of(
                "baseDir", persistence.dataDir().toString()), scope);
    }
}
