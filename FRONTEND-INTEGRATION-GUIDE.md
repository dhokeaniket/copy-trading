# Frontend Integration Guide — Ascentra Copy Trading Platform
## Base URL: `https://copy-trading-production-3981.up.railway.app`
## All endpoints require `Authorization: Bearer <accessToken>` unless marked PUBLIC

---

# SECTION 1: AUTHENTICATION

### POST /api/v1/auth/register (PUBLIC)
```json
Request:  { "name": "John Doe", "email": "john@email.com", "password": "Pass1234", "role": "MASTER", "phone": "9876543210" }
Response 201: { "userId": "uuid", "message": "Registration successful" }
```
Role must be `MASTER` or `CHILD`.

### POST /api/v1/auth/login (PUBLIC)
```json
Request:  { "email": "john@email.com", "password": "Pass1234" }
Response 200:
{
  "accessToken": "jwt (15 min)",
  "refreshToken": "jwt (7 days)",
  "user": { "userId", "name", "email", "role", "status", "phone", "twoFactorEnabled", "createdAt", "brokerAccounts": [] },
  "requires2FA": false
}
```

### POST /api/v1/auth/refresh-token (PUBLIC)
```json
Request:  { "refreshToken": "string" }
Response: { "accessToken": "new jwt", "refreshToken": "new refresh" }
```

### GET /api/v1/auth/me
```json
Response: { "userId", "name", "email", "role", "status", "phone", "twoFactorEnabled", "createdAt" }
```

### PUT /api/v1/auth/me
```json
Request: { "name": "New Name", "phone": "1234567890" }
Response: updated user object
```

---

# SECTION 2: BROKER CONNECTION

## How it works

There are two types of brokers:

**OAuth brokers (Zerodha, Fyers, Upstox):** User clicks "Connect" → popup opens broker login page → user logs in with their own broker account → popup closes → broker connected. No API keys needed from user.

**Secret brokers (Groww):** User enters their own API key + secret from Groww developer portal → calls login → connected.

## Step 1: Get broker list

### GET /api/v1/brokers
```json
Response 200:
{
  "brokers": [
    { "brokerId": "GROWW", "name": "Groww", "requiredFields": ["apiKey", "apiSecret"], "isActive": true, "loginMethod": "secret" },
    { "brokerId": "ZERODHA", "name": "Zerodha", "requiredFields": [], "isActive": true, "loginMethod": "oauth", "loginField": "requestToken" },
    { "brokerId": "FYERS", "name": "Fyers", "requiredFields": [], "isActive": true, "loginMethod": "oauth", "loginField": "authCode" },
    { "brokerId": "UPSTOX", "name": "Upstox", "requiredFields": [], "isActive": true, "loginMethod": "oauth", "loginField": "authCode" }
  ]
}
```

**Frontend logic:**
- If `requiredFields` is empty → show only a "Connect" button (no form)
- If `requiredFields` has items → show input fields for those (masked, type=password)
- Use `loginMethod` to decide the login flow

## Step 2: Link broker account

### POST /api/v1/brokers/accounts

**For OAuth brokers (Zerodha/Fyers/Upstox) — no apiKey needed:**
```json
Request:  { "brokerId": "zerodha", "accountNickname": "My Zerodha" }
Response 201: { "accountId": "uuid", "brokerId": "ZERODHA", "status": "AUTH_REQUIRED" }
```

**For Groww — user provides their own keys:**
```json
Request:  { "brokerId": "groww", "apiKey": "user_key", "apiSecret": "user_secret", "accountNickname": "My Groww" }
Response 201: { "accountId": "uuid", "brokerId": "GROWW", "status": "AUTH_REQUIRED" }
```

Save the `accountId` — you need it for all subsequent calls.

## Step 3: Login to broker

### For Groww (secret-based):
```json
POST /api/v1/brokers/accounts/{accountId}/login
Request:  {}
Response 200: { "status": "SESSION_ACTIVE", "broker": "Groww", "expiresAt": "2026-04-07T06:00:00Z" }
```
Or with TOTP: `{"totpCode": "123456"}`

### For OAuth brokers (Zerodha/Fyers/Upstox):

**Step 3a: Get OAuth URL**
```json
GET /api/v1/brokers/accounts/{accountId}/oauth-url
Response 200:
{
  "broker": "ZERODHA",
  "loginMethod": "oauth",
  "loginField": "requestToken",
  "oauthUrl": "https://kite.zerodha.com/connect/login?v=3&api_key=xxx",
  "message": "Open oauthUrl in browser..."
}
```

**Step 3b: Open popup with oauthUrl**
```javascript
// Frontend code example
const popup = window.open(oauthUrl, 'broker-login', 'width=600,height=700');

// Listen for the callback redirect
const interval = setInterval(() => {
  try {
    const url = popup.location.href;
    if (url.includes('/api/v1/brokers/callback')) {
      const params = new URL(url).searchParams;
      const requestToken = params.get('request_token');  // Zerodha
      const authCode = params.get('auth_code') || params.get('code');  // Fyers/Upstox
      popup.close();
      clearInterval(interval);
      // Call login API with the token
      loginToBroker(accountId, requestToken, authCode);
    }
  } catch (e) { /* cross-origin, keep waiting */ }
}, 500);
```

**Step 3c: Call login with the token from callback**
```json
// Zerodha:
POST /api/v1/brokers/accounts/{accountId}/login
Request: { "requestToken": "token_from_callback" }

// Fyers or Upstox:
POST /api/v1/brokers/accounts/{accountId}/login
Request: { "authCode": "code_from_callback" }

Response 200: { "status": "SESSION_ACTIVE", "broker": "Zerodha", "expiresAt": "2026-04-07T06:00:00Z" }
```

### Backend callback endpoint (PUBLIC, no auth needed):
```
GET /api/v1/brokers/callback?request_token=xxx&status=success  (Zerodha)
GET /api/v1/brokers/callback?auth_code=xxx  (Fyers)
GET /api/v1/brokers/callback?code=xxx  (Upstox)

Response 200:
{
  "message": "Broker OAuth callback received.",
  "broker": "ZERODHA",
  "requestToken": "xxx",
  "loginBody": {"requestToken": "xxx"},
  "status": "success"
}
```

## Step 4: Check connection status

### GET /api/v1/brokers/accounts
```json
Response 200:
{
  "accounts": [
    {
      "accountId": "uuid",
      "brokerId": "ZERODHA",
      "brokerName": "ZERODHA",
      "clientId": "",
      "nickname": "My Zerodha",
      "status": "ACTIVE",
      "sessionActive": true,
      "linkedAt": "2026-04-06T10:00:00Z"
    }
  ]
}
```

**Status values:**
- `AUTH_REQUIRED` → needs login (show "Login" button)
- `ACTIVE` → session live (show green indicator)
- `LINKED` → has token but not verified

**sessionActive:** `true` = connected, `false` = needs re-login

## Step 5: Get margin / positions (after login)

### GET /api/v1/brokers/accounts/{accountId}/margin
```json
Response 200: { "availableMargin": 50000.0, "usedMargin": 10000.0, "totalFunds": 60000.0, "collateral": 0.0 }
```

### GET /api/v1/brokers/accounts/{accountId}/positions
```json
Response 200: { "positions": [...] }
```

### GET /api/v1/brokers/accounts/{accountId}/status
```json
Response 200: { "sessionActive": true, "broker": "ZERODHA", "expiresAt": "2026-04-07T06:00:00Z" }
```

## Other broker endpoints

### PUT /api/v1/brokers/accounts/{accountId} — Update account
```json
Request: { "apiKey": "new", "apiSecret": "new", "accountNickname": "New Name" }
Response: { "message": "Account updated" }
```

### DELETE /api/v1/brokers/accounts/{accountId} — Unlink
```json
Response: { "message": "Account unlinked" }
```

---

# SECTION 3: SUBSCRIPTION & COPY TRADING

## Subscription approval flow

New child → master must approve. Previously approved child can re-subscribe instantly.

```
NEW CHILD subscribes → PENDING_APPROVAL → Master approves → ACTIVE
RETURNING CHILD subscribes → ACTIVE (auto-approved)
MASTER links child directly → ACTIVE (bypasses approval)
```

## Child endpoints (`/api/v1/child`)

### GET /api/v1/child/masters — List available masters
```json
Response: { "masters": [{ "masterId": "uuid", "name": "Master Name", "winRate": 0, "totalTrades": 0, "subscribers": 0 }] }
```

### POST /api/v1/child/subscriptions — Subscribe to master
```json
Request:  { "masterId": "uuid", "brokerAccountId": "uuid", "scalingFactor": 1.5 }

Response (new): { "subscriptionId": 1, "status": "PENDING_APPROVAL", "message": "Waiting for master approval." }
Response (returning): { "subscriptionId": 1, "status": "ACTIVE", "message": "Re-subscribed successfully" }
Response (duplicate): { "error": "Already subscribed or pending approval", "status": 409 }
```

### POST /api/v1/child/subscriptions/bulk — Bulk subscribe
```json
Request: { "masters": [{ "masterId": "uuid1", "brokerAccountId": "uuid" }, { "masterId": "uuid2", "brokerAccountId": "uuid", "scalingFactor": 0.5 }] }
Response: { "results": [{ "masterId": "uuid1", "status": "PENDING_APPROVAL" }, { "masterId": "uuid2", "status": "RE_SUBSCRIBED" }] }
```

### DELETE /api/v1/child/subscriptions/{masterId} — Unsubscribe
```json
Response: { "message": "Unsubscribed" }
```

### POST /api/v1/child/subscriptions/bulk-unsubscribe — Bulk unsubscribe
```json
Request: { "masterIds": ["uuid1", "uuid2"] }
Response: { "results": [{ "masterId": "uuid1", "status": "UNSUBSCRIBED" }, { "masterId": "uuid2", "status": "NOT_FOUND" }] }
```

### GET /api/v1/child/subscriptions — List subscriptions
```json
Response:
{
  "subscriptions": [
    {
      "masterId": "uuid",
      "masterName": "Aniket Master",
      "scalingFactor": 1.5,
      "copyingStatus": "ACTIVE",
      "subscribedAt": "2026-04-06T06:20:52Z",
      "brokerAccountId": "uuid"
    }
  ]
}
```
Note: INACTIVE subscriptions are hidden. Shows ACTIVE, PAUSED, PENDING_APPROVAL, REJECTED only.

### PUT /api/v1/child/scaling — Update scaling
```json
Request: { "masterId": "uuid", "scalingFactor": 2.0 }
Response: { "scalingFactor": 2.0 }
```
Range: 0.01 to 10.0

### POST /api/v1/child/copying/pause
```json
Request: { "masterId": "uuid" }
Response: { "message": "Copying paused" }
```

### POST /api/v1/child/copying/resume
```json
Request: { "masterId": "uuid" }
Response: { "message": "Copying resumed" }
```

### GET /api/v1/child/copied-trades
```json
Response: { "trades": [...] }
```

### GET /api/v1/child/analytics
```json
Response: { "totalPnl": 0, "copiedTrades": 10, "failedReplications": 1, "masterPnlComparison": {} }
```

---

## Master endpoints (`/api/v1/master`)

### GET /api/v1/master/children — List linked children
```json
Response:
{
  "children": [
    { "childId": "uuid", "name": "Child One", "email": "child@email.com", "scalingFactor": 1.0, "copyingStatus": "ACTIVE", "subscribedAt": "2026-04-06T06:20:52Z" }
  ]
}
```

### GET /api/v1/master/children/pending — Pending approval requests
```json
Response:
{
  "pendingApprovals": [
    { "childId": "uuid", "name": "Child Name", "email": "child@email.com", "requestedAt": "2026-04-06T06:10:30Z", "subscriptionId": 13 }
  ]
}
```

### POST /api/v1/master/children/{childId}/approve
```json
Response: { "message": "Child approved" }
```

### POST /api/v1/master/children/{childId}/reject
```json
Response: { "message": "Child rejected" }
```

### POST /api/v1/master/children/{childId}/link — Direct link (bypasses approval)
```json
Request (optional): { "scalingFactor": 1.5 }
Response: { "message": "Child linked successfully" }
```

### POST /api/v1/master/children/bulk-link — Bulk link/approve
```json
Request: { "children": [{ "childId": "uuid1", "scalingFactor": 1.0 }, { "childId": "uuid2" }] }
Response: { "results": [{ "childId": "uuid1", "status": "APPROVED" }, { "childId": "uuid2", "status": "LINKED" }] }
```
Statuses: LINKED, APPROVED, REACTIVATED, ALREADY_LINKED

### DELETE /api/v1/master/children/{childId}/unlink
```json
Response: { "message": "Child unlinked" }
```

### POST /api/v1/master/children/bulk-unlink
```json
Request: { "childIds": ["uuid1", "uuid2"] }
Response: { "results": [{ "childId": "uuid1", "status": "UNLINKED" }] }
```

### POST /api/v1/master/children/{childId}/pause
```json
Response: { "message": "Child copying paused" }
```

### POST /api/v1/master/children/{childId}/resume
```json
Response: { "message": "Child copying resumed" }
```

### PUT /api/v1/master/children/{childId}/scaling
```json
Request: { "scalingFactor": 2.0 }
Response: { "childId": "uuid", "scalingFactor": 2.0 }
```

### POST /api/v1/master/subscribe/{childId} — Master follows child's trades
```json
Request (optional): { "scalingFactor": 1.0 }
Response 201: { "subscriptionId": 5, "message": "Subscribed to child successfully" }
```

### GET /api/v1/master/analytics
```json
Response: { "totalPnl": 0, "winRate": 0, "totalTrades": 15, "totalReplications": 12, "childPerformance": [...] }
```

### GET /api/v1/master/trade-history
```json
Response: { "trades": [...] }
```

---

# SECTION 4: SUBSCRIPTION STATUSES

| Status | Meaning | Who can see |
|--------|---------|-------------|
| PENDING_APPROVAL | New child waiting for master approval | Both |
| ACTIVE | Approved, copying trades | Both |
| PAUSED | Temporarily paused | Both |
| REJECTED | Master rejected | Both |
| INACTIVE | Unsubscribed (hidden from list) | Neither |

---

# SECTION 5: ERROR RESPONSES

All errors:
```json
{ "error": "Error message", "status": 400 }
```

| Code | Meaning |
|------|---------|
| 400 | Bad request |
| 401 | Unauthorized (missing/expired token) |
| 404 | Not found |
| 409 | Conflict (duplicate) |
| 502 | Broker API error |

---

# SECTION 6: ADMIN ENDPOINTS

### GET /api/v1/admin/brokers/accounts?userId=uuid&brokerId=GROWW
```json
Response: { "accounts": [{ "accountId", "userId", "brokerId", "clientId", "status" }] }
```

### GET /api/v1/admin/brokers/status
```json
Response: { "brokers": [{ "brokerId": "GROWW", "apiStatus": "UP", "latencyMs": 45 }, ...] }
```
