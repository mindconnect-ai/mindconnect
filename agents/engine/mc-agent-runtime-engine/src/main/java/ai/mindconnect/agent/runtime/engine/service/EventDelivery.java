package ai.mindconnect.agent.runtime.engine.service;

/**
 * The two places a turn's events go: to whoever follows that turn, and to
 * whoever follows its session. A turn worker reports to both, so it is
 * handed them as one.
 */
record EventDelivery(TurnEventHub toTurnListeners, SessionEventHub toSessionListeners) { }
