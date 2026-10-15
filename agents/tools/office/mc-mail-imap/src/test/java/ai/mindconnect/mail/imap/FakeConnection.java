package ai.mindconnect.mail.imap;

import ai.mindconnect.agent.tool.ToolConnection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A connection as the runtime would hand one in. The real one reads across
 * encrypted and readable halves; a tool cannot tell the difference, and
 * neither can this.
 */
record FakeConnection(String key, String label, Map<String, String> values, boolean usable)
        implements ToolConnection {

    static FakeConnection of(String key, Map<String, String> values) {
        return new FakeConnection(key, key.substring(0, 1).toUpperCase() + key.substring(1), values, true);
    }

    @Override
    public String provider() {
        return MailAccount.PROVIDER;
    }

    @Override
    public String value(String field) {
        return values.get(field);
    }

    /** This connection with one field changed — for the test that breaks the password. */
    FakeConnection with(String field, String value) {
        Map<String, String> changed = new LinkedHashMap<>(values);
        changed.put(field, value);
        return new FakeConnection(key, label, changed, usable);
    }

    FakeConnection without(String field) {
        Map<String, String> changed = new LinkedHashMap<>(values);
        changed.remove(field);
        return new FakeConnection(key, label, changed, usable);
    }
}
