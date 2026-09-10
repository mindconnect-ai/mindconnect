#!/bin/sh
# Runs once, on the first start of an empty data directory. Keycloak and the
# other applications get their own database inside the same Postgres instance —
# a second Postgres container would cost another ~250 MB of RAM for nothing.
set -e
for db in ${EXTRA_DATABASES}; do
	echo "creating database ${db}"
	psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres <<-SQL
		CREATE DATABASE "${db}" OWNER "${POSTGRES_USER}";
	SQL
done
