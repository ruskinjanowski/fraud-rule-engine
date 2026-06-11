-- Runs once, on first container init, against the POSTGRES_DB (fraud_rule_engine).
--
-- Creates a dedicated, least-privilege application role so the service never connects
-- as the 'postgres' superuser. The app (and Flyway, which runs with the same datasource
-- credentials) needs CREATE/USAGE on the public schema to manage and use its tables;
-- it gets nothing more.
CREATE ROLE fraud_app WITH LOGIN PASSWORD 'fraud';

GRANT CONNECT ON DATABASE fraud_rule_engine TO fraud_app;
GRANT ALL ON SCHEMA public TO fraud_app;
