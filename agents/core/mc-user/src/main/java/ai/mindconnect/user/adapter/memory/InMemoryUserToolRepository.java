package ai.mindconnect.user.adapter.memory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.UserTool;
import ai.mindconnect.user.domain.UserToolId;
import ai.mindconnect.user.port.out.UserToolRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link UserToolRepository} in a map — for tests and hosts that keep nothing. */
public class InMemoryUserToolRepository implements UserToolRepository {

    private final Map<UserToolId, UserTool> byId = new ConcurrentHashMap<>();

    @Override
    public void save(UserTool tool) {
        byId.put(tool.id(), tool);
    }

    @Override
    public Optional<UserTool> findById(UserToolId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<UserTool> findByUser(UserId userId) {
        return byId.values().stream().filter(tool -> tool.userId().equals(userId)).toList();
    }

    @Override
    public void deleteById(UserToolId id) {
        byId.remove(id);
    }
}
