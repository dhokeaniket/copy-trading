# Broker Integration API — Frontend Developer Guide
## Base URL: `https://copy-trading-production-3981.up.railway.app`
## Auth: All endpoints require `Authorization: Bearer <accessToken>`

---

# OVERVIEW

All brokers use the **same API endpoints**. The backend handles broker-specific differences internally.

Two login methods exist:
- **secret** (Groww): Just call login with `{}` — backend uses stored API key + secret
- **oauth** (Zerodha, Fyers, Upstox): Frontend opens a browser popup, user logs in, captures a code from redirect URL, sends it to login endpoint

---

# 1. LIST SUPPORTED BROKERS

### GET /api/v1/brokers

```
Response 200:
{
  "brokers": [
    {
      "brokerId": "GROWW",
      "name": "Groww",
      "requiredFields": ["apiKey", "apiSecret", "clientId"],
      "isActive": true,
      "loginMethod": "secret",
      "loginField": null
    },
    {
      "brokerId": "ZERODHA",
      "name": "Zerodha",
      "requiredFields": ["apiKey", "apiSecret", "clientId"],
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "requestToken"
    },
    {
      "brokerId": "FYERS",
      "name": "Fyers",
      "requiredFields": ["apiKey", "apiSecret", "clientId"],
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "authCode"
    },
    {
      "brokerId": "UPSTOX",
      "name": "Upstox",
      "requiredFields": ["apiKey", "apiSecret", "clientId"],
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "authCode"
    }
  ]
}
```

**Frontend logic:** Use `loginMethod` to decide the login flow. Use `loginField` to know which field to send in the login request body.

---

# 2. LINK A BROKER ACCOUNT

### POST /api/v1/brokers/accounts

Same request for ALL brokers. Just change `brokerId`.

```json
Request:
{
  "brokerId": "groww",
  "clientId": "my-client-id",
  "apiKey": "broker-api-key",
  "apiSecret": "broker-api-secret",
  "accountNickname": "My Groww Account"
}

Response 201:
{
  "accountId": "uuid",
  "brokerId": "GROWW",
  "status": "AUTH_REQUIRED"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| brokerId | Yes | One of: `groww`, `zerodha`, `fyers`, `upstox` |
| clientId | No | User's broker client ID |
| apiKey | Yes | API key from broker dashboard |
| apiSecret | Yes | API secret from broker dashboard |
| accountNickname | No | Display name for the account |

---

# 3. GET OAUTH URL (for oauth brokers only)

### GET /api/v1/brokers/accounts/{accountId}/oauth-url

Call this BEFORE login for oauth brokers. Returns the URL to open in browser.

```
Response 200 (Groww — no OAuth needed):
{
  "broker": "GROWW",
  "loginMethod": "secret",
  "message": "No OAuth needed. Call login with empty body {}"
}

Response 200 (Zerodha):
{
  "broker": "ZERODHA",
  "loginMethod": "oauth",
  "loginField": "requestToken",
  "oauthUrl": "https://kite.zerodha.com/connect/login?v=3&api_key=xxx",
  "message": "Open oauthUrl in browser. After login, capture request_token from redirect URL and POST it as {\"requestToken\":\"...\"}"
}

Response 200 (Fyers):
{
  "broker": "FYERS",
  "loginMethod": "oauth",
  "loginField": "authCode",
  "oauthUrl": "https://api-t1.fyers.in/api/v3/generate-authcode?client_id=xxx&redirect_uri=https://localhost&response_type=code&state=ok",
  "message": "Open oauthUrl in browser. After login, capture auth_code from redirect URL and POST it as {\"authCode\":\"...\"}"
}

Response 200 (Upstox):
{
  "broker": "UPSTOX",
  "loginMethod": "oauth",
  "loginField": "authCode",
  "oauthUrl": "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=xxx&redirect_uri=https://localhost",
  "message": "Open oauthUrl in browser. After login, capture code from redirect URL and POST it as {\"authCode\":\"...\"}"
}
```

---

# 4. LOGIN TO BROKER (Create Session)

### POST /api/v1/brokers/accounts/{accountId}/login

Same endpoint for ALL brokers. Body differs based on broker type.

**Groww (secret-based, no browser needed):**
```json
Request:  {}
Response 200: { "status": "SESSION_ACTIVE", "broker": "Groww", "expiresAt": "2026-04-06T19:49:33Z" }
```

**Groww with TOTP (optional):**
```json
Request:  { "totpCode": "123456" }
Response 200: { "status": "SESSION_ACTIVE", "broker": "Groww", "expiresAt": "2026-04-06T19:49:33Z" }
```

**Zerodha (needs requestToken from OAuth):**
```json
Request:  { "requestToken": "abc123xyz" }
Response 200: { "status": "SESSION_ACTIVE", "broker": "Zerodha", "expiresAt": "2026-04-06T19:49:33Z" }
```

**Fyers (needs authCode from OAuth):**
```json
Request:  { "authCode": "xyz789abc" }
Response 200: { "status": "SESSION_ACTIVE", "broker": "Fyers", "expiresAt": "2026-04-06T19:49:33Z" }
```

**Upstox (needs authCode from OAuth):**
```json
Request:  { "authCode": "def456ghi" }
Response 200: { "status": "SESSION_ACTIVE", "broker": "Upstox", "expiresAt": "2026-04-06T19:49:33Z" }
```

**Error (missing auth code):**
```json
Response 400: { "error": "requestToken required. Open this URL to login: https://kite.zerodha.com/connect/login?v=3&api_key=xxx", "status": 400 }
```

**Error (broker API rejected):**
```json
Response 502: { "error": "Zerodha API error: Zerodha 403: Invalid request_token", "status": 502 }
```

---

# 5. FRONTEND LOGIN FLOW (Step by Step)

## Flow A: Groww (secret-based)
```
1. POST /brokers/accounts  → link account with apiKey + apiSecret
2. POST /brokers/accounts/{id}/login  → send {}
3. Done. Session is active.
```

## Flow B: Zerodha / Fyers / Upstox (OAuth)
```
1. POST /brokers/accounts  → link account with apiKey + apiSecret
2. GET /brokers/accounts/{id}/oauth-url  → get oauthUrl
3. Open oauthUrl in popup/new tab
4. User logs in on broker website
5. Broker redirects to redirect_uri with code in URL params:
   - Zerodha: ?request_token=xxx&status=success
   - Fyers: ?auth_code=xxx&state=ok
   - Upstox: ?code=xxx
6. Frontend captures the code from URL
7. POST /brokers/accounts/{id}/login  → send {"requestToken":"xxx"} or {"authCode":"xxx"}
8. Done. Session is active.
```

---

# 6. CHECK SESSION STATUS

### GET /api/v1/brokers/accounts/{accountId}/status

```json
Response 200:
{
  "sessionActive": true,
  "broker": "GROWW",
  "expiresAt": "2026-04-06T19:49:33Z"
}
```

---

# 7. GET MARGIN

### GET /api/v1/brokers/accounts/{accountId}/margin

```json
Response 200 (normalized for all brokers):
{
  "availableMargin": 50000.0,
  "usedMargin": 10000.0,
  "totalFunds": 60000.0,
  "collateral": 0.0
}
```

---

# 8. GET POSITIONS

### GET /api/v1/brokers/accounts/{accountId}/positions

```json
Response 200:
{
  "positions": [ ... ]
}
```

Position data format varies by broker but is returned as-is from the broker API.

---

# 9. LIST USER'S BROKER ACCOUNTS

### GET /api/v1/brokers/accounts

```json
Response 200:
{
  "accounts": [
    {
      "accountId": "uuid",
      "brokerId": "GROWW",
      "brokerName": "GROWW",
      "clientId": "aniket-groww",
      "nickname": "My Groww",
      "status": "ACTIVE",
      "sessionActive": true,
      "linkedAt": "2026-04-05T19:48:42Z"
    },
    {
      "accountId": "uuid",
      "brokerId": "ZERODHA",
      "brokerName": "ZERODHA",
      "clientId": "aniket-zerodha",
      "nickname": "My Zerodha",
      "status": "AUTH_REQUIRED",
      "sessionActive": false,
      "linkedAt": "2026-04-05T19:49:38Z"
    }
  ]
}
```

Status values: `AUTH_REQUIRED` (needs login), `ACTIVE` (session live), `LINKED` (has access token but not verified)

---

# 10. GET SINGLE ACCOUNT

### GET /api/v1/brokers/accounts/{accountId}

```json
Response 200:
{
  "accountId": "uuid",
  "brokerId": "GROWW",
  "brokerName": "GROWW",
  "clientId": "aniket-groww",
  "nickname": "My Groww",
  "status": "ACTIVE",
  "sessionActive": true,
  "linkedAt": "2026-04-05T19:48:42Z"
}
```

---

# 11. UPDATE ACCOUNT

### PUT /api/v1/brokers/accounts/{accountId}

```json
Request:
{
  "apiKey": "new-key",
  "apiSecret": "new-secret",
  "accountNickname": "New Name"
}

Response 200: { "message": "Account updated" }
```

All fields optional. Only provided fields are updated.

---

# 12. DELETE (UNLINK) ACCOUNT

### DELETE /api/v1/brokers/accounts/{accountId}

```json
Response 200: { "message": "Account unlinked" }
```

---

# 13. ADMIN ENDPOINTS

### GET /api/v1/admin/brokers/accounts?userId=uuid&brokerId=GROWW

```json
Response 200:
{
  "accounts": [
    { "accountId": "uuid", "userId": "uuid", "brokerId": "GROWW", "clientId": "xxx", "status": "ACTIVE" }
  ]
}
```

### GET /api/v1/admin/brokers/status

```json
Response 200:
{
  "brokers": [
    { "brokerId": "GROWW", "name": "Groww", "apiStatus": "UP", "latencyMs": 45, "lastChecked": "2026-04-05T20:00:00Z" },
    { "brokerId": "ZERODHA", "name": "Zerodha", "apiStatus": "UP", "latencyMs": 0, "lastChecked": "2026-04-05T20:00:00Z" },
    { "brokerId": "FYERS", "name": "Fyers", "apiStatus": "UP", "latencyMs": 0, "lastChecked": "2026-04-05T20:00:00Z" },
    { "brokerId": "UPSTOX", "name": "Upstox", "apiStatus": "UP", "latencyMs": 0, "lastChecked": "2026-04-05T20:00:00Z" }
  ]
}
```

---

# ERROR RESPONSES

All errors follow this format:
```json
{ "error": "Error message", "status": 400 }
```

| HTTP Code | Meaning |
|-----------|---------|
| 400 | Bad request (missing fields, unsupported broker) |
| 401 | Unauthorized (invalid/expired JWT) |
| 404 | Account not found |
| 502 | Broker API error (login failed, API down) |
| 500 | Internal server error |
