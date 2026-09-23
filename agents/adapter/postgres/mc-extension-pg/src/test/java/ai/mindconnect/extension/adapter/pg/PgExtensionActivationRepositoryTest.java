package ai.mindconnect.extension.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgExtensionActivationRepositoryTest {

    private static final ExtensionId ACME = ExtensionId.of("acme-crm");

    private PgExtensionActivationRepository acme;
    private PgExtensionActivationRepository other;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(TestDb.require());
        sql.execute("DROP TABLE IF EXISTS mc_extension_activation");
        acme = new PgExtensionActivationRepository(sql, new Namespace("acme")).initSchema();
        other = new PgExtensionActivationRepository(sql, new Namespace("other")).initSchema();
    }

    @Test
    void a_decision_is_stored_per_namespace() {
        acme.save(ExtensionActivation.of(ACME, false, UserId.of("david")));

        assertThat(acme.find(ACME)).isPresent().get().extracting(ExtensionActivation::enabled).isEqualTo(false);
        assertThat(acme.find(ACME).orElseThrow().changedBy()).isEqualTo(UserId.of("david"));
        assertThat(other.find(ACME)).isEmpty();
        assertThat(acme.all()).hasSize(1);
    }

    @Test
    void a_later_decision_replaces_the_earlier_one() {
        acme.save(ExtensionActivation.of(ACME, false, null));
        acme.save(ExtensionActivation.of(ACME, true, null));

        assertThat(acme.find(ACME).orElseThrow().enabled()).isTrue();
        assertThat(acme.all()).hasSize(1);
    }

    @Test
    void deleting_forgets_the_decision() {
        acme.save(ExtensionActivation.of(ACME, false, null));
        acme.delete(ACME);

        assertThat(acme.find(ACME)).isEmpty();
        assertThat(acme.all()).isEmpty();
    }
}
