package ai.mindconnect.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/** A module of the installation, as a plugin contributes one. */
public class ContributedModule extends SimpleModule {

    /** Written as its word only, which no mapper does on its own. */
    public record Word(String word) { }

    public ContributedModule() {
        super("contributed-module-for-tests");
        addSerializer(Word.class, new StdSerializer<>(Word.class) {
            @Override
            public void serialize(Word value, JsonGenerator out, SerializerProvider provider) throws java.io.IOException {
                out.writeString(value.word());
            }
        });
    }
}
