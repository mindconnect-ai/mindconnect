package ai.mindconnect.adminui.ui.component;

/** Opens the component's package-private helpers to the controller tests next door. */
public final class UserMemoryComponentAccess {

    private UserMemoryComponentAccess() {}

    public static String hardBreaks(String text) {
        return UserMemoryComponent.hardBreaks(text);
    }
}
