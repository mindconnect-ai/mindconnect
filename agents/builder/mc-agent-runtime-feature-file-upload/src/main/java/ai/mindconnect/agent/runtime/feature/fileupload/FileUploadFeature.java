package ai.mindconnect.agent.runtime.feature.fileupload;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
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
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

import java.util.Map;
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
        ctx.bean(FileStoreFactory.class, () -> switch (ctx.persistence()) {
            case Persistence.Postgres p -> new PgFileStoreFactory(
                    ctx.require(ai.mindconnect.jdbc.Sql.class), ctx.require(Namespace.class));
            case Persistence.File f -> spiFactory(ctx, f);
            case Persistence.InMemory m -> spiFactory(ctx, m);   // the side-channel directory
        });
        ctx.bean(FileStore.class, () -> ctx.require(FileStoreFactory.class).fileStore());
        ctx.bean(AttachSupport.class, () -> AttachSupport.create(
                ctx.properties(),
                ctx.require(DynamicToolActivations.class),
                ctx.require(AgentSessionRepository.class),
                ctx.require(LlmEmbeddings.class),
                ctx.require(LlmConfigRepository.class),
                ctx.runtime().beans(),
                ctx.require(FileStore.class),
                ctx.require(Namespace.class),
                ctx.require(UserHome.class)));
    }

    private static FileStoreFactory spiFactory(FeatureContext ctx, Persistence persistence) {
        return new SpiFileStoreFactory(ctx.property("fileStoreBackend").orElse("filesystem"), Map.of(
                "baseDir", persistence.dataDir().toString(),
                "namespace", ctx.require(Namespace.class).value()));
    }
}
