# Ascentra API — Copy-Paste Curl Commands
## Base URL: `http://localhost:8081/api/v1`

---

## STEP 0: Health Check

```bash
curl -s http://localhost:8081/health | python3 -m json.tool
```

---

## SECTION 1: AUTHENTICATION

### 1.1 Register

**Register MASTER:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Master","email":"master@test.com","password":"Pass1234","role":"MASTER","phone":"9876543210"}' | python3 -m json.tool
```
> Copy the `userId` → this is your **MASTER_ID**

**Register CHILD:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Child","email":"child@test.com","password":"Pass1234","role":"CHILD","phone":"1234567890"}' | python3 -m json.tool
```
> Copy the `userId` → this is your **CHILD_ID**

**Edge — Duplicate email:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Dup","email":"master@test.com","password":"Pass1234","role":"MASTER"}'
```

**Edge — Weak password:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Weak","email":"weak@test.com","password":"abcdefgh","role":"CHILD"}'
```

**Edge — Missing fields:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"NoEmail"}'
```

**Edge — Invalid role:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Bad","email":"bad@test.com","password":"Pass1234","role":"SUPERADMIN"}'
```

---

### 1.2 Login

**Login ADMIN (seeded):**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@ascentra.com","password":"Admin@123"}' | python3 -m json.tool
```
> Copy `accessToken` → **ADMIN_TOKEN**
> Copy `refreshToken` → **ADMIN_REFRESH**

**Login MASTER:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"master@test.com","password":"Pass1234"}' | python3 -m json.tool
```
> Copy `accessToken` → **MASTER_TOKEN**
> Copy `refreshToken` → **MASTER_REFRESH**

**Login CHILD:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"child@test.com","password":"Pass1234"}' | python3 -m json.tool
```
> Copy `accessToken` → **CHILD_TOKEN**

**Edge — Wrong password:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"master@test.com","password":"WrongPass1"}'
```

**Edge — Non-existent email:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"nobody@test.com","password":"Pass1234"}'
```

---

### 1.3 Logout

```bash
curl -s -X POST http://localhost:8081/api/v1/auth/logout \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer MASTER_TOKEN" \
  -d '{"refreshToken":"MASTER_REFRESH"}' | python3 -m json.tool
```

**Edge — Missing refreshToken:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/logout \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer MASTER_TOKEN" \
  -d '{}'
```

**Edge — No auth header:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/logout \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"something"}'
```

---

### 1.4 Refresh Token

> After logout, login again to get a fresh refresh token first.

```bash
curl -s -X POST http://localhost:8081/api/v1/auth/refresh-token \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"ADMIN_REFRESH"}' | python3 -m json.tool
```
> Old refresh token is now REVOKED. Save the new ones.

**Edge — Reuse revoked token:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/refresh-token \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"ADMIN_REFRESH_OLD"}'
```

**Edge — Garbage token:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/refresh-token \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"not-a-real-token"}'
```

---

### 1.5 Forgot Password

```bash
curl -s -X POST http://localhost:8081/api/v1/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"master@test.com"}' | python3 -m json.tool
```
> Check server logs for: `PASSWORD_RESET_TOKEN_CREATED userId=... token=<RAW_TOKEN>`

**Edge — Non-existent email (same response):**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"nobody@nowhere.com"}' | python3 -m json.tool
```

---

### 1.6 Reset Password

> Replace RAW_TOKEN with the token from server logs.

```bash
curl -s -X POST http://localhost:8081/api/v1/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token":"RAW_TOKEN","newPassword":"NewPass123"}' | python3 -m json.tool
```

**Edge — Reuse same token:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token":"RAW_TOKEN","newPassword":"Another123"}'
```

**Edge — Weak new password:**
```bash
curl -s -X POST http://localhost:8081/api/v1/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token":"any-token","newPassword":"weak"}'
```

---

### 1.7 Get My Profile

```bash
curl -s -X GET http://localhost:8081/api/v1/auth/me \
  -H "Authorization: Bearer MASTER_TOKEN" | python3 -m json.tool
```

**Edge — No auth:**
```bash
curl -s http://localhost:8081/api/v1/auth/me
```

**Edge — Bad token:**
```bash
curl -s http://localhost:8081/api/v1/auth/me \
  -H "Authorization: Bearer invalid.jwt.token"
```

---

### 1.8 Update My Profile

**Update name + phone:**
```bash
curl -s -X PUT http://localhost:8081/api/v1/auth/me \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer MASTER_TOKEN" \
  -d '{"name":"Updated Master","phone":"1111111111"}' | python3 -m json.tool
```

**Change password:**
```bash
curl -s -X PUT http://localhost:8081/api/v1/auth/me \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer MASTER_TOKEN" \
  -d '{"currentPassword":"Pass1234","newPassword":"NewPass999"}' | python3 -m json.tool
```

**Edge — Wrong current password:**
```bash
curl -s -X PUT http://localhost:8081/api/v1/auth/me \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer MASTER_TOKEN" \
  -d '{"currentPassword":"WrongOld1","newPassword":"NewPass888"}'
```

---
