package ai.mindconnect.agent.voice;

/**
 * The server's voice defaults ({@code mindconnect.voice.*}).
 *
 * @param speechToTextConfig the LLM config (or alias) that transcribes
 * @param textToSpeechConfig the LLM config (or alias) that speaks; answers stay
 *                           text-only while no config of that name exists
 * @param language           the spoken language when a client names none
 * @param maxUtteranceBytes  how much audio one utterance may carry
 */
public record VoiceSettings(String speechToTextConfig, String textToSpeechConfig, String language,
                            int maxUtteranceBytes) {
}
