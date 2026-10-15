package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.ImportReport;
import ai.mindconnect.agent.registry.domain.RegistryItemType;

/**
 * What a button on the catalog started, and what came of it.
 *
 * <p>The catalog answers an import with itself, so the page has to carry the
 * answer: what was asked for, which rubric to unfold so the rows that changed
 * are in sight, and either the report or why it could not run at all.
 *
 * @param what   the headline the report is filed under — an entry's name, or a
 *               rubric's title when a whole rubric was imported
 * @param rubric the kind to unfold; {@code null} when the import named none
 * @param report what happened, member by member; {@code null} when it did not run
 * @param error  why it did not run; {@code null} when it did
 */
record RegistryOutcome(String what, RegistryItemType rubric, ImportReport report, String error) {
}
