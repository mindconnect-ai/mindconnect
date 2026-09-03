package ai.mindconnect.jdbc;

import java.sql.SQLException;

/** Turns the current row into a value. Called once per row by {@link Sql#query}. */
@FunctionalInterface
public interface RowMapper<T> {

    T map(Row row) throws SQLException;
}
