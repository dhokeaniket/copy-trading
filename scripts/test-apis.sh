#!/bin/bash
# ============================================================
# Ascentra Trading Platform — Full API Test Suite
# Base URL: http://localhost:8081/api/v1
# Run each section in order — later tests depend on IDs/tokens from earlier ones
# ============================================================

BASE="http://localhost:8081/api/v1"

# ============================================================
# SECTION 1: AUTHENTICATION (11 endpoints)
# ============================================================

# ── 1.1 POST /auth/register ──────────────────────────────────

# Happy path: Register MASTER
curl -s -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Master",
    "email": "master@test.com",
    "password": "Pass1234",
    "role": "MASTER",
    "phone": "9876543210"
  }' | python3 -m json.tool
# Expected: 201 { "userId": "<uuid>", "message": "Registration successful" }
# >>> SAVE the userId as MASTER_ID

# Happy path: Register CHILD
curl -s -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Child",
    "email": "child@test.com",
    "password": "Pass1234",
    "role": "CHILD",
    "phone": "1234567890"
  }' | python3 -m json.tool
# Expected: 201 { "userId": "<uuid>", "message": "Registration successful" }
# >>> SAVE the userId as CHILD_ID

# Edge case: Duplicate email
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Duplicate",
    "email": "master@test.com",
    "password": "Pass1234",
    "role": "MASTER"
  }'
# Expected: 409 { "error": "Email already registered", "status": 409 }

# Edge case: Weak password (no number)
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Weak Pass",
    "email": "weak@test.com",
    "password": "abcdefgh",
    "role": "CHILD"
  }'
# Expected: 400 { "error": "Password must be min 8 chars with at least one number" }

# Edge case: Password too short
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Short",
    "email": "short@test.com",
    "password": "Ab1",
    "role": "CHILD"
  }'
# Expected: 400

# Edge case: Missing required fields
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{ "name": "No Email" }'
# Expected: 400 { "error": "name, email, password, role are required" }

# Edge case: Invalid role
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bad Role",
    "email": "badrole@test.com",
    "password": "Pass1234",
    "role": "SUPERADMIN"
  }'
# Expected: 400 { "error": "Invalid role" }

# Edge case: Empty body
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{}'
# Expected: 400


# ── 1.2 POST /auth/login ─────────────────────────────────────

# Happy path: Login as MASTER
curl -s -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "master@test.com",
    "password": "Pass1234"
  }' | python3 -m json.tool
# Expected: 200 {
#   "accessToken": "<jwt>",
#   "refreshToken": "<jwt>",
#   "user": { "userId", "name", "email", "role": "MASTER", "status": "ACTIVE", ... },
#   "requires2FA": false
# }
# >>> SAVE accessToken as MASTER_TOKEN
# >>> SAVE refreshToken as MASTER_REFRESH

# Happy path: Login as CHILD
curl -s -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "child@test.com",
    "password": "Pass1234"
  }' | python3 -m json.tool
# Expected: 200 — same structure, role=CHILD
# >>> SAVE accessToken as CHILD_TOKEN

# Happy path: Login as ADMIN (seeded user)
curl -s -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@ascentra.com",
    "password": "Admin@123"
  }' | python3 -m json.tool
# Expected: 200 — role=ADMIN
# >>> SAVE accessToken as ADMIN_TOKEN
# >>> SAVE refreshToken as ADMIN_REFRESH

# Edge case: Wrong password
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "master@test.com",
    "password": "WrongPass1"
  }'
# Expected: 401 { "error": "Invalid credentials" }

# Edge case: Non-existent email
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "nobody@test.com",
    "password": "Pass1234"
  }'
# Expected: 401

# Edge case: Empty body
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{}'
# Expected: 401 or 500

# ── 1.3 POST /auth/logout ────────────────────────────────────

# Happy path: Logout (use MASTER_REFRESH from login response)
curl -s -X POST "$BASE/auth/logout" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{
    "refreshToken": "<MASTER_REFRESH>"
  }' | python3 -m json.tool
# Expected: 200 { "message": "Logged out successfully" }

# Edge case: Missing refreshToken
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/logout" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{}'
# Expected: 400 { "error": "refreshToken is required" }

# Edge case: No auth header
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/logout" \
  -H "Content-Type: application/json" \
  -d '{ "refreshToken": "something" }'
# Expected: 401

# ── 1.4 POST /auth/refresh-token ─────────────────────────────

# NOTE: After logout the old refresh token is revoked. Login again first to get a fresh one.

# Happy path: Refresh token (use ADMIN_REFRESH)
curl -s -X POST "$BASE/auth/refresh-token" \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "<ADMIN_REFRESH>"
  }' | python3 -m json.tool
# Expected: 200 { "accessToken": "<new_jwt>", "refreshToken": "<new_refresh>" }
# >>> The old refresh token is now INVALID (rotation)

# Edge case: Reuse old (revoked) refresh token
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/refresh-token" \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "<ADMIN_REFRESH_OLD>"
  }'
# Expected: 401 { "error": "Invalid or expired refresh token" }

# Edge case: Garbage token
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/refresh-token" \
  -H "Content-Type: application/json" \
  -d '{ "refreshToken": "not-a-real-token" }'
# Expected: 401

# Edge case: Missing field
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/refresh-token" \
  -H "Content-Type: application/json" \
  -d '{}'
# Expected: 400


# ── 1.5 POST /auth/forgot-password ───────────────────────────

# Happy path: Existing email
curl -s -X POST "$BASE/auth/forgot-password" \
  -H "Content-Type: application/json" \
  -d '{ "email": "master@test.com" }' | python3 -m json.tool
# Expected: 200 { "message": "If the email exists, a reset link has been sent" }

# Edge case: Non-existent email (same response to prevent enumeration)
curl -s -X POST "$BASE/auth/forgot-password" \
  -H "Content-Type: application/json" \
  -d '{ "email": "nobody@nowhere.com" }' | python3 -m json.tool
# Expected: 200 { "message": "If the email exists, a reset link has been sent" }

# Edge case: Missing email
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/forgot-password" \
  -H "Content-Type: application/json" \
  -d '{}'
# Expected: 400

# ── 1.6 POST /auth/reset-password ────────────────────────────
# NOTE: You need the raw token from the forgot-password flow (logged server-side).
# Check app logs for: PASSWORD_RESET_TOKEN_CREATED userId=... token=<RAW_TOKEN>

# Happy path (replace <RAW_TOKEN> from server logs)
curl -s -X POST "$BASE/auth/reset-password" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "<RAW_TOKEN>",
    "newPassword": "NewPass123"
  }' | python3 -m json.tool
# Expected: 200 { "message": "Password reset successful" }

# Edge case: Reuse same token
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/reset-password" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "<RAW_TOKEN>",
    "newPassword": "Another123"
  }'
# Expected: 400 { "error": "Invalid or expired reset token" }

# Edge case: Weak new password
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/reset-password" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "some-token",
    "newPassword": "weak"
  }'
# Expected: 400

# Edge case: Fake token
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/reset-password" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "fake-token-12345",
    "newPassword": "Valid1234"
  }'
# Expected: 400

# ── 1.7 GET /auth/me ─────────────────────────────────────────

# Happy path: Get own profile (use any valid token)
curl -s -X GET "$BASE/auth/me" \
  -H "Authorization: Bearer <MASTER_TOKEN>" | python3 -m json.tool
# Expected: 200 {
#   "userId": "<uuid>",
#   "name": "Test Master",
#   "email": "master@test.com",
#   "role": "MASTER",
#   "status": "ACTIVE",
#   "phone": "9876543210",
#   "twoFactorEnabled": false,
#   "createdAt": "..."
# }

# Edge case: No auth header
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X GET "$BASE/auth/me"
# Expected: 401

# Edge case: Invalid/expired token
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X GET "$BASE/auth/me" \
  -H "Authorization: Bearer invalid.jwt.token"
# Expected: 401

# ── 1.8 PUT /auth/me ─────────────────────────────────────────

# Happy path: Update name and phone
curl -s -X PUT "$BASE/auth/me" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{
    "name": "Updated Master",
    "phone": "1111111111"
  }' | python3 -m json.tool
# Expected: 200 { updated user object with new name/phone }

# Happy path: Change password
curl -s -X PUT "$BASE/auth/me" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{
    "currentPassword": "Pass1234",
    "newPassword": "NewPass999"
  }' | python3 -m json.tool
# Expected: 200

# Edge case: Wrong current password
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X PUT "$BASE/auth/me" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{
    "currentPassword": "WrongOldPass1",
    "newPassword": "NewPass888"
  }'
# Expected: 400 { "error": "Current password is incorrect" }

# Edge case: Weak new password
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X PUT "$BASE/auth/me" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{
    "currentPassword": "NewPass999",
    "newPassword": "weak"
  }'
# Expected: 400

# Edge case: No auth
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X PUT "$BASE/auth/me" \
  -H "Content-Type: application/json" \
  -d '{ "name": "Hacker" }'
# Expected: 401


# ── 1.9 POST /auth/2fa/enable ────────────────────────────────

# Happy path: Enable 2FA
curl -s -X POST "$BASE/auth/2fa/enable" \
  -H "Authorization: Bearer <MASTER_TOKEN>" | python3 -m json.tool
# Expected: 200 {
#   "qrCodeUri": "otpauth://totp/Ascentra:master@test.com?secret=...&issuer=Ascentra",
#   "secret": "<base32_secret>"
# }
# >>> SAVE the "secret" value for TOTP generation

# Edge case: No auth
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/2fa/enable"
# Expected: 401

# ── 1.10 POST /auth/2fa/verify ───────────────────────────────
# NOTE: You need a real TOTP code from an authenticator app using the secret above.
# Or use a TOTP generator: python3 -c "import pyotp; print(pyotp.TOTP('<SECRET>').now())"

# Happy path: Verify OTP (activates 2FA)
curl -s -X POST "$BASE/auth/2fa/verify" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{
    "otp": "<6_DIGIT_OTP>"
  }' | python3 -m json.tool
# Expected: 200 { "accessToken": "<jwt>", "message": "2FA enabled and verified" }

# Edge case: Wrong OTP
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/2fa/verify" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{ "otp": "000000" }'
# Expected: 401 { "error": "Invalid OTP" }

# Edge case: No auth
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/2fa/verify" \
  -H "Content-Type: application/json" \
  -d '{ "otp": "123456" }'
# Expected: 401

# ── 1.11 DELETE /auth/2fa/disable ────────────────────────────

# Happy path: Disable 2FA (need current password + valid OTP)
curl -s -X DELETE "$BASE/auth/2fa/disable" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{
    "password": "NewPass999",
    "otp": "<6_DIGIT_OTP>"
  }' | python3 -m json.tool
# Expected: 200 { "message": "2FA disabled successfully" }

# Edge case: Wrong password
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X DELETE "$BASE/auth/2fa/disable" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{
    "password": "WrongPass1",
    "otp": "123456"
  }'
# Expected: 400 { "error": "Invalid password" }

# Edge case: 2FA not enabled
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X DELETE "$BASE/auth/2fa/disable" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <CHILD_TOKEN>" \
  -d '{
    "password": "Pass1234",
    "otp": "123456"
  }'
# Expected: 400 { "error": "2FA is not enabled" }


# ============================================================
# SECTION 2: ADMIN — USER MANAGEMENT (10 endpoints)
# All require: Authorization: Bearer <ADMIN_TOKEN>
# ============================================================

# ── 2.1 GET /admin/users ─────────────────────────────────────

# Happy path: List all users
curl -s -X GET "$BASE/admin/users" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 { "users": [...], "total": N, "page": 1 }

# With role filter
curl -s -X GET "$BASE/admin/users?role=MASTER" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 — only MASTER users

# With status filter
curl -s -X GET "$BASE/admin/users?status=ACTIVE" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 — only ACTIVE users

# With role + status filter
curl -s -X GET "$BASE/admin/users?role=CHILD&status=ACTIVE" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200

# With pagination
curl -s -X GET "$BASE/admin/users?page=1&limit=2" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 — max 2 users in array

# Edge case: Non-admin tries to access
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X GET "$BASE/admin/users" \
  -H "Authorization: Bearer <MASTER_TOKEN>"
# Expected: 403 Forbidden

# Edge case: No auth
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X GET "$BASE/admin/users"
# Expected: 401

# ── 2.2 POST /admin/users/master ─────────────────────────────

# Happy path: Admin creates a master
curl -s -X POST "$BASE/admin/users/master" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{
    "name": "Admin-Created Master",
    "email": "adminmaster@test.com",
    "password": "Pass1234",
    "phone": "5555555555"
  }' | python3 -m json.tool
# Expected: 201 { "userId": "<uuid>", "message": "Master account created" }

# Edge case: Duplicate email
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/admin/users/master" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{
    "name": "Dup Master",
    "email": "adminmaster@test.com",
    "password": "Pass1234"
  }'
# Expected: 409

# Edge case: Non-admin tries
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/admin/users/master" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <CHILD_TOKEN>" \
  -d '{
    "name": "Sneaky",
    "email": "sneaky@test.com",
    "password": "Pass1234"
  }'
# Expected: 403

# ── 2.3 POST /admin/users/child ──────────────────────────────

# Happy path: Admin creates a child
curl -s -X POST "$BASE/admin/users/child" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{
    "name": "Admin-Created Child",
    "email": "adminchild@test.com",
    "password": "Pass1234",
    "phone": "6666666666"
  }' | python3 -m json.tool
# Expected: 201 { "userId": "<uuid>", "message": "Child account created" }
# >>> SAVE userId as ADMIN_CHILD_ID

# Edge case: With assignedMasterId (optional field)
curl -s -X POST "$BASE/admin/users/child" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{
    "name": "Assigned Child",
    "email": "assigned@test.com",
    "password": "Pass1234",
    "assignedMasterId": "<MASTER_ID>"
  }' | python3 -m json.tool
# Expected: 201


# ── 2.4 GET /admin/users/:userId ─────────────────────────────

# Happy path: Get specific user details
curl -s -X GET "$BASE/admin/users/<MASTER_ID>" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 {
#   "userId": "<uuid>",
#   "name": "...",
#   "email": "...",
#   "role": "MASTER",
#   "status": "ACTIVE",
#   "phone": "...",
#   "twoFactorEnabled": false,
#   "createdAt": "..."
# }

# Edge case: Non-existent user
curl -s -w "\nHTTP_STATUS: %{http_code}\n" \
  -X GET "$BASE/admin/users/00000000-0000-0000-0000-000000000000" \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
# Expected: 404 { "error": "User not found" }

# Edge case: Invalid UUID format
curl -s -w "\nHTTP_STATUS: %{http_code}\n" \
  -X GET "$BASE/admin/users/not-a-uuid" \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
# Expected: 400 or 500

# ── 2.5 PUT /admin/users/:userId ─────────────────────────────

# Happy path: Update user name and phone
curl -s -X PUT "$BASE/admin/users/<MASTER_ID>" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{
    "name": "Renamed Master",
    "phone": "9999999999"
  }' | python3 -m json.tool
# Expected: 200 { updated user object }

# Happy path: Update email
curl -s -X PUT "$BASE/admin/users/<CHILD_ID>" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{
    "email": "childnew@test.com"
  }' | python3 -m json.tool
# Expected: 200

# Edge case: Update non-existent user
curl -s -w "\nHTTP_STATUS: %{http_code}\n" \
  -X PUT "$BASE/admin/users/00000000-0000-0000-0000-000000000000" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{ "name": "Ghost" }'
# Expected: 404

# ── 2.6 PATCH /admin/users/:userId/activate ──────────────────

# (First deactivate, then activate — or just call activate on an active user)
curl -s -X PATCH "$BASE/admin/users/<CHILD_ID>/activate" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 { "message": "User activated" }

# Edge case: Non-existent user
curl -s -w "\nHTTP_STATUS: %{http_code}\n" \
  -X PATCH "$BASE/admin/users/00000000-0000-0000-0000-000000000000/activate" \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
# Expected: 404

# ── 2.7 PATCH /admin/users/:userId/deactivate ────────────────

# Happy path: Deactivate a user
curl -s -X PATCH "$BASE/admin/users/<CHILD_ID>/deactivate" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 { "message": "User deactivated" }

# Verify: Deactivated user cannot login
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "childnew@test.com",
    "password": "Pass1234"
  }'
# Expected: 401 (user is INACTIVE)

# Re-activate for further tests
curl -s -X PATCH "$BASE/admin/users/<CHILD_ID>/activate" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200

# ── 2.8 DELETE /admin/users/:userId ──────────────────────────

# First create a throwaway user to delete
curl -s -X POST "$BASE/admin/users/child" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{
    "name": "To Delete",
    "email": "delete@test.com",
    "password": "Pass1234"
  }' | python3 -m json.tool
# >>> SAVE userId as DELETE_ID

# Happy path: Delete user permanently
curl -s -X DELETE "$BASE/admin/users/<DELETE_ID>" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 { "message": "User permanently deleted" }

# Verify: Deleted user is gone
curl -s -w "\nHTTP_STATUS: %{http_code}\n" \
  -X GET "$BASE/admin/users/<DELETE_ID>" \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
# Expected: 404

# Edge case: Delete non-existent user
curl -s -w "\nHTTP_STATUS: %{http_code}\n" \
  -X DELETE "$BASE/admin/users/00000000-0000-0000-0000-000000000000" \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
# Expected: 404

# Edge case: Non-admin tries to delete
curl -s -w "\nHTTP_STATUS: %{http_code}\n" \
  -X DELETE "$BASE/admin/users/<MASTER_ID>" \
  -H "Authorization: Bearer <MASTER_TOKEN>"
# Expected: 403


# ── 2.9 GET /admin/analytics ─────────────────────────────────

# Happy path
curl -s -X GET "$BASE/admin/analytics" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 {
#   "totalUsers": { "admin": 1, "master": N, "child": N },
#   "totalTrades": 0,
#   "totalReplications": 0,
#   "tradeVolume": 0,
#   "activeSubscriptions": 0
# }

# Edge case: Non-admin
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X GET "$BASE/admin/analytics" \
  -H "Authorization: Bearer <CHILD_TOKEN>"
# Expected: 403

# ── 2.10 GET /admin/system-health ────────────────────────────

# Happy path
curl -s -X GET "$BASE/admin/system-health" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 {
#   "cpuUsage": <number>,
#   "memoryUsage": <number>,
#   "avgTradeLatency": 0,
#   "brokerStatus": [],
#   "activeWebSocketConnections": 0
# }

# Edge case: Non-admin
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X GET "$BASE/admin/system-health" \
  -H "Authorization: Bearer <MASTER_TOKEN>"
# Expected: 403

# ============================================================
# SECTION 3: CROSS-CUTTING EDGE CASES
# ============================================================

# ── Expired token ──
# Wait 15+ minutes for access token to expire, then:
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X GET "$BASE/auth/me" \
  -H "Authorization: Bearer <EXPIRED_TOKEN>"
# Expected: 401

# ── Malformed JSON ──
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d 'this is not json'
# Expected: 400

# ── Wrong Content-Type ──
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X POST "$BASE/auth/login" \
  -H "Content-Type: text/plain" \
  -d '{"email":"master@test.com","password":"Pass1234"}'
# Expected: 415 Unsupported Media Type

# ── OPTIONS preflight (CORS) ──
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X OPTIONS "$BASE/auth/login" \
  -H "Origin: http://localhost:3000" \
  -H "Access-Control-Request-Method: POST"
# Expected: 200 or 403 depending on CORS config

# ── Health check (always public) ──
curl -s http://localhost:8081/health | python3 -m json.tool
# Expected: 200 { "status": "UP", "time": "..." }

echo ""
echo "============================================================"
echo "ALL TESTS COMPLETE"
echo "============================================================"


# ============================================================
# SECTION 2 (continued): MISSING ENDPOINTS NOW ADDED
# ============================================================

# ── 2.11 GET /admin/subscriptions ────────────────────────────

# Happy path: List all subscriptions
curl -s -X GET "$BASE/admin/subscriptions" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 { "subscriptions": [...] }

# With masterId filter
curl -s -X GET "$BASE/admin/subscriptions?masterId=<MASTER_ID>" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200

# Edge case: Non-admin
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X GET "$BASE/admin/subscriptions" \
  -H "Authorization: Bearer <CHILD_TOKEN>"
# Expected: 403

# ── 2.12 GET /admin/trade-logs ───────────────────────────────

# Happy path: List all trade logs
curl -s -X GET "$BASE/admin/trade-logs" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200 { "logs": [...] }

# With userId filter
curl -s -X GET "$BASE/admin/trade-logs?userId=<MASTER_ID>" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200

# With status filter
curl -s -X GET "$BASE/admin/trade-logs?status=EXECUTED" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | python3 -m json.tool
# Expected: 200

# Edge case: Non-admin
curl -s -w "\nHTTP_STATUS: %{http_code}\n" -X GET "$BASE/admin/trade-logs" \
  -H "Authorization: Bearer <MASTER_TOKEN>"
# Expected: 403

# ============================================================
# 2FA COMPLETE FLOW (corrected)
# ============================================================
# The 2FA login flow now works like this:
#
# SETUP FLOW (user already logged in):
# 1. POST /auth/2fa/enable → returns { qrCodeUri, secret }
# 2. User scans QR in authenticator app
# 3. POST /auth/2fa/verify with OTP → activates 2FA, returns { accessToken, refreshToken }
#
# LOGIN FLOW (2FA already enabled):
# 1. POST /auth/login → returns { requires2FA: true, accessToken: "<pending_token>", user: {...} }
#    The accessToken here is a SHORT-LIVED (5 min) pending-2FA token
# 2. POST /auth/2fa/verify with the pending token + OTP
#    → returns { accessToken: "<full_token>", refreshToken: "<refresh>", message: "OTP verified" }
#
# DISABLE FLOW:
# 1. DELETE /auth/2fa/disable with { password, otp } → disables 2FA
