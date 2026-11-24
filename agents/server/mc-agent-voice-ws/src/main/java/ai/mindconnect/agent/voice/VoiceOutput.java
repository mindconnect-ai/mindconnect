package ai.mindconnect.agent.voice;

import ai.mindconnect.agent.voice.protocol.VoiceEvent;

/** Where a conversation's events and audio go — the client's connection. */
public interface VoiceOutput {

    void send(VoiceEvent event);

    void sendAudio(byte[] audio);
}
