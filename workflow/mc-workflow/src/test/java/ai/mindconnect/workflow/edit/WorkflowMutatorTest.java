package ai.mindconnect.workflow.edit;

import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.WorkflowData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests cover the three structural concerns: addressing nested steps by
 * name, insert/delete semantics in the same containing list, and rename
 * (the simplest property update that still has to walk the tree). The
 * happy paths are obvious; the failure modes — stale refs, deeply nested
 * steps — are what these tests exist for.
 */
class WorkflowMutatorTest {

    private final WorkflowMutator mutator = new WorkflowMutator();

    // -----------------------------------------------------------------------
    // find()
    // -----------------------------------------------------------------------

    @Test
    void findLocatesTopLevelStep() {
        WorkflowData wf = new WorkflowData();
        CodeData a = code("a");
        CodeData b = code("b");
        wf.addSteps(a, b);

        WorkflowMutator.StepLocation loc = mutator.find(wf, "b");

        assertThat(loc).isNotNull();
        assertThat(loc.step()).isSameAs(b);
        assertThat(loc.container()).isSameAs(wf.getSteps());
        assertThat(loc.index()).isEqualTo(1);
    }

    @Test
    void findRecursesIntoIfBranches() {
        WorkflowData wf = new WorkflowData();
        CodeData nested = code("nested");
        BlockData thenBlock = new BlockData();
        thenBlock.addSteps(nested);

        IfData ifStep = new IfData();
        ifStep.setName("branch");
        IfData.Condition cond = new IfData.Condition();
        cond.setCondition("x > 0");
        cond.setThenBlock(thenBlock);
        ifStep.setConditions(cond);

        wf.addSteps(ifStep);

        WorkflowMutator.StepLocation loc = mutator.find(wf, "nested");

        assertThat(loc).isNotNull();
        assertThat(loc.step()).isSameAs(nested);
        assertThat(loc.container()).isSameAs(thenBlock.getSteps());
    }

    @Test
    void findRecursesIntoForEachBody() {
        WorkflowData wf = new WorkflowData();
        ForEachData fe = new ForEachData();
        fe.setName("loop");
        CodeData body = code("body");
        fe.addSteps(body);
        wf.addSteps(fe);

        assertThat(mutator.find(wf, "body").step()).isSameAs(body);
    }

    @Test
    void findReturnsNullForUnknownRef() {
        WorkflowData wf = new WorkflowData();
        wf.addSteps(code("only"));
        assertThat(mutator.find(wf, "nope")).isNull();
    }

    // -----------------------------------------------------------------------
    // insertAfter / insertAtStart
    // -----------------------------------------------------------------------

    @Test
    void insertAfterPlacesNewStepRightAfterAnchor() {
        WorkflowData wf = new WorkflowData();
        wf.addSteps(code("a"), code("c"));

        mutator.insertAfter(wf, "a", code("b"));

        assertThat(wf.getSteps())
            .extracting(s -> ((CodeData) s).getName())
            .containsExactly("a", "b", "c");
    }

    @Test
    void insertAfterInsideElseBlock() {
        WorkflowData wf = new WorkflowData();
        CodeData existing = code("only-else-step");
        BlockData elseBlock = new BlockData();
        elseBlock.addSteps(existing);

        IfData ifStep = new IfData();
        ifStep.setName("branch");
        ifStep.setElseBlock(elseBlock);
        wf.addSteps(ifStep);

        mutator.insertAfter(wf, "only-else-step", code("inserted"));

        assertThat(elseBlock.getSteps())
            .extracting(s -> ((CodeData) s).getName())
            .containsExactly("only-else-step", "inserted");
    }

    @Test
    void insertAtStartPrependsToTopLevel() {
        WorkflowData wf = new WorkflowData();
        wf.addSteps(code("b"));

        mutator.insertAtStart(wf, code("a"));

        assertThat(wf.getSteps())
            .extracting(s -> ((CodeData) s).getName())
            .containsExactly("a", "b");
    }

    @Test
    void insertAfterUnknownRefRaisesIllegalArgument() {
        WorkflowData wf = new WorkflowData();
        wf.addSteps(code("a"));

        assertThatThrownBy(() -> mutator.insertAfter(wf, "missing", code("x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing");
    }

    // -----------------------------------------------------------------------
    // delete
    // -----------------------------------------------------------------------

    @Test
    void deleteRemovesTopLevelStep() {
        WorkflowData wf = new WorkflowData();
        wf.addSteps(code("a"), code("b"), code("c"));

        assertThat(mutator.delete(wf, "b")).isTrue();

        assertThat(wf.getSteps())
            .extracting(s -> ((CodeData) s).getName())
            .containsExactly("a", "c");
    }

    @Test
    void deleteRemovesNestedStep() {
        WorkflowData wf = new WorkflowData();
        ForEachData fe = new ForEachData();
        fe.setName("loop");
        fe.addSteps(code("body"));
        wf.addSteps(fe);

        assertThat(mutator.delete(wf, "body")).isTrue();
        assertThat(fe.getSteps()).isEmpty();
    }

    @Test
    void deleteReturnsFalseForUnknownRef() {
        WorkflowData wf = new WorkflowData();
        wf.addSteps(code("a"));
        assertThat(mutator.delete(wf, "nope")).isFalse();
    }

    // -----------------------------------------------------------------------
    // rename
    // -----------------------------------------------------------------------

    @Test
    void renameUpdatesStepName() {
        WorkflowData wf = new WorkflowData();
        CodeData a = code("a");
        wf.addSteps(a);

        assertThat(mutator.rename(wf, "a", "renamed")).isTrue();
        assertThat(a.getName()).isEqualTo("renamed");
    }

    @Test
    void renameReturnsFalseForUnknownRef() {
        assertThat(mutator.rename(new WorkflowData(), "nope", "x")).isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static CodeData code(String name) {
        CodeData c = new CodeData();
        c.setName(name);
        return c;
    }

    // -----------------------------------------------------------------------
    // insertIntoContainer
    // -----------------------------------------------------------------------

    @Test
    void insertIntoBlockAppendsByDefault() {
        WorkflowData wf = new WorkflowData();
        BlockData block = new BlockData();
        block.setName("blk");
        block.addSteps(code("inside-1"));
        wf.addSteps(block);

        mutator.insertIntoContainer(wf, "block:blk", WorkflowMutator.Position.LAST, code("inside-2"));
        assertThat(block.getSteps())
            .extracting(s -> ((CodeData) s).getName())
            .containsExactly("inside-1", "inside-2");
    }

    @Test
    void insertIntoBlockAtFirstPrepends() {
        WorkflowData wf = new WorkflowData();
        BlockData block = new BlockData();
        block.setName("blk");
        block.addSteps(code("inside-1"));
        wf.addSteps(block);

        mutator.insertIntoContainer(wf, "block:blk", WorkflowMutator.Position.FIRST, code("new"));
        assertThat(block.getSteps())
            .extracting(s -> ((CodeData) s).getName())
            .containsExactly("new", "inside-1");
    }

    @Test
    void insertIntoForeachAppendsToBody() {
        WorkflowData wf = new WorkflowData();
        ForEachData fe = new ForEachData();
        fe.setName("loop");
        wf.addSteps(fe);

        mutator.insertIntoContainer(wf, "foreach:loop", WorkflowMutator.Position.LAST, code("body-step"));
        assertThat(fe.getSteps())
            .extracting(s -> ((CodeData) s).getName())
            .containsExactly("body-step");
    }

    @Test
    void insertIntoIfThenBranchTargetsCorrectCondition() {
        WorkflowData wf = new WorkflowData();
        IfData ifStep = new IfData();
        ifStep.setName("decide");
        IfData.Condition c = new IfData.Condition();
        c.setCondition("x > 0");
        c.setThenBlock(new BlockData());
        ifStep.setConditions(c);
        wf.addSteps(ifStep);

        mutator.insertIntoContainer(wf, "if:decide:then:0", WorkflowMutator.Position.LAST, code("then-step"));
        assertThat(c.getThenBlock().getSteps())
            .extracting(s -> ((CodeData) s).getName())
            .containsExactly("then-step");
    }

    @Test
    void insertIntoIfElseCreatesElseBlockOnDemand() {
        WorkflowData wf = new WorkflowData();
        IfData ifStep = new IfData();
        ifStep.setName("decide");
        IfData.Condition c = new IfData.Condition();
        c.setCondition("x > 0");
        c.setThenBlock(new BlockData());
        ifStep.setConditions(c);
        // No else block initially.
        wf.addSteps(ifStep);

        mutator.insertIntoContainer(wf, "if:decide:else", WorkflowMutator.Position.LAST, code("first-else"));
        assertThat(ifStep.getElseBlock()).isNotNull();
        assertThat(ifStep.getElseBlock().getSteps())
            .extracting(s -> ((CodeData) s).getName())
            .containsExactly("first-else");
    }

    @Test
    void insertIntoUnknownContainerRaisesIllegalArgument() {
        WorkflowData wf = new WorkflowData();
        wf.addSteps(code("a"));

        assertThatThrownBy(() -> mutator.insertIntoContainer(
                wf, "block:nope", WorkflowMutator.Position.LAST, code("x")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
