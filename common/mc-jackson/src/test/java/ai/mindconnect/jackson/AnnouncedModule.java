package ai.mindconnect.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/** A module that announces itself the way another module of the installation does: in META-INF/services. */
public class AnnouncedModule extends SimpleModule {

    /** Written as its word only, which no mapper does on its own. */
    public record Word(String word) { }

    public AnnouncedModule() {
        super("announced-module-for-tests");
        addSerializer(Word.class, new StdSerializer<>(Word.class) {
            @Override
            public void serialize(Word value, JsonGenerator out, SerializerProvider provider) throws java.io.IOException {
                out.writeString(value.word());
            }
        });
    }
}
