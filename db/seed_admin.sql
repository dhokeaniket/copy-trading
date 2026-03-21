INSERT INTO users (username, password_hash, role, active)
VALUES ('admin', crypt('Admin@123', gen_salt('bf')), 'ADMIN', true)
ON CONFLICT (username) DO NOTHING;
