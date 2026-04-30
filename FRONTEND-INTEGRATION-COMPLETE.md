# Ascentra Copy Trading — Frontend Integration Guide

**Base URL:** `http://13.53.246.13:8081` (EC2) or `https://api.ascentracapital.com`
**Auth:** All endpoints (except login/register) need `Authorization: Bearer <accessToken>`
**Content-Type:** `application/json` for POST/PUT

---

## How the Platform Works

There are 3 user roles:
- **MASTER** — Places trades on their broker. Trades get auto-copied to all linked children.
- **CHILD** — Subscribes to a master. When master trades, the same trade is placed on child's broker (scaled by scaling factor).
- **ADMIN** — Manages users, views analytics, system health.

### Copy Trading Flow (How it works end-to-end)

```
Master places trade on Groww/Zerodha/etc
        ↓
Poller detects new order (every 1 second)
        ↓
For each ACTIVE child subscription:
  → Scale quantity (masterQty × scalingFactor)
  → Translate symbol to child's broker format
  → Place order on child's broker
  → Log result + notify child
```

### Stocks vs F&O

Both work automatically. The system detects F&O symbols by pattern (ends with CE/PE + numbers).

- **Equity:** `RELIANCE`, `SBIN`, `TCS` → product: `CNC` (delivery) or `MIS` (intraday)
- **F&O:** `NIFTY25JUN25000CE`, `BANKNIFTY25JUN48000PE` → product: `NRML` (carry forward) or `MIS` (intraday)

The backend handles symbol translation between brokers automatically:
- Groww: `RELIANCE` / `NIFTY25625000CE`
- Zerodha: `RELIANCE` / `NIFTY25JUN25000CE`
- Fyers: `NSE:RELIANCE-EQ` / `NSE:NIFTY25JUN25000CE`
- Upstox: `NSE_EQ|INE002A01018` (ISIN from instrument master)
- Dhan: securityId `2885` (numeric from instrument master)
- Angel One: `RELIANCE-EQ` + symboltoken `2885`

---

## Page-by-Page Integration

### Page 1: Login / Register

**Register:**
```
POST /api/v1/auth/register
Body: { "name": "John", "email": "john@example.com", "password": "pass1234", "role": "CHILD", "phone": "+919876543210" }
Response: { "userId": "uuid", "message": "Registration successful" }
```

**Login (Email):**
```
POST /api/v1/auth/login
Body: { "email": "john@example.com", "password": "pass1234" }
Response: { "accessToken": "eyJ...", "refreshToken": "eyJ...", "user": {...}, "requires2FA": false }
```
Store `accessToken` in memory/state, `refreshToken` in secure storage.
If `requires2FA: true` → show OTP input, call `/auth/2fa/verify` with the temporary accessToken.

**Login (Phone OTP):**
```
POST /api/v1/auth/send-otp → { "phone": "+919876543210" }
POST /api/v1/auth/verify-otp → { "phone": "+919876543210", "otp": "123456" }
Response: { "success": true, "data": { "accessToken": "...", "refreshToken": "...", "user": {...} } }
```

**Token Refresh (call when accessToken expires — 15 min):**
```
POST /api/v1/auth/refresh-token
Body: { "refreshToken": "eyJ..." }
Response: { "accessToken": "new...", "refreshToken": "new..." }
```

---

### Page 2: Broker Accounts (All Roles)

This is where users connect their demat/trading accounts.

**Step 1: Show available brokers**
```
GET /api/v1/brokers
Response: { "brokers": [
  { "brokerId": "GROWW", "name": "Groww", "isActive": true, "loginMethod": "token" },
  { "brokerId": "ZERODHA", "name": "Zerodha", "isActive": true, "loginMethod": "oauth", "loginField": "requestToken" },
  { "brokerId": "FYERS", "name": "Fyers", ... },
  { "brokerId": "UPSTOX", "name": "Upstox", ... },
  { "brokerId": "DHAN", "name": "Dhan", ... },
  { "brokerId": "ANGELONE", "name": "Angel One", "loginMethod": "totp", "loginField": "totpCode" }
]}
```

**Step 2: Link a broker account**
```
POST /api/v1/brokers/accounts
Body: {
  "brokerId": "GROWW",
  "apiKey": "user-groww-api-key",
  "apiSecret": "user-groww-api-secret",
  "accountNickname": "My Groww Account"
}
Response: { "accountId": "uuid", "brokerId": "GROWW", "status": "AUTH_REQUIRED" }
```

For each broker, what to send:
| Broker | Required Fields |
|---|---|
| Groww | `apiKey`, `apiSecret` (from Groww developer portal) |
| Zerodha | just `accountNickname` (platform has keys) |
| Fyers | just `accountNickname` |
| Upstox | just `accountNickname` |
| Dhan | `clientId` (Dhan client ID like "1000000003"), `accountNickname` |
| Angel One | `clientId` (Angel client code), `apiSecret` (password), `accountNickname` |

**Step 3: Login to broker (create session)**
```
POST /api/v1/brokers/accounts/{accountId}/login
```

Body varies by broker:
| Broker | Login Body | How it works |
|---|---|---|
| Groww | `{}` (empty) | Uses stored apiKey+apiSecret to auto-generate token |
| Zerodha | `{ "requestToken": "xxx" }` | User opens OAuth URL in browser, gets requestToken from redirect |
| Fyers | `{ "authCode": "xxx" }` | Same OAuth flow |
| Upstox | `{ "authCode": "xxx" }` | Same OAuth flow |
| Dhan | `{}` first → returns `loginUrl`, user opens it, gets `tokenId` → call again with `{ "authCode": "tokenId" }` |
| Angel One | `{ "totpCode": "123456" }` | User enters TOTP from authenticator app |

**For OAuth brokers (Zerodha, Fyers, Upstox):**
1. Call `GET /api/v1/brokers/accounts/{accountId}/oauth-url` → get `oauthUrl`
2. Open `oauthUrl` in browser/webview
3. After user logs in, broker redirects to callback URL with token
4. Callback URL: `GET /api/v1/brokers/callback?request_token=xxx` (or `auth_code=xxx` or `code=xxx`)
5. Extract the token from callback response
6. Call `POST /api/v1/brokers/accounts/{accountId}/login` with the token

**Step 4: Check connection**
```
GET /api/v1/brokers/accounts/{accountId}/status
Response: { "sessionActive": true, "connectionHealth": "good", "expiresAt": "..." }
```

**Step 5: View dashboard (all-in-one)**
```
GET /api/v1/brokers/accounts/{accountId}/dashboard
Response: { "margin": {...}, "positions": [...], "holdings": [...], "orders": [...], "signal": { "bars": 4, "color": "green" } }
```

---

### Page 3: Master Dashboard

**APIs to call on load:**
```
GET /api/v1/master/analytics → { totalPnl, winRate, totalTrades, totalFollowers, ... }
GET /api/v1/master/children → { children: [{ childId, name, email, scalingFactor, copyingStatus }] }
GET /api/v1/master/active-account → { brokerAccountId: "uuid" }
GET /api/v1/engine/status → { engineStatus, pollingEnabled, supportedBrokers }
```

**Set active account (REQUIRED for auto-copy to work):**
```
POST /api/v1/master/active-account
Body: { "brokerAccountId": "uuid-of-masters-broker-account" }
```
This tells the poller which broker to monitor. Without this, auto-copy won't work.

**Manual copy trade (master can also trigger manually):**
```
POST /api/v1/engine/copy-trade
Body: {
  "symbol": "RELIANCE",    // or "NIFTY25JUN25000CE" for F&O
  "qty": 10,
  "side": "BUY",           // BUY or SELL
  "product": "CNC",        // CNC (delivery), MIS (intraday), NRML (F&O)
  "orderType": "MARKET",   // MARKET or LIMIT
  "price": 0,              // 0 for MARKET, actual price for LIMIT
  "exchange": "NSE"
}
Response: {
  "message": "Trade copy completed",
  "childrenTotal": 3, "success": 2, "failed": 1,
  "results": [
    { "childId": "uuid", "status": "SUCCESS", "message": "Order placed: GMK...", "broker": "GROWW", "scaledQty": 10 },
    { "childId": "uuid", "status": "FAILED", "message": "Order failed: 401 Unauthorized", "broker": "GROWW", "scaledQty": 5 }
  ]
}
```

**Link a child (master-initiated, bypasses approval):**
```
POST /api/v1/master/children/{childId}/link
Body: { "scalingFactor": 1.0 }
```

**Approve/Reject pending child requests:**
```
GET /api/v1/master/children/pending → { pendingApprovals: [...] }
POST /api/v1/master/children/{childId}/approve
POST /api/v1/master/children/{childId}/reject
```

**Pause/Resume a child:**
```
POST /api/v1/master/children/{childId}/pause
POST /api/v1/master/children/{childId}/resume
```

**Update scaling factor:**
```
PUT /api/v1/master/children/{childId}/scaling
Body: { "scalingFactor": 1.5 }
```
scalingFactor 1.0 = same qty, 0.5 = half, 2.0 = double. Range: 0.01 to 10.0.

---

### Page 4: Child Dashboard

**APIs to call on load:**
```
GET /api/v1/child/analytics → { totalPnl, copiedTrades, failedReplications, ... }
GET /api/v1/child/subscriptions → { subscriptions: [{ masterId, masterName, scalingFactor, copyingStatus, brokerAccountId }] }
GET /api/v1/child/copied-trades → { trades: [...] }
```

**Browse available masters:**
```
GET /api/v1/child/masters → { masters: [{ masterId, name, winRate, subscribers, ... }] }
```

**Subscribe to a master:**
```
POST /api/v1/child/subscriptions
Body: {
  "masterId": "uuid",
  "brokerAccountId": "uuid",   // REQUIRED — which broker account to use for copy trades
  "scalingFactor": 1.0
}
Response: { "subscriptionId": 1, "status": "PENDING_APPROVAL", "message": "Waiting for master approval" }
```
New subscriptions need master approval. Previously approved children get auto-activated.


**How child changes broker account:**

The child can unsubscribe and re-subscribe with a different broker account:
```
1. DELETE /api/v1/child/subscriptions/{masterId}     → unsubscribe
2. POST /api/v1/child/subscriptions                  → re-subscribe with new brokerAccountId
   Body: { "masterId": "uuid", "brokerAccountId": "new-broker-uuid", "scalingFactor": 1.0 }
```
Since the child was previously approved (`approvedOnce: true`), re-subscribing goes directly to ACTIVE — no need for master approval again.

Or the child can link multiple broker accounts and choose which one to use per master.

**Pause/Resume copying:**
```
POST /api/v1/child/copying/pause → Body: { "masterId": "uuid" }
POST /api/v1/child/copying/resume → Body: { "masterId": "uuid" }
```

**Update scaling:**
```
PUT /api/v1/child/scaling
Body: { "masterId": "uuid", "scalingFactor": 0.5 }
```

---

### Page 5: Profile / Settings

```
GET /api/v1/auth/me → user profile
PUT /api/v1/auth/me → { "name": "New Name", "phone": "+91...", "currentPassword": "old", "newPassword": "new123" }
```

**2FA Setup:**
```
POST /api/v1/auth/2fa/enable → { "qrCodeUri": "otpauth://...", "secret": "BASE32" }
POST /api/v1/auth/2fa/verify → { "otp": "123456" } → activates 2FA
DELETE /api/v1/auth/2fa/disable → { "password": "mypass", "otp": "123456" }
```

---

### Page 6: Admin Panel

```
GET /api/v1/admin/users?role=MASTER&status=ACTIVE&page=1&limit=20
POST /api/v1/admin/users/master → { "name": "...", "email": "...", "password": "...", "phone": "..." }
POST /api/v1/admin/users/child → same
GET /api/v1/admin/users/{userId}
PUT /api/v1/admin/users/{userId} → { "name": "...", "email": "...", "phone": "..." }
PATCH /api/v1/admin/users/{userId}/activate
PATCH /api/v1/admin/users/{userId}/deactivate
DELETE /api/v1/admin/users/{userId}
GET /api/v1/admin/analytics
GET /api/v1/admin/system-health
GET /api/v1/admin/subscriptions?masterId=uuid
GET /api/v1/admin/trade-logs?userId=uuid
GET /api/v1/admin/brokers/accounts?userId=uuid&brokerId=GROWW
GET /api/v1/admin/brokers/status
```

---

## Important Flows

### Flow 1: Complete Setup (New Child)

```
1. Register as CHILD → POST /auth/register
2. Login → POST /auth/login
3. Link broker → POST /brokers/accounts { brokerId: "GROWW", apiKey: "...", apiSecret: "..." }
4. Login to broker → POST /brokers/accounts/{id}/login {}
5. Browse masters → GET /child/masters
6. Subscribe → POST /child/subscriptions { masterId: "uuid", brokerAccountId: "uuid" }
7. Wait for master approval (or master links directly)
8. Done — trades will auto-copy when master trades
```

### Flow 2: Complete Setup (New Master)

```
1. Register as MASTER → POST /auth/register
2. Login → POST /auth/login
3. Link broker → POST /brokers/accounts { brokerId: "GROWW", apiKey: "...", apiSecret: "..." }
4. Login to broker → POST /brokers/accounts/{id}/login {}
5. Set active account → POST /master/active-account { brokerAccountId: "uuid" }
6. Now when master trades on Groww, poller detects it and copies to all children
```

### Flow 3: Child Switches Broker

```
1. Link new broker → POST /brokers/accounts { brokerId: "ZERODHA", accountNickname: "My Zerodha" }
2. Login to new broker → POST /brokers/accounts/{newId}/login { requestToken: "..." }
3. Unsubscribe from master → DELETE /child/subscriptions/{masterId}
4. Re-subscribe with new broker → POST /child/subscriptions { masterId: "uuid", brokerAccountId: "newId" }
   → Goes directly to ACTIVE (previously approved)
```

### Flow 4: F&O Copy Trading

Same as equity, just different symbol format and product type:
```
POST /engine/copy-trade
Body: {
  "symbol": "NIFTY25JUN25000CE",   // F&O symbol
  "qty": 50,                        // lot size
  "side": "BUY",
  "product": "NRML",               // NRML for F&O carry forward, MIS for intraday
  "orderType": "MARKET",
  "price": 0
}
```
The backend auto-detects F&O (symbol ends with CE/PE), sets correct exchange (NFO), and translates symbol format per broker.

### Flow 5: Polling (Auto-Copy)

When master has set their active account and polling is enabled:
1. Poller checks master's broker orders every 1 second
2. Detects new COMPLETE/EXECUTED/TRADED orders
3. Extracts symbol, qty, side, product, price from the order
4. Calls `copyTrade()` which places orders on all active children's brokers
5. Each child gets scaled quantity: `childQty = masterQty × scalingFactor`

No manual intervention needed — master just trades normally on their broker app.

---

## Error Handling

All errors follow this pattern:
```json
{ "error": "Error message", "status": 400 }
```

Session expired errors include action hint:
```json
{
  "error": "Session expired. Please re-login to Groww to continue.",
  "errorCode": "SESSION_EXPIRED",
  "action": "RE_LOGIN",
  "accountId": "uuid",
  "brokerId": "GROWW",
  "brokerName": "Groww"
}
```
When FE sees `errorCode: "SESSION_EXPIRED"`, show a "Re-login" button that calls the broker login flow.

---

## WebSocket (Real-time Updates)

```
ws://13.53.246.13:8081/ws/trades
```
Events pushed:
- `TRADE_DETECTED` — master's new order detected by poller
- `SESSION_EXPIRED` — child's broker session expired

---

## Supported Brokers Summary

| Broker | Equity | F&O | Login | IP Whitelist |
|---|---|---|---|---|
| Groww | ✅ | ✅ | API Key + Secret | Required (1 IP per app) |
| Zerodha | ✅ | ✅ | OAuth (requestToken) | Not required |
| Fyers | ✅ | ✅ | OAuth (authCode) | Required |
| Upstox | ✅ | ✅ | OAuth (authCode) | Not required |
| Dhan | ✅ | ✅ | OAuth (tokenId) | Required |
| Angel One | ✅ | ✅ | ClientCode + Password + TOTP | Required |

All instrument tokens (Dhan securityId, Upstox ISIN, Angel One symboltoken) are loaded automatically from official instrument master files on server startup.


---

## Complete API Reference (All Request/Response Bodies)

### AUTH APIs

| # | Method | Endpoint | Request Body | Response |
|---|---|---|---|---|
| 1.1 | POST | `/api/v1/auth/register` | `{ "name": "John", "email": "j@e.com", "password": "pass1234", "role": "CHILD", "phone": "+91..." }` | `{ "userId": "uuid", "message": "Registration successful" }` |
| 1.2 | POST | `/api/v1/auth/login` | `{ "email": "j@e.com", "password": "pass1234" }` | `{ "accessToken": "eyJ...", "refreshToken": "eyJ...", "user": { "userId", "name", "email", "role", "status", "phone", "twoFactorEnabled", "createdAt", "brokerAccounts": [] }, "requires2FA": false }` |
| 1.3 | POST | `/api/v1/auth/send-otp` | `{ "phone": "+919876543210" }` | `{ "success": true, "data": { "expiresIn": 300, "retryAfter": 30 }, "message": "OTP sent" }` |
| 1.4 | POST | `/api/v1/auth/verify-otp` | `{ "phone": "+91...", "otp": "123456" }` | `{ "success": true, "data": { "accessToken", "refreshToken", "user": {...} } }` |
| 1.5 | POST | `/api/v1/auth/logout` | `{ "refreshToken": "eyJ..." }` | `{ "message": "Logged out" }` |
| 1.6 | POST | `/api/v1/auth/refresh-token` | `{ "refreshToken": "eyJ..." }` | `{ "accessToken": "new", "refreshToken": "new" }` |
| 1.7 | POST | `/api/v1/auth/forgot-password` | `{ "email": "j@e.com" }` | `{ "message": "If email exists, reset link sent" }` |
| 1.8 | POST | `/api/v1/auth/reset-password` | `{ "token": "uuid", "newPassword": "new1234" }` | `{ "message": "Password reset successful" }` |
| 1.9 | GET | `/api/v1/auth/me` | — | UserDto: `{ userId, name, email, role, status, phone, twoFactorEnabled, createdAt }` |
| 1.10 | PUT | `/api/v1/auth/me` | `{ "name": "New", "phone": "+91...", "currentPassword": "old", "newPassword": "new1234" }` | UserDto |
| 1.11 | POST | `/api/v1/auth/2fa/enable` | — | `{ "qrCodeUri": "otpauth://...", "secret": "BASE32" }` |
| 1.12 | POST | `/api/v1/auth/2fa/verify` | `{ "otp": "123456" }` | `{ "accessToken", "refreshToken", "message": "2FA verified" }` |
| 1.13 | DELETE | `/api/v1/auth/2fa/disable` | `{ "password": "pass", "otp": "123456" }` | `{ "message": "2FA disabled" }` |

### BROKER APIs

| # | Method | Endpoint | Request Body | Response |
|---|---|---|---|---|
| 2.1 | GET | `/api/v1/brokers` | — | `{ "brokers": [{ brokerId, name, isActive, loginMethod, loginField }] }` |
| 2.2 | POST | `/api/v1/brokers/accounts` | `{ "brokerId": "GROWW", "clientId": "", "apiKey": "...", "apiSecret": "...", "accessToken": null, "accountNickname": "My Groww" }` | `{ "accountId": "uuid", "brokerId": "GROWW", "status": "ACTIVE" }` |
| 2.3 | GET | `/api/v1/brokers/accounts` | — | `{ "accounts": [{ accountId, brokerId, brokerName, clientId, nickname, status, sessionActive, linkedAt, margin, pnl, positions, orders }] }` |
| 2.4 | GET | `/api/v1/brokers/accounts/{id}` | — | BrokerAccountDto (same as above single item) |
| 2.5 | PUT | `/api/v1/brokers/accounts/{id}` | `{ "apiKey": "new", "apiSecret": "new", "accountNickname": "new" }` | `{ "message": "Account updated" }` |
| 2.6 | DELETE | `/api/v1/brokers/accounts/{id}` | — | `{ "message": "Account unlinked" }` |
| 2.7 | POST | `/api/v1/brokers/accounts/{id}/login` | Groww: `{}`, Zerodha: `{ "requestToken": "..." }`, Fyers/Upstox: `{ "authCode": "..." }`, Dhan: `{ "authCode": "tokenId" }`, Angel: `{ "totpCode": "123456" }` | `{ "status": "SESSION_ACTIVE", "broker": "Groww", "expiresAt": "..." }` |
| 2.8 | GET | `/api/v1/brokers/accounts/{id}/oauth-url` | — | `{ "broker": "ZERODHA", "oauthUrl": "https://...", "loginField": "requestToken" }` |
| 2.9 | GET | `/api/v1/brokers/accounts/{id}/status` | — | `{ accountId, status, sessionActive, broker, brokerName, connectionHealth, expiresAt }` |
| 2.10 | GET | `/api/v1/brokers/accounts/{id}/test` | — | `{ accountId, connectionHealth, sessionActive, margin, brokerName, message }` |
| 2.11 | GET | `/api/v1/brokers/accounts/{id}/margin` | — | `{ availableMargin, usedMargin, totalFunds, collateral }` |
| 2.12 | GET | `/api/v1/brokers/accounts/{id}/positions` | — | `{ "positions": [...] }` |
| 2.13 | GET | `/api/v1/brokers/accounts/{id}/orders` | — | `{ "orders": [...] }` |
| 2.14 | GET | `/api/v1/brokers/accounts/{id}/trades` | — | `{ "trades": [...] }` |
| 2.15 | GET | `/api/v1/brokers/accounts/{id}/holdings` | — | `{ "holdings": [...] }` |
| 2.16 | GET | `/api/v1/brokers/accounts/{id}/dashboard` | — | `{ accountId, brokerId, brokerName, signal: { bars, color }, balanceAlert: { level, availableMargin }, profile, margin, positions, holdings, orders }` |
| 2.17 | POST | `/api/v1/brokers/accounts/{id}/orders/close-position` | `{ "symbol": "RELIANCE", "qty": 10, "type": "SELL", "product": "MIS" }` | `{ "message": "Position close order placed", "response": {...} }` |
| 2.18 | DELETE | `/api/v1/brokers/accounts/{id}/orders/{orderId}` | — | `{ "message": "Order cancelled", "response": {...} }` |
| 2.19 | GET | `/api/v1/brokers/accounts/{id}/balance-alert` | — | `{ level: "OK"/"LOW"/"WARNING"/"CRITICAL", availableMargin, threshold }` |
| 2.20 | GET | `/api/v1/brokers/accounts/{id}/signal` | — | `{ signal: 4, maxSignal: 4, quality: "excellent", color: "green", latencyMs: 423 }` |
| 2.21 | GET | `/api/v1/brokers/callback` | Query: `?request_token=...` or `?auth_code=...` or `?code=...` or `?tokenId=...` | `{ broker, requestToken/authCode/tokenId, loginBody }` |

### MASTER APIs

| # | Method | Endpoint | Request Body | Response |
|---|---|---|---|---|
| 3.1 | GET | `/api/v1/master/children` | — | `{ "children": [{ childId, name, email, scalingFactor, copyingStatus, subscribedAt }] }` |
| 3.2 | POST | `/api/v1/master/children/{childId}/link` | `{ "scalingFactor": 1.0 }` | `{ "message": "Child linked successfully" }` |
| 3.3 | POST | `/api/v1/master/children/bulk-link` | `{ "children": [{ "childId": "uuid", "scalingFactor": 1.0 }] }` | `{ "results": [{ childId, status }] }` |
| 3.4 | GET | `/api/v1/master/children/pending` | — | `{ "pendingApprovals": [{ childId, name, email, requestedAt }] }` |
| 3.5 | POST | `/api/v1/master/children/{childId}/approve` | — | `{ "message": "Child approved" }` |
| 3.6 | POST | `/api/v1/master/children/{childId}/reject` | — | `{ "message": "Child rejected" }` |
| 3.7 | DELETE | `/api/v1/master/children/{childId}/unlink` | — | `{ "message": "Child unlinked" }` |
| 3.8 | POST | `/api/v1/master/children/{childId}/pause` | — | `{ "message": "Child copying paused" }` |
| 3.9 | POST | `/api/v1/master/children/{childId}/resume` | — | `{ "message": "Child copying resumed" }` |
| 3.10 | GET | `/api/v1/master/children/{childId}/scaling` | — | `{ childId, scalingFactor }` |
| 3.11 | PUT | `/api/v1/master/children/{childId}/scaling` | `{ "scalingFactor": 1.5 }` | `{ childId, scalingFactor }` |
| 3.12 | POST | `/api/v1/master/active-account` | `{ "brokerAccountId": "uuid" }` | `{ brokerAccountId, message }` |
| 3.13 | GET | `/api/v1/master/active-account` | — | `{ "brokerAccountId": "uuid" }` |
| 3.14 | DELETE | `/api/v1/master/active-account` | — | `{ "message": "Active account cleared" }` |
| 3.15 | GET | `/api/v1/master/analytics` | — | `{ totalPnl, winRate, totalTrades, totalReplications, totalFollowers, revenue, performanceChart, childPerformance }` |
| 3.16 | GET | `/api/v1/master/trade-history` | — | `{ "trades": [...] }` |
| 3.17 | GET | `/api/v1/master/copy/logs` | — | `{ "logs": [...] }` |
| 3.18 | GET | `/api/v1/master/earnings` | — | `{ totalEarnings, thisMonth, lastMonth, pendingPayout, monthlyBreakdown }` |
| 3.19 | GET | `/api/v1/master/payouts` | — | `{ "payouts": [], totalPaid }` |

### CHILD APIs

| # | Method | Endpoint | Request Body | Response |
|---|---|---|---|---|
| 4.1 | GET | `/api/v1/child/masters` | — | `{ "masters": [{ masterId, name, winRate, subscribers, return30d, riskLevel, verified, markets }] }` |
| 4.2 | POST | `/api/v1/child/subscriptions` | `{ "masterId": "uuid", "brokerAccountId": "uuid", "scalingFactor": 1.0 }` | `{ subscriptionId, status: "PENDING_APPROVAL", message }` |
| 4.3 | DELETE | `/api/v1/child/subscriptions/{masterId}` | — | `{ "message": "Unsubscribed" }` |
| 4.4 | GET | `/api/v1/child/subscriptions` | — | `{ "subscriptions": [{ masterId, masterName, scalingFactor, copyingStatus, subscribedAt, brokerAccountId, pnl, tradesCopiedToday }] }` |
| 4.5 | GET | `/api/v1/child/scaling?masterId=uuid` | — | `{ scalingFactor }` |
| 4.6 | PUT | `/api/v1/child/scaling` | `{ "masterId": "uuid", "scalingFactor": 0.5 }` | `{ scalingFactor }` |
| 4.7 | POST | `/api/v1/child/copying/pause` | `{ "masterId": "uuid" }` | `{ "message": "Copying paused" }` |
| 4.8 | POST | `/api/v1/child/copying/resume` | `{ "masterId": "uuid" }` | `{ "message": "Copying resumed" }` |
| 4.9 | GET | `/api/v1/child/copied-trades` | — | `{ "trades": [{ id, master, instrument, type, masterQty, myQty, pnl, time, status }] }` |
| 4.10 | GET | `/api/v1/child/analytics` | — | `{ totalPnl, copiedTrades, failedReplications, winRate, activeMasters, pnlHistory }` |
| 4.11 | GET | `/api/v1/child/copy/logs` | — | `{ "logs": [...] }` |

### COPY ENGINE APIs

| # | Method | Endpoint | Request Body | Response |
|---|---|---|---|---|
| 5.1 | POST | `/api/v1/engine/copy-trade` | `{ "symbol": "RELIANCE", "qty": 10, "side": "BUY", "product": "CNC", "orderType": "MARKET", "price": 0, "exchange": "NSE" }` | `{ message, symbol, side, masterQty, childrenTotal, success, failed, results: [{ childId, status, message, broker, scaledQty }] }` |
| 5.2 | GET | `/api/v1/engine/status` | — | `{ engineStatus, pollingEnabled, supportedBrokers: ["GROWW","ZERODHA","FYERS","UPSTOX","DHAN","ANGELONE"], modes, detectionMethod }` |
| 5.3 | POST | `/api/v1/engine/polling` | `{ "enabled": true }` | `{ pollingEnabled, message }` |
| 5.4 | POST | `/api/v1/engine/polling/reset` | — | `{ "message": "Known orders cache cleared" }` |
| 5.5 | GET | `/api/v1/engine/polling/status` | — | `{ lastResetAt, autoResetEnabled, pollingEnabled }` |

### ADMIN APIs

| # | Method | Endpoint | Request Body | Response |
|---|---|---|---|---|
| 6.1 | GET | `/api/v1/admin/users?role=MASTER&status=ACTIVE&page=1&limit=20` | — | `{ "users": [UserDto], "total": 50, "page": 1 }` |
| 6.2 | POST | `/api/v1/admin/users/master` | `{ "name", "email", "password", "phone" }` | `{ userId, message }` |
| 6.3 | POST | `/api/v1/admin/users/child` | `{ "name", "email", "password", "phone" }` | `{ userId, message }` |
| 6.4 | GET | `/api/v1/admin/users/{userId}` | — | UserDto |
| 6.5 | PUT | `/api/v1/admin/users/{userId}` | `{ "name", "email", "phone" }` | UserDto |
| 6.6 | PATCH | `/api/v1/admin/users/{userId}/activate` | — | `{ "message": "User activated" }` |
| 6.7 | PATCH | `/api/v1/admin/users/{userId}/deactivate` | — | `{ "message": "User deactivated" }` |
| 6.8 | DELETE | `/api/v1/admin/users/{userId}` | — | `{ "message": "User deleted" }` |
| 6.9 | GET | `/api/v1/admin/analytics` | — | `{ totalUsers: { admin, master, child }, totalTrades, totalReplications, activeSubscriptions }` |
| 6.10 | GET | `/api/v1/admin/system-health` | — | `{ cpuUsage, memoryUsage, avgTradeLatency, brokerStatus, activeWebSocketConnections }` |
| 6.11 | GET | `/api/v1/admin/subscriptions?masterId=uuid` | — | `{ "subscriptions": [...] }` |
| 6.12 | GET | `/api/v1/admin/trade-logs?userId=uuid&status=EXECUTED` | — | `{ "logs": [...] }` |
| 6.13 | GET | `/api/v1/admin/brokers/accounts?userId=uuid&brokerId=GROWW` | — | `{ "accounts": [{ accountId, userId, brokerId, clientId, status }] }` |
| 6.14 | GET | `/api/v1/admin/brokers/status` | — | `{ "brokers": [{ brokerId, name, apiStatus, latencyMs, lastChecked }] }` |


---

## NEW: Switch Broker Account for Copy Trading

`PUT /api/v1/child/subscriptions/broker`

**Headers:** `Authorization: Bearer <childAccessToken>`, `Content-Type: application/json`

**Request Body:**
```json
{
  "masterId": "3cc742bd-6c9d-405a-95e8-49691e4f26d2",
  "brokerAccountId": "33e8ff3c-a8f2-434f-82ea-320cc7893757"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| masterId | UUID | Yes | The master trader's user ID |
| brokerAccountId | UUID | Yes | The new broker account to use for copy trading |

**Success Response (200):**
```json
{
  "message": "Broker account switched",
  "brokerAccountId": "33e8ff3c-a8f2-434f-82ea-320cc7893757",
  "brokerId": "DHAN",
  "brokerName": "DHAN"
}
```

**Error Responses:**
| Status | Response |
|---|---|
| 400 | `{ "error": "brokerAccountId is required" }` |
| 403 | `{ "error": "This broker account does not belong to you" }` |
| 404 | `{ "error": "Subscription not found" }` |

**FE Implementation:**
1. On child's subscription page, show current broker for each master subscription
2. Show dropdown of all linked broker accounts (`GET /brokers/accounts`)
3. On change, call `PUT /child/subscriptions/broker` with masterId + new brokerAccountId
4. No unsubscribe needed — instant switch
