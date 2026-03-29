INSERT INTO users (name, email, password_hash, role, status)
VALUES (
  'Platform Admin',
  'admin@ascentra.com',
  crypt('Admin@123', gen_salt('bf')),
  'ADMIN',
  'ACTIVE'
)
ON CONFLICT (email) DO NOTHING;
