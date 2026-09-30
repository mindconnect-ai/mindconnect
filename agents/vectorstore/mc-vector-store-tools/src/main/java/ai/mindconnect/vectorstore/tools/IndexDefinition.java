package ai.mindconnect.vectorstore.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.regex.Pattern;

/**
 * Where the chunks of the stores naming this index live: a pgvector table —
 * in the application's database or one of its own — or a directory of files.
 * Stores say what belongs together; an index says where it is kept, so chat
 * uploads can live apart from knowledge bases, and a namespace can put its
 * vectors into a database of its own.
 *
 * <p>Two indexes are built in, from the host's settings: {@value #DEFAULT} and
 * {@value #CHAT_UPLOADS} (its own table). A namespace may save a definition
 * under either name to move them, or add more.
 *
 * @param name        the index's name, which templates and stores refer to
 * @param kind        {@value #PGVECTOR} or {@value #FILE}
 * @param table       pgvector: the table ({@code mc_embedding} if blank); its declared
 *                    fields go to {@code <table>_field}
 * @param url         pgvector: a JDBC URL of a database of its own; blank for the application's
 * @param user        pgvector with a URL: the database user
 * @param password    pgvector with a URL: stored encrypted ({@code enc:…}); a {@code ${VAR}}
 *                    placeholder is read from the environment
 * @param directory   file: the directory under {@code <data dir>/<namespace>/}; derived from the name if blank
 * @param description free text for the admin screen
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexDefinition(
        String name,
        String kind,
        String table,
        String url,
        String user,
        String password,
        String directory,
        String description
) {

    public static final String DEFAULT = "default";
    public static final String CHAT_UPLOADS = "chat-uploads";
    public static final String PGVECTOR = "pgvector";
    public static final String FILE = "file";

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern TABLE = Pattern.compile("[a-z_][a-z0-9_]{0,47}");
    private static final Pattern DIRECTORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public IndexDefinition {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("An index name is lower case letters, digits, '.', '_' and '-': '" + name + "'");
        }
        if (!PGVECTOR.equals(kind) && !FILE.equals(kind)) {
            throw new IllegalArgumentException("An index is '" + PGVECTOR + "' or '" + FILE + "', not '" + kind + "'");
        }
        table = blank(table) ? null : table.strip();
        if (table != null && !TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("A table name is lower case letters, digits and '_': '" + table + "'");
        }
        directory = blank(directory) ? null : directory.strip();
        if (directory != null && !DIRECTORY.matcher(directory).matches()) {
            throw new IllegalArgumentException("A directory is one plain name under the namespace's data: '" + directory + "'");
        }
        url = blank(url) ? null : url.strip();
        user = blank(user) ? null : user;
        password = blank(password) ? null : password;
    }

    public boolean pgvector() {
        return PGVECTOR.equals(kind);
    }

    /** The table this definition uses when it is pgvector. */
    public String effectiveTable() {
        return table != null ? table : ai.mindconnect.vectorstore.pgvector.PgEmbeddingIndex.DEFAULT_TABLE;
    }

    /** The directory under the namespace's data this definition uses when it is files. */
    public String effectiveDirectory() {
        if (directory != null) return directory;
        return DEFAULT.equals(name) ? "embeddings" : "embeddings-" + name;
    }

    /** The same definition with {@code password} as given — encrypted by whoever saves it. */
    public IndexDefinition withPassword(String password) {
        return new IndexDefinition(name, kind, table, url, user, password, directory, description);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
