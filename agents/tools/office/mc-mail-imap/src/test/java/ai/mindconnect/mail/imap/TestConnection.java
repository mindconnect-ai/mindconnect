package ai.mindconnect.mail.imap;

import ai.mindconnect.agent.tool.ToolConnection;

import java.util.Map;

/** A connection with the values a test hands it, and nothing else. */
record TestConnection(String key, String label, String provider, Map<String, String> values)
        implements ToolConnection {

    @Override
    public String value(String field) {
        return values.get(field);
    }

    @Override
    public boolean usable() {
        return true;
    }
}
