package ai.mindconnect.extension.adapter.pg;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.domain.BrandActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgBrandActivationRepositoryTest {

    private static final ExtensionId ACME = ExtensionId.of("acme-crm");

    private PgBrandActivationRepository repo;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(TestDb.require());
        sql.execute("DROP TABLE IF EXISTS mc_extension_brand_activation");
        repo = new PgBrandActivationRepository(sql).initSchema();
    }

    @Test
    void a_decision_is_stored_per_brand_and_replaced_on_save() {
        repo.save(BrandActivation.of("erni", ACME, false, true, UserId.of("david")));
        repo.save(BrandActivation.of("erni", ACME, true, false, UserId.of("david")));

        assertThat(repo.find("erni", ACME)).isPresent().get().satisfies(a -> {
            assertThat(a.enabled()).isTrue();
            assertThat(a.locked()).isFalse();
            assertThat(a.changedBy()).isEqualTo(UserId.of("david"));
        });
        assertThat(repo.find("other", ACME)).isEmpty();
        assertThat(repo.all("erni")).hasSize(1);
    }

    @Test
    void deleting_forgets_the_decision() {
        repo.save(BrandActivation.of("erni", ACME, false, false, null));
        repo.delete("erni", ACME);

        assertThat(repo.find("erni", ACME)).isEmpty();
        assertThat(repo.all("erni")).isEmpty();
    }
}
