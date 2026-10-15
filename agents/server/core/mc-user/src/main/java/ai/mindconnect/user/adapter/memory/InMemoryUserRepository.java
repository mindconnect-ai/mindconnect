package ai.mindconnect.user.adapter.memory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link UserRepository} on the heap — for tests and embedders that keep nothing. */
public class InMemoryUserRepository implements UserRepository {

    private final Map<UserId, User> users = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public List<User> findAll() {
        return List.copyOf(users.values());
    }

    @Override
    public void save(User user) {
        users.put(user.id(), user);
    }
}
