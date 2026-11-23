package ai.mindconnect.agent.runtime.engine.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
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
        private final List<TurnEventListener> listeners = new ArrayList<>();
    }

    private final Map<TurnId, Channel> channels = new ConcurrentHashMap<>();

    void publish(TurnId turnId, TurnEvent event) {
        Channel channel = channels.computeIfAbsent(turnId, id -> new Channel());
        synchronized (channel) {
            channel.events.add(event);
            channel.listeners.forEach(listener -> listener.onEvent(event));
        }
    }

    Subscription subscribe(TurnId turnId, TurnEventListener listener) {
        Channel channel = channels.computeIfAbsent(turnId, id -> new Channel());
        synchronized (channel) {
            channel.events.forEach(listener::onEvent);
            channel.listeners.add(listener);
        }
        return () -> {
            synchronized (channel) {
                channel.listeners.remove(listener);
            }
        };
    }
}
