SELECT 'create database copytrading'
WHERE NOT EXISTS (SELECT 1 FROM pg_database
WHERE datname = 'copytrading')\gexec
