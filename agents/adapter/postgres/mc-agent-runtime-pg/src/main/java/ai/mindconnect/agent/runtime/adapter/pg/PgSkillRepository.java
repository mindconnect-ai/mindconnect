package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
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
 * <p>The repository is bound to one namespace: every row it writes carries
 * it, and every statement it runs matches it, so skills of another namespace
 * in the same table are invisible here.
 */
public final class PgSkillRepository implements SkillRepository {

    private final DocumentTable<Skill> skills;
    private final Namespace namespace;

    public PgSkillRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    public PgSkillRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.skills = DocumentTable.of(Skill.class)
                .table("mc_skill")
                .partitionKey("namespace", "TEXT", s -> namespace.value())
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
        return skills.find("WHERE namespace = ? ORDER BY name", namespace.value());
    }

    @Override
    public Optional<Skill> findById(SkillId id) {
        return skills.findById(namespace.value(), id.value());
    }

    @Override
    public Optional<Skill> findByName(String name) {
        if (name == null) return Optional.empty();
        return skills.findOne("WHERE namespace = ? AND lower(name) = lower(?) ORDER BY updated_at LIMIT 1",
                namespace.value(), name.strip());
    }

    @Override
    /** Checks the version against the row read {@code FOR UPDATE} and stores it one higher, in one transaction. */
    public Skill save(Skill skill) {
        Skill valid = skill.validated();
        return skills.compute(namespace.value(), valid.id().value(), current ->
                valid.withVersion(ai.mindconnect.common.Versions.next(
                        current.map(Skill::version).orElse(null), valid.version(),
                        "Skill", valid.id().value())));
    }

    @Override
    public void deleteById(SkillId id) {
        skills.deleteById(namespace.value(), id.value());
    }
}
