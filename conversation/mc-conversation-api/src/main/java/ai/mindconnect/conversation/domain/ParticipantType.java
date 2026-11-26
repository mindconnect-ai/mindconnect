package ai.mindconnect.conversation.domain;

/**
 * What a participant is, and with it what the messages it writes are. Whether
 * a person is a customer or works in support is their role, kept in the
 * participant's metadata.
 */
public enum ParticipantType {

    /** A human: a customer, someone of the support, a member of a group. */
    PERSON,

    /** A program that takes part: an agent of this installation, or a bot of the channel. */
    BOT,

    /** The conversation itself, telling what happened in it: someone joined, it was handed over. */
    SYSTEM
}
