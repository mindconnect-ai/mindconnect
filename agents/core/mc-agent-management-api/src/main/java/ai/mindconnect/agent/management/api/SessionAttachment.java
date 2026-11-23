package ai.mindconnect.agent.management.api;

/**
 * A file attached to a session.
 *
 * @param id        its id in the file store; {@code null} for a file that
 *                  is only named
 * @param name      what the file is called in the session; a session holds
 *                  one file of a name
 * @param mediaType as it was recorded; {@code null} where it is not known
 * @param path      where a copy lies on disk for the session's tools;
 *                  {@code null} for a file without one
 */
public record SessionAttachment(String id, String name, String mediaType, long sizeBytes, String path) { }
