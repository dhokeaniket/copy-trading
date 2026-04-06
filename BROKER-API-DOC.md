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


---

# SUBSCRIPTION ENDPOINTS (Master ↔ Child)

---

## MASTER ENDPOINTS (`/api/v1/master`)

### POST /api/v1/master/children/{childId}/link — Link a child
```json
Request (optional):  { "scalingFactor": 1.5 }
Response 200:        { "message": "Child linked successfully" }
```

### POST /api/v1/master/children/bulk-link — Bulk link multiple children
```json
Request:
{
  "children": [
    { "childId": "uuid-1", "scalingFactor": 1.0 },
    { "childId": "uuid-2", "scalingFactor": 0.5 },
    { "childId": "uuid-3" }
  ]
}

Response 200:
{
  "results": [
    { "childId": "uuid-1", "status": "LINKED", "subscriptionId": 1 },
    { "childId": "uuid-2", "status": "LINKED", "subscriptionId": 2 },
    { "childId": "uuid-3", "status": "ALREADY_LINKED" }
  ]
}
```

### POST /api/v1/master/subscribe/{childId} — Master subscribes to a child (follows child's trades)
```json
Request (optional):  { "scalingFactor": 1.0 }
Response 201:        { "subscriptionId": 5, "message": "Subscribed to child successfully" }
```
This reverses the normal flow — the master copies the child's trades.

### DELETE /api/v1/master/children/{childId}/unlink — Unlink a child
```json
Response 200: { "message": "Child unlinked" }
```

### GET /api/v1/master/children — List linked children
```json
Response 200:
{
  "children": [
    {
      "childId": "uuid",
      "name": "Child Name",
      "email": "child@email.com",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "subscribedAt": "2026-04-05T20:00:00Z"
    }
  ]
}
```

### GET /api/v1/master/children/{childId}/scaling — Get child's scaling
```json
Response 200: { "childId": "uuid", "scalingFactor": 1.5 }
```

### PUT /api/v1/master/children/{childId}/scaling — Update child's scaling
```json
Request:  { "scalingFactor": 2.0 }
Response 200: { "childId": "uuid", "scalingFactor": 2.0 }
```

### GET /api/v1/master/analytics
```json
Response 200:
{
  "totalPnl": 0,
  "winRate": 0,
  "totalTrades": 15,
  "totalReplications": 12,
  "childPerformance": [
    { "childId": "uuid", "scalingFactor": 1.0, "copyingStatus": "ACTIVE" }
  ]
}
```

### GET /api/v1/master/trade-history
```json
Response 200: { "trades": [ ... ] }
```

---

## CHILD ENDPOINTS (`/api/v1/child`)

### GET /api/v1/child/masters — List available masters
```json
Response 200:
{
  "masters": [
    { "masterId": "uuid", "name": "Master Name", "winRate": 0, "totalTrades": 0, "avgPnl": 0, "subscribers": 0 }
  ]
}
```

### POST /api/v1/child/subscriptions — Subscribe to a master
```json
Request:  { "masterId": "uuid", "brokerAccountId": "uuid" }
Response 201: { "subscriptionId": 1, "message": "Subscribed successfully" }
```

### POST /api/v1/child/subscriptions/bulk — Bulk subscribe to multiple masters
```json
Request:
{
  "masters": [
    { "masterId": "uuid-1", "brokerAccountId": "uuid-broker", "scalingFactor": 1.0 },
    { "masterId": "uuid-2", "brokerAccountId": "uuid-broker" },
    { "masterId": "uuid-3", "brokerAccountId": "uuid-broker", "scalingFactor": 0.5 }
  ]
}

Response 201:
{
  "results": [
    { "masterId": "uuid-1", "status": "SUBSCRIBED", "subscriptionId": 1 },
    { "masterId": "uuid-2", "status": "SUBSCRIBED", "subscriptionId": 2 },
    { "masterId": "uuid-3", "status": "ALREADY_SUBSCRIBED" }
  ]
}
```

### DELETE /api/v1/child/subscriptions/{masterId} — Unsubscribe
```json
Response 200: { "message": "Unsubscribed" }
```

### GET /api/v1/child/subscriptions — List subscriptions
```json
Response 200:
{
  "subscriptions": [
    {
      "masterId": "uuid",
      "masterName": "Master Name",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "subscribedAt": "2026-04-05T20:00:00Z",
      "brokerAccountId": "uuid"
    }
  ]
}
```

### GET /api/v1/child/scaling?masterId=uuid — Get scaling factor
```json
Response 200: { "scalingFactor": 1.5 }
```

### PUT /api/v1/child/scaling — Update scaling factor
```json
Request:  { "masterId": "uuid", "scalingFactor": 2.0 }
Response 200: { "scalingFactor": 2.0 }
```
Scaling factor range: 0.01 to 10.0

### POST /api/v1/child/copying/pause — Pause copying
```json
Request:  { "masterId": "uuid" }
Response 200: { "message": "Copying paused" }
```

### POST /api/v1/child/copying/resume — Resume copying
```json
Request:  { "masterId": "uuid" }
Response 200: { "message": "Copying resumed" }
```

### GET /api/v1/child/copied-trades
```json
Response 200: { "trades": [ ... ] }
```

### GET /api/v1/child/analytics
```json
Response 200:
{
  "totalPnl": 0,
  "copiedTrades": 10,
  "failedReplications": 1,
  "masterPnlComparison": {}
}
```


---

# SUBSCRIPTION APPROVAL SYSTEM

New child subscribers require master approval. Previously approved children can re-subscribe directly.

## Flow:
```
1. Child calls POST /child/subscriptions → status: PENDING_APPROVAL
2. Master sees it in GET /master/children/pending
3. Master calls POST /master/children/{childId}/approve → status: ACTIVE
   OR POST /master/children/{childId}/reject → status: REJECTED
4. If child unsubscribes and re-subscribes later → status: ACTIVE (auto-approved)
5. Master can also link directly via POST /master/children/{childId}/link → bypasses approval
```

### GET /api/v1/master/children/pending — List pending approval requests
```json
Response 200:
{
  "pendingApprovals": [
    {
      "childId": "uuid",
      "name": "Child Name",
      "email": "child@email.com",
      "requestedAt": "2026-04-05T20:00:00Z",
      "subscriptionId": 1
    }
  ]
}
```

### POST /api/v1/master/children/{childId}/approve — Approve child
```json
Response 200: { "message": "Child approved" }
```

### POST /api/v1/master/children/{childId}/reject — Reject child
```json
Response 200: { "message": "Child rejected" }
```

### POST /api/v1/child/subscriptions — Subscribe (with approval)
```json
Request:  { "masterId": "uuid", "brokerAccountId": "uuid" }

Response 201 (new child):
{ "subscriptionId": 1, "status": "PENDING_APPROVAL", "message": "Subscription request sent. Waiting for master approval." }

Response 201 (previously approved child re-subscribing):
{ "subscriptionId": 1, "status": "ACTIVE", "message": "Re-subscribed successfully (previously approved)" }

Response 409 (already subscribed/pending):
{ "error": "Already subscribed or pending approval", "status": 409 }
```

| Status | Meaning |
|--------|---------|
| PENDING_APPROVAL | Child requested, waiting for master to approve |
| ACTIVE | Approved and copying trades |
| PAUSED | Temporarily paused by child |
| REJECTED | Master rejected the request |
| INACTIVE | Subscription deactivated |
