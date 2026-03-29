# Ascentra Trading Platform — API Testing Guide
## Live URL: `https://copy-trading-production-3981.up.railway.app`

Run these curls in order. Each step builds on the previous one — save the tokens and IDs as you go.

---

## 0. Health Check

```bash
curl -s https://copy-trading-production-3981.up.railway.app/health
```
**Expected:** `{"status":"UP","time":"..."}`

---

## SECTION 1: AUTHENTICATION

### 1.1 Register Master

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Rahul Sharma","email":"rahul@trading.com","password":"Trade1234","role":"MASTER","phone":"9876543210"}'
```
**Expected:** `{"userId":"<MASTER_ID>","message":"Registration successful"}`
> Save `<MASTER_ID>`

### 1.2 Register Child

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Priya Patel","email":"priya@trading.com","password":"Trade1234","role":"CHILD","phone":"8765432109"}'
```
> Save `<CHILD_ID>`

### 1.3 Login as Admin (seeded)

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@ascentra.com","password":"Admin@123"}'
```
**Expected:**
```json
{
  "accessToken": "<ADMIN_TOKEN>",
  "refreshToken": "<ADMIN_REFRESH>",
  "user": { "userId":"...", "name":"Platform Admin", "role":"ADMIN", "status":"ACTIVE", ... },
  "requires2FA": false
}
```
> Save `<ADMIN_TOKEN>` and `<ADMIN_REFRESH>`

### 1.4 Login as Master

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"rahul@trading.com","password":"Trade1234"}'
```
> Save `<MASTER_TOKEN>` and `<MASTER_REFRESH>`

### 1.5 Login as Child

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"priya@trading.com","password":"Trade1234"}'
```
> Save `<CHILD_TOKEN>`

### 1.6 Get My Profile

```bash
curl -s https://copy-trading-production-3981.up.railway.app/api/v1/auth/me \
  -H "Authorization: Bearer <MASTER_TOKEN>"
```
**Expected:**
```json
{
  "userId": "...",
  "name": "Rahul Sharma",
  "email": "rahul@trading.com",
  "role": "MASTER",
  "status": "ACTIVE",
  "phone": "9876543210",
  "twoFactorEnabled": false,
  "createdAt": "...",
  "brokerAccounts": []
}
```

### 1.7 Update My Profile

```bash
curl -s -X PUT https://copy-trading-production-3981.up.railway.app/api/v1/auth/me \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{"name":"Rahul S.","phone":"9999999999"}'
```

### 1.8 Change Password

```bash
curl -s -X PUT https://copy-trading-production-3981.up.railway.app/api/v1/auth/me \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{"currentPassword":"Trade1234","newPassword":"NewTrade999"}'
```

### 1.9 Refresh Token

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/refresh-token \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<MASTER_REFRESH>"}'
```
**Expected:** New `accessToken` + new `refreshToken` (old one is revoked)

### 1.10 Forgot Password

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"rahul@trading.com"}'
```
**Expected:** `{"message":"If the email exists, a reset link has been sent"}`
> Check Railway logs for the raw reset token

### 1.11 Reset Password (use token from logs)

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token":"<RAW_TOKEN_FROM_LOGS>","newPassword":"Reset1234"}'
```

### 1.12 Enable 2FA

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/2fa/enable \
  -H "Authorization: Bearer <MASTER_TOKEN>"
```
**Expected:**
```json
{
  "qrCodeUri": "otpauth://totp/Ascentra:rahul@trading.com?secret=...&issuer=Ascentra",
  "secret": "<TOTP_SECRET>"
}
```

### 1.13 Verify 2FA (use authenticator app or python)

Generate OTP: `python3 -c "import pyotp; print(pyotp.TOTP('<TOTP_SECRET>').now())"`

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/2fa/verify \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{"otp":"<6_DIGIT_CODE>"}'
```
**Expected:** `{"accessToken":"...","refreshToken":"...","message":"2FA enabled and verified"}`

### 1.14 Disable 2FA

```bash
curl -s -X DELETE https://copy-trading-production-3981.up.railway.app/api/v1/auth/2fa/disable \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{"password":"Reset1234","otp":"<6_DIGIT_CODE>"}'
```

### 1.15 Logout

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/logout \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <MASTER_TOKEN>" \
  -d '{"refreshToken":"<MASTER_REFRESH>"}'
```
**Expected:** `{"message":"Logged out successfully"}`

---

## SECTION 2: ADMIN — USER MANAGEMENT
> All require `Authorization: Bearer <ADMIN_TOKEN>`

### 2.1 List All Users

```bash
curl -s https://copy-trading-production-3981.up.railway.app/api/v1/admin/users \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```

With filters:
```bash
curl -s "https://copy-trading-production-3981.up.railway.app/api/v1/admin/users?role=MASTER&status=ACTIVE&page=1&limit=10" \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```
**Expected:** `{"users":[...],"total":N,"page":1}`

### 2.2 Admin Creates a Master

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/admin/users/master \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{"name":"Admin Master","email":"adminmaster@trading.com","password":"Pass1234","phone":"7777777777"}'
```
**Expected:** `{"userId":"<NEW_MASTER_ID>","message":"Master account created"}`

### 2.3 Admin Creates a Child

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/admin/users/child \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{"name":"Admin Child","email":"adminchild@trading.com","password":"Pass1234","phone":"6666666666"}'
```
> Save `<ADMIN_CHILD_ID>`

### 2.4 Get User by ID

```bash
curl -s https://copy-trading-production-3981.up.railway.app/api/v1/admin/users/<MASTER_ID> \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```

### 2.5 Update User

```bash
curl -s -X PUT https://copy-trading-production-3981.up.railway.app/api/v1/admin/users/<MASTER_ID> \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{"name":"Rahul Sharma (Verified)","phone":"8888888888"}'
```

### 2.6 Deactivate User

```bash
curl -s -X PATCH https://copy-trading-production-3981.up.railway.app/api/v1/admin/users/<CHILD_ID>/deactivate \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```
**Expected:** `{"message":"User deactivated"}`

Verify deactivated user can't login:
```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"priya@trading.com","password":"Trade1234"}'
```
**Expected:** 401 Unauthorized

### 2.7 Activate User

```bash
curl -s -X PATCH https://copy-trading-production-3981.up.railway.app/api/v1/admin/users/<CHILD_ID>/activate \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```
**Expected:** `{"message":"User activated"}`

### 2.8 Delete User (create throwaway first)

```bash
curl -s -X POST https://copy-trading-production-3981.up.railway.app/api/v1/admin/users/child \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{"name":"To Delete","email":"delete@trading.com","password":"Pass1234"}'
```
> Save `<DELETE_ID>`

```bash
curl -s -X DELETE https://copy-trading-production-3981.up.railway.app/api/v1/admin/users/<DELETE_ID> \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```
**Expected:** `{"message":"User permanently deleted"}`

### 2.9 Platform Analytics

```bash
curl -s https://copy-trading-production-3981.up.railway.app/api/v1/admin/analytics \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```
**Expected:**
```json
{
  "totalUsers": {"admin":1,"master":N,"child":N},
  "totalTrades": 0,
  "totalReplications": 0,
  "tradeVolume": 0,
  "activeSubscriptions": 0
}
```

### 2.10 System Health

```bash
curl -s https://copy-trading-production-3981.up.railway.app/api/v1/admin/system-health \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```
**Expected:**
```json
{
  "cpuUsage": N,
  "memoryUsage": N,
  "avgTradeLatency": 0,
  "brokerStatus": [],
  "activeWebSocketConnections": 0
}
```

### 2.11 List All Subscriptions

```bash
curl -s https://copy-trading-production-3981.up.railway.app/api/v1/admin/subscriptions \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```

### 2.12 View Trade Logs

```bash
curl -s https://copy-trading-production-3981.up.railway.app/api/v1/admin/trade-logs \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```

---

## SECTION 3: EDGE CASES & SECURITY

### Non-admin tries admin endpoint (expect 403)

```bash
curl -s -w "\nHTTP: %{http_code}\n" https://copy-trading-production-3981.up.railway.app/api/v1/admin/users \
  -H "Authorization: Bearer <MASTER_TOKEN>"
```

### No auth header (expect 401)

```bash
curl -s -w "\nHTTP: %{http_code}\n" https://copy-trading-production-3981.up.railway.app/api/v1/auth/me
```

### Invalid token (expect 401)

```bash
curl -s -w "\nHTTP: %{http_code}\n" https://copy-trading-production-3981.up.railway.app/api/v1/auth/me \
  -H "Authorization: Bearer fake.jwt.token"
```

### Duplicate email registration (expect 409)

```bash
curl -s -w "\nHTTP: %{http_code}\n" -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Dup","email":"rahul@trading.com","password":"Pass1234","role":"MASTER"}'
```

### Weak password (expect 400)

```bash
curl -s -w "\nHTTP: %{http_code}\n" -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Weak","email":"weak@test.com","password":"abcdefgh","role":"CHILD"}'
```

### Wrong login password (expect 401)

```bash
curl -s -w "\nHTTP: %{http_code}\n" -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"rahul@trading.com","password":"WrongPass1"}'
```

### Get non-existent user (expect 404)

```bash
curl -s -w "\nHTTP: %{http_code}\n" https://copy-trading-production-3981.up.railway.app/api/v1/admin/users/00000000-0000-0000-0000-000000000000 \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```

### Revoked refresh token (expect 401)

```bash
curl -s -w "\nHTTP: %{http_code}\n" -X POST https://copy-trading-production-3981.up.railway.app/api/v1/auth/refresh-token \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<ALREADY_USED_REFRESH>"}'
```

---

## BUSINESS FLOW: Complete Master-Child Lifecycle

```
1. Admin logs in                    → POST /auth/login
2. Admin creates Master account     → POST /admin/users/master
3. Admin creates Child account      → POST /admin/users/child
4. Master logs in                   → POST /auth/login
5. Master enables 2FA               → POST /auth/2fa/enable + /2fa/verify
6. Child logs in                    → POST /auth/login
7. Admin views all users            → GET /admin/users
8. Admin checks analytics           → GET /admin/analytics
9. Admin checks system health       → GET /admin/system-health
10. Admin deactivates a child       → PATCH /admin/users/:id/deactivate
11. Child can't login anymore       → POST /auth/login → 401
12. Admin reactivates child         → PATCH /admin/users/:id/activate
13. Child logs in again             → POST /auth/login → 200
14. Master changes password         → PUT /auth/me
15. Master logs out                 → POST /auth/logout
```
