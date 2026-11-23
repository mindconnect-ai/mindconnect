package ai.mindconnect.agent.runtime.engine.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.TurnListener;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;

/**
 * Hands a turn's events to whoever listens, and keeps them so a listener
 * that comes late is told what it missed — between a submit and the
 * subscribe that follows it, the turn may already have said everything.
 *
 * <p>Events live in this object: a listener in another process hears
 * nothing, and the events of every turn stay until the hub is gone.
 */
final class TurnEventHub {

    /** One turn's events so far and its listeners, changed and read under its own lock. */
    private static final class Channel {
        private final List<TurnEvent> events = new ArrayList<>();
        /** The turn as it ended; {@code null} while it has not. */
        private Turn finished;
        // A listener may leave while it is being told something — at the turn's end, typically.
        private final List<TurnListener> listeners = new CopyOnWriteArrayList<>();
    }

    private final Map<TurnId, Channel> channels = new ConcurrentHashMap<>();

    void publish(TurnId turnId, TurnEvent event) {
        Channel channel = channels.computeIfAbsent(turnId, id -> new Channel());
        synchronized (channel) {
            channel.events.add(event);
            channel.listeners.forEach(listener -> listener.onEvent(event));
        }
    }

    /** The turn is over: told once, to whoever listens now and whoever comes later. */
    void finished(Turn turn) {
        Channel channel = channels.computeIfAbsent(turn.id(), id -> new Channel());
        synchronized (channel) {
            if (channel.finished == null) {
                channel.finished = turn;
                channel.listeners.forEach(listener -> listener.onFinished(turn));
            }
        }
    }

    Subscription subscribe(TurnId turnId, TurnListener listener) {
        Channel channel = channels.computeIfAbsent(turnId, id -> new Channel());
        synchronized (channel) {
            channel.events.forEach(listener::onEvent);
            if (channel.finished != null) {
                listener.onFinished(channel.finished);
            }
            channel.listeners.add(listener);
        }
        return () -> {
            synchronized (channel) {
                channel.listeners.remove(listener);
            }
        };
    }
}
