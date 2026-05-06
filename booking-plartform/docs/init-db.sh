#!/bin/bash
set -e
for db in users_db bookings_db inventory_db notifications_db; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE $db;
EOSQL
done
