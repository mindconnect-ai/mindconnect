package ai.mindconnect.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** The shared rules over every module the taskqueue aggregator lists; see mc-arch-rules. */
@AnalyzeClasses(locations = AreaLocations.class)
class TaskqueueArchitectureTest {

    @ArchTest
    static final ArchTests conventions = ArchTests.in(McArchRules.class);
}
