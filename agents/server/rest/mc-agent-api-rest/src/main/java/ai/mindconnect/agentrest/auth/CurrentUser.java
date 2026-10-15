package ai.mindconnect.agentrest.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link ai.mindconnect.agent.UserId} controller parameter as the
 * authenticated caller of the request. The value never comes from the client:
 * it is what the security layer established (see {@link CurrentUsers}). A
 * request without an authenticated caller does not reach the method — it is
 * answered with 401.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
