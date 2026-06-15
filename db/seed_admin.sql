-- Admin user for Ascentra (Spring BCrypt — works with POST /api/v1/auth/login)
-- Email:    admin@gmail.com
-- Password: admin@123

INSERT INTO users (
  name,
  email,
  password_hash,
  role,
  status,
  two_factor_enabled,
  created_at,
  updated_at
)
VALUES (
  'Platform Admin',
  'admin@gmail.com',
  '$2y$10$ct5GmgyvijyT1cGsCg.QnOm22ImmueWQA1jSR74hQLmzO4bttXooW',
  'ADMIN',
  'ACTIVE',
  FALSE,
  now(),
  now()
)
ON CONFLICT (email) DO UPDATE SET
  role = 'ADMIN',
  status = 'ACTIVE',
  password_hash = EXCLUDED.password_hash,
  updated_at = now();
