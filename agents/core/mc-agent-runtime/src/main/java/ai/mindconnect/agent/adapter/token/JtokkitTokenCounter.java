package ai.mindconnect.agent.adapter.token;

import ai.mindconnect.agent.port.out.TokenCounter;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

public class JtokkitTokenCounter implements TokenCounter {

    private final Encoding encoding;

    public JtokkitTokenCounter(EncodingType encodingType) {
        this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(encodingType);
    }

    @Override
    public int countText(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokens(text);
    }
}
