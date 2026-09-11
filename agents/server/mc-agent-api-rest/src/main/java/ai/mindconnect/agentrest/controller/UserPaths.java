package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A path that names a user — {@code /api/users/{userId}/…},
 * {@code /api/workspaces/user/{userId}/…} — leads to the caller's own data
 * and nobody else's. The segment is the caller's id or {@code me}, for a
 * client that does not know which id its login maps to. Any other user is
 * answered like a user that does not exist: a 404, where a 403 would confirm
 * that the id is someone's.
 */
class UserPaths {

    /** The path segment that always means the caller. */
    static final String ME = "me";

    private UserPaths() {
    }

    /** The caller, when {@code pathUserId} names them; a 404 for the request otherwise. */
    static UserId requireSelf(String pathUserId, UserId caller) {
        if (ME.equals(pathUserId) || caller.value().equals(pathUserId)) {
            return caller;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user");
    }
}
