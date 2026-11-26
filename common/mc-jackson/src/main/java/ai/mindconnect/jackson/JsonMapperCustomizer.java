package ai.mindconnect.jackson;

import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * How a host wants its mapper — the one the runtime's stores, gateways and web
 * layer share: whether it indents, how it writes dates, whether it reads
 * unknown fields. The host says it, the runtime builds the mapper; the host
 * cannot build it itself, because the modules come from the runtime's plugins.
 */
@FunctionalInterface
public interface JsonMapperCustomizer {

    JsonMapper.Builder customize(JsonMapper.Builder mapper);
}
