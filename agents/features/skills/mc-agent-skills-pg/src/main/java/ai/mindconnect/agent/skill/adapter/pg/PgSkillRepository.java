package ai.mindconnect.agent.skill.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.skill.Skill;
import ai.mindconnect.agent.skill.SkillId;
import ai.mindconnect.agent.skill.SkillRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SkillRepository} on Postgres: one row of {@code mc_skill} per
 * skill, keyed by {@code (namespace, id)}, with the name beside the
 * document. {@link #findByName} is case-insensitive, as the file store's is.
 *
 * <p>Every row it writes carries the namespace the scope names for the
 * current call, and every statement it runs matches it, so skills of another
 * namespace in the same table are invisible here.
 */
public final class PgSkillRepository implements SkillRepository {

    private final DocumentTable<Skill> skills;
    private final ScopeSupplier scope;

    public PgSkillRepository(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    public PgSkillRepository(Sql sql, ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.skills = DocumentTable.of(Skill.class)
                .table("mc_skill")
                .partitionKey("namespace", "TEXT", s -> scope.namespace().value())
                .id("id", "TEXT", s -> s.id().value())
                .requiredColumn("name", "TEXT", Skill::name)
                .index("namespace", "name")
                .build(sql);
    }

    public PgSkillRepository initSchema() {
        skills.createSchema();
        return this;
    }

    @Override
    public List<Skill> findAll() {
        return skills.find("WHERE namespace = ? ORDER BY name", scope.namespace().value());
    }

    @Override
    public Optional<Skill> findById(SkillId id) {
        return skills.findById(scope.namespace().value(), id.value());
    }

    @Override
    public Optional<Skill> findByName(String name) {
        if (name == null) return Optional.empty();
        return skills.findOne("WHERE namespace = ? AND lower(name) = lower(?) ORDER BY updated_at LIMIT 1",
                scope.namespace().value(), name.strip());
    }

    @Override
    /** Checks the version against the row read {@code FOR UPDATE} and stores it one higher, in one transaction. */
    public Skill save(Skill skill) {
        Skill valid = skill.validated();
        return skills.compute(scope.namespace().value(), valid.id().value(), current ->
                valid.withVersion(ai.mindconnect.common.Versions.next(
                        current.map(Skill::version).orElse(null), valid.version(),
                        "Skill", valid.id().value())));
    }

    @Override
    public void deleteById(SkillId id) {
        skills.deleteById(scope.namespace().value(), id.value());
    }
}
