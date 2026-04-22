# Ascentra Copy Trading — Complete API Reference

**Base URL:** `https://copy-trading-production-3981.up.railway.app`
**Auth:** All endpoints (except public) require `Authorization: Bearer <accessToken>` header.
**Content-Type:** `application/json` for all POST/PUT/PATCH/DELETE with body.

---

## 1. Authentication (Public)

### 1.1 Register
```
POST /api/v1/auth/register
```
**Body:**
```json
{ "name": "John", "email": "john@example.com", "password": "Pass@123", "role": "MASTER|CHILD", "phone": "+919876543210" }
```
**Response (201):**
```json
{ "userId": "uuid", "message": "User registered successfully" }
```

### 1.2 Login
```
POST /api/v1/auth/login
```
**Body:**
```json
{ "email": "john@example.com", "password": "Pass@123" }
```
**Response:**
```json
{
  "accessToken": "jwt...",
  "refreshToken": "jwt...",
  "user": {
    "userId": "uuid", "name": "John", "email": "john@example.com",
    "role": "MASTER", "status": "ACTIVE", "phone": "+919876543210",
    "twoFactorEnabled": false, "createdAt": "2026-04-20T07:21:39Z",
    "brokerAccounts": []
  },
  "requires2FA": false
}
```

### 1.3 Refresh Token
```
POST /api/v1/auth/refresh-token
```
**Body:**
```json
{ "refreshToken": "jwt..." }
```
**Response:**
```json
{ "accessToken": "new-jwt...", "refreshToken": "new-refresh-jwt..." }
```

### 1.4 Logout
```
POST /api/v1/auth/logout
```
**Body:**
```json
{ "refreshToken": "jwt..." }
```
**Response:**
```json
{ "message": "Logged out" }
```

### 1.5 Forgot Password
```
POST /api/v1/auth/forgot-password
```
**Body:**
```json
{ "email": "john@example.com" }
```
**Response:**
```json
{ "message": "Reset token generated", "resetToken": "uuid" }
```

### 1.6 Reset Password
```
POST /api/v1/auth/reset-password
```
**Body:**
```json
{ "token": "reset-uuid", "newPassword": "NewPass@123" }
```
**Response:**
```json
{ "message": "Password reset successful" }
```

### 1.7 Send OTP
```
POST /api/v1/auth/send-otp
```
**Body:**
```json
{ "phone": "+919876543210" }
```
**Response:**
```json
{ "success": true, "data": { "expiresIn": 300, "retryAfter": 30 }, "message": "OTP sent successfully" }
```

### 1.8 Verify OTP
```
POST /api/v1/auth/verify-otp
```
**Body:**
```json
{ "phone": "+919876543210", "otp": "123456" }
```
**Response:** Same as login response (accessToken + user).

---

## 2. Profile (Auth Required)

### 2.1 Get Profile
```
GET /api/v1/auth/me
```
**Response:**
```json
{
  "userId": "uuid", "name": "John", "email": "john@example.com",
  "role": "MASTER", "status": "ACTIVE", "phone": "+919876543210",
  "twoFactorEnabled": false, "createdAt": "2026-04-20T07:21:39Z"
}
```

### 2.2 Update Profile
```
PUT /api/v1/auth/me
```
**Body:**
```json
{ "name": "New Name", "phone": "+919876543211" }
```

### 2.3 Enable 2FA
```
POST /api/v1/auth/2fa/enable
```
**Response:**
```json
{ "secret": "BASE32SECRET", "qrUri": "otpauth://totp/..." }
```

### 2.4 Verify 2FA
```
POST /api/v1/auth/2fa/verify
```
**Body:**
```json
{ "otp": "123456" }
```

### 2.5 Disable 2FA
```
DELETE /api/v1/auth/2fa/disable
```
**Body:**
```json
{ "password": "Pass@123", "otp": "123456" }
```

---

## 3. Broker Accounts (Auth Required)

### 3.1 List Supported Brokers
```
GET /api/v1/brokers
```
**Response:**
```json
{
  "brokers": [
    {
      "brokerId": "GROWW", "name": "Groww", "isActive": true,
      "loginMethod": "token",
      "loginOptions": [
        { "method": "accessToken", "description": "Paste access token", "requiredFields": ["accessToken"] },
        { "method": "apiKeyWithTotp", "description": "API key + TOTP", "requiredFields": ["apiKey", "totpCode"] }
      ]
    },
    { "brokerId": "ZERODHA", "name": "Zerodha", "isActive": true, "loginMethod": "oauth", "loginField": "requestToken" },
    { "brokerId": "FYERS", "name": "Fyers", "isActive": true, "loginMethod": "oauth", "loginField": "authCode" },
    { "brokerId": "UPSTOX", "name": "Upstox", "isActive": true, "loginMethod": "oauth", "loginField": "authCode" },
    { "brokerId": "DHAN", "name": "Dhan", "isActive": true, "loginMethod": "oauth", "loginField": "tokenId" },
    { "brokerId": "ANGELONE", "name": "Angel One", "isActive": false }
  ]
}
```

### 3.2 Link Broker Account
```
POST /api/v1/brokers/accounts
```
**Body:**
```json
{
  "brokerId": "GROWW",
  "clientId": "ABC123",
  "accountNickname": "my-groww",
  "apiKey": "optional",
  "apiSecret": "optional",
  "accessToken": "optional-direct-token"
}
```
**Response (201):**
```json
{ "accountId": "uuid", "brokerId": "GROWW", "status": "ACTIVE|AUTH_REQUIRED" }
```

### 3.3 List My Broker Accounts
```
GET /api/v1/brokers/accounts
```
**Response:**
```json
{
  "accounts": [
    {
      "accountId": "uuid", "brokerId": "GROWW", "brokerName": "Groww",
      "clientId": "", "nickname": "my-groww", "status": "ACTIVE",
      "sessionActive": true, "linkedAt": "2026-04-20T07:27:25Z",
      "lastSyncedAt": "2026-04-20T12:39:11Z",
      "margin": 0.0, "pnl": 0.0, "positions": 0, "orders": 0
    }
  ]
}
```

### 3.4 Get Broker Account Detail
```
GET /api/v1/brokers/accounts/{accountId}
```

### 3.5 Update Broker Account
```
PUT /api/v1/brokers/accounts/{accountId}
```
**Body:**
```json
{ "apiKey": "new-key", "apiSecret": "new-secret", "accountNickname": "renamed" }
```

### 3.6 Delete Broker Account
```
DELETE /api/v1/brokers/accounts/{accountId}
```
**Note:** If account has active subscriptions, it gets deactivated instead of deleted.

### 3.7 Login to Broker (Create Session)
```
POST /api/v1/brokers/accounts/{accountId}/login
```
**Body varies by broker:**
- **Groww (secret):** `{}` (empty body, uses stored apiKey+apiSecret)
- **Groww (TOTP):** `{ "totpCode": "123456" }`
- **Zerodha:** `{ "requestToken": "token-from-oauth" }`
- **Fyers:** `{ "authCode": "code-from-oauth" }`
- **Upstox:** `{ "authCode": "code-from-oauth" }`
- **Dhan (step 1):** `{}` → returns `{ "loginUrl": "https://auth.dhan.co/..." }`
- **Dhan (step 2):** `{ "authCode": "tokenId-from-redirect" }`

**Response:**
```json
{ "status": "SESSION_ACTIVE", "broker": "Groww", "expiresAt": "2026-04-21T07:27:25Z" }
```

### 3.7b Get OAuth URL
```
GET /api/v1/brokers/accounts/{accountId}/oauth-url?redirectUri=optional
```
**Response:**
```json
{ "broker": "ZERODHA", "loginMethod": "oauth", "loginField": "requestToken", "oauthUrl": "https://kite.zerodha.com/connect/login?..." }
```

### 3.8 Get Session Status
```
GET /api/v1/brokers/accounts/{accountId}/status
```
**Response:**
```json
{
  "accountId": "uuid", "status": "ACTIVE", "sessionActive": true,
  "broker": "GROWW", "brokerName": "Groww", "clientId": "",
  "connectionHealth": "good", "lastSyncedAt": "...", "expiresAt": "..."
}
```

### 3.8b Test Connection
```
GET /api/v1/brokers/accounts/{accountId}/test
```
**Response:**
```json
{ "accountId": "uuid", "connectionHealth": "good", "sessionActive": true, "margin": 236.92, "brokerName": "Groww", "message": "Connection successful" }
```

### 3.9 Get Margin
```
GET /api/v1/brokers/accounts/{accountId}/margin
```
**Response:**
```json
{ "availableMargin": 236.92, "usedMargin": 190.43, "totalFunds": 427.35, "collateral": 0.0 }
```
**Error (session expired):**
```json
{ "error": "Session expired. Please re-login to Groww to continue.", "errorCode": "SESSION_EXPIRED", "action": "RE_LOGIN" }
```

### 3.10 Get Positions
```
GET /api/v1/brokers/accounts/{accountId}/positions
```
**Response:**
```json
{ "positions": [ { "trading_symbol": "RELIANCE", "quantity": 10, ... } ] }
```

### 3.11 Get Orders
```
GET /api/v1/brokers/accounts/{accountId}/orders
```
**Response:**
```json
{ "orders": [ { "order_id": "...", "status": "COMPLETE", "tradingsymbol": "RELIANCE", ... } ] }
```

### 3.12 Get Trades (Broker)
```
GET /api/v1/brokers/accounts/{accountId}/trades
```

### 3.13 Get Holdings
```
GET /api/v1/brokers/accounts/{accountId}/holdings
```
**Response:**
```json
{ "holdings": [ { "tradingsymbol": "RELIANCE", "quantity": 10, ... } ] }
```

### 3.14 Dashboard (All-in-One)
```
GET /api/v1/brokers/accounts/{accountId}/dashboard
```
**Response:**
```json
{
  "accountId": "uuid", "brokerId": "GROWW", "brokerName": "Groww",
  "clientId": "", "nickname": "my-groww", "status": "ACTIVE", "sessionActive": true,
  "signal": { "bars": 4, "maxBars": 4, "quality": "excellent", "color": "green" },
  "balanceAlert": { "level": "OK|LOW|WARNING|CRITICAL", "availableMargin": 236.92 },
  "profile": { "name": "User", "email": "user@email.com", "clientId": "ABC", "broker": "Groww" },
  "margin": { "availableMargin": 236.92, "usedMargin": 190.43, "totalFunds": 427.35, "collateral": 0 },
  "positions": [ ... ],
  "holdings": [ ... ],
  "orders": [ ... ]
}
```

### 3.15 Close Position
```
POST /api/v1/brokers/accounts/{accountId}/orders/close-position
```
**Body:**
```json
{ "symbol": "RELIANCE", "qty": 10, "type": "SELL", "product": "MIS" }
```

### 3.16 Cancel Order
```
DELETE /api/v1/brokers/accounts/{accountId}/orders/{orderId}
```

### 3.17 Balance Alert
```
GET /api/v1/brokers/accounts/{accountId}/balance-alert
```
**Response:**
```json
{ "level": "OK|LOW|WARNING|CRITICAL", "availableMargin": 236.92, "thresholds": { "critical": 1000, "warning": 5000, "low": 10000 } }
```

### 3.18 Connection Signal
```
GET /api/v1/brokers/accounts/{accountId}/signal
```
**Response:**
```json
{
  "accountId": "uuid", "brokerId": "GROWW", "brokerName": "Groww",
  "signal": 4, "maxSignal": 4, "quality": "excellent", "color": "green",
  "message": "Connection excellent (417ms)", "sessionActive": true,
  "latencyMs": 417, "marginAvailable": 236.92
}
```
Signal values: `0` = disconnected (red), `1` = expired/error (red), `2` = fair (yellow), `3` = good (green), `4` = excellent (green)

### 3.19 OAuth Callback (Public)
```
GET /api/v1/brokers/callback?request_token=...&auth_code=...&code=...&tokenId=...
```
Captures OAuth redirect from brokers. Returns the token to use in login API.

---

## 4. Master Endpoints (Role: MASTER)

### 4.1 List Children
```
GET /api/v1/master/children
```
**Response:**
```json
{
  "children": [
    {
      "childId": "uuid", "name": "Child User", "email": "child@email.com",
      "scalingFactor": 1.0, "copyingStatus": "ACTIVE|PAUSED|PENDING_APPROVAL|INACTIVE",
      "subscribedAt": "2026-04-20T10:00:00Z"
    }
  ]
}
```

### 4.2 Link Child (Approve/Add)
```
POST /api/v1/master/children/{childId}/link
```
**Body (optional):**
```json
{ "scalingFactor": 0.5 }
```
If child has PENDING_APPROVAL status, this approves them.

### 4.3 Bulk Link Children
```
POST /api/v1/master/children/bulk-link
```
**Body:**
```json
{
  "children": [
    { "childId": "uuid", "scalingFactor": 1.0 },
    { "childId": "uuid2", "scalingFactor": 0.5 }
  ]
}
```

### 4.4 Unlink Child
```
DELETE /api/v1/master/children/{childId}/unlink
```

### 4.5 Bulk Unlink Children
```
POST /api/v1/master/children/bulk-unlink
```
**Body:**
```json
{ "childIds": ["uuid1", "uuid2"] }
```

### 4.6 Pause Child Copying
```
POST /api/v1/master/children/{childId}/pause
```

### 4.7 Resume Child Copying
```
POST /api/v1/master/children/{childId}/resume
```

### 4.8 List Pending Approvals
```
GET /api/v1/master/children/pending
```
**Response:**
```json
{
  "pendingApprovals": [
    { "childId": "uuid", "name": "Child", "email": "child@email.com", "requestedAt": "...", "subscriptionId": "uuid" }
  ]
}
```

### 4.9 Approve Child
```
POST /api/v1/master/children/{childId}/approve
```

### 4.10 Reject Child
```
POST /api/v1/master/children/{childId}/reject
```

### 4.11 Get Scaling Factor
```
GET /api/v1/master/children/{childId}/scaling
```
**Response:**
```json
{ "childId": "uuid", "scalingFactor": 1.0 }
```

### 4.12 Update Scaling Factor
```
PUT /api/v1/master/children/{childId}/scaling
```
**Body:**
```json
{ "scalingFactor": 0.5 }
```
Range: 0.01 to 10.0

### 4.13 Master Analytics
```
GET /api/v1/master/analytics
```
**Response:**
```json
{
  "totalPnl": 0, "winRate": 0, "totalTrades": 312, "totalReplications": 280,
  "totalChildren": 14, "totalFollowers": 14, "revenue": 0,
  "totalEarnings": 0, "subscriptionRevenue": 0, "performanceBonus": 0, "portfolioValue": 0,
  "earningsBreakdown": [
    { "name": "Subscription Fees", "value": 0 },
    { "name": "Performance Bonus", "value": 0 }
  ],
  "performanceChart": [
    { "date": "2026-03-30", "value": 100 },
    { "date": "2026-04-06", "value": 100 }
  ],
  "pnl": [],
  "childPerformance": [
    { "childId": "uuid", "scalingFactor": 1.0, "copyingStatus": "ACTIVE", "pnl": 0, "tradesCopied": 0 }
  ]
}
```

### 4.14 Trade History
```
GET /api/v1/master/trade-history
```

### 4.15 Set Active Broker Account (for polling)
```
POST /api/v1/master/active-account
```
**Body:**
```json
{ "brokerAccountId": "uuid" }
```
This tells the polling engine which broker account to monitor for new trades.

### 4.16 Get Active Account
```
GET /api/v1/master/active-account
```
**Response:**
```json
{ "brokerAccountId": "uuid" }
```

### 4.17 Clear Active Account
```
DELETE /api/v1/master/active-account
```

### 4.18 Copy Logs
```
GET /api/v1/master/copy/logs
```

### 4.19 Earnings
```
GET /api/v1/master/earnings
```
**Response:**
```json
{
  "totalEarnings": 0, "thisMonth": 0, "lastMonth": 0, "pendingPayout": 0, "currency": "INR",
  "monthlyBreakdown": [
    { "month": "2025-01", "subscribers": 12, "subscriptionFee": 0, "performanceBonus": 0, "total": 0 }
  ],
  "payouts": [
    { "date": "2025-01-31", "amount": 8000, "method": "Bank Transfer", "status": "COMPLETED" }
  ]
}
```

### 4.20 Payouts
```
GET /api/v1/master/payouts
```
**Response:**
```json
{ "payouts": [], "totalPaid": 0, "currency": "INR" }
```

### 4.21 Subscribe to Child (Master follows Child)
```
POST /api/v1/master/subscribe/{childId}
```
**Body (optional):**
```json
{ "scalingFactor": 1.0 }
```

---

## 5. Child Endpoints (Role: CHILD)

### 5.1 List Available Masters
```
GET /api/v1/child/masters
```
**Response:**
```json
{
  "masters": [
    {
      "masterId": "uuid", "name": "Master Trader", "winRate": 0, "totalTrades": 0,
      "avgPnl": 0, "subscribers": 4,
      "return30d": 0, "returnYTD": 0, "riskLevel": "Medium",
      "bestTrade": "₹0", "worstTrade": "₹0", "verified": true,
      "description": "Master Trader — Master trader on Ascentra",
      "markets": ["Equity", "F&O"],
      "equityCurve": [100, 100, 100, 100, 100, 100]
    }
  ]
}
```

### 5.2 Subscribe to Master
```
POST /api/v1/child/subscriptions
```
**Body:**
```json
{ "masterId": "uuid", "brokerAccountId": "uuid", "scalingFactor": 1.0 }
```
`brokerAccountId` is **required** — must be a broker account owned by the child.
New subscriptions go to `PENDING_APPROVAL`. Previously approved children get `ACTIVE` directly.

**Response (201):**
```json
{ "subscriptionId": "uuid", "status": "PENDING_APPROVAL|ACTIVE", "message": "..." }
```

### 5.3 Bulk Subscribe
```
POST /api/v1/child/subscriptions/bulk
```
**Body:**
```json
{
  "masters": [
    { "masterId": "uuid", "brokerAccountId": "uuid", "scalingFactor": 1.0 }
  ]
}
```

### 5.4 Unsubscribe
```
DELETE /api/v1/child/subscriptions/{masterId}
```

### 5.5 Bulk Unsubscribe
```
POST /api/v1/child/subscriptions/bulk-unsubscribe
```
**Body:**
```json
{ "masterIds": ["uuid1", "uuid2"] }
```

### 5.6 List Subscriptions
```
GET /api/v1/child/subscriptions
```
**Response:**
```json
{
  "subscriptions": [
    {
      "masterId": "uuid", "masterName": "Master Trader", "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE", "subscribedAt": "2026-04-20T10:00:00Z",
      "brokerAccountId": "uuid",
      "pnl": 0, "totalPnL": 0, "tradesCopiedToday": 0, "allocation": 0, "allocationAmount": 0
    }
  ]
}
```

### 5.7 Get Scaling
```
GET /api/v1/child/scaling?masterId=uuid
```

### 5.8 Update Scaling
```
PUT /api/v1/child/scaling
```
**Body:**
```json
{ "masterId": "uuid", "scalingFactor": 0.5 }
```

### 5.9 Pause Copying
```
POST /api/v1/child/copying/pause
```
**Body:**
```json
{ "masterId": "uuid" }
```

### 5.10 Resume Copying
```
POST /api/v1/child/copying/resume
```
**Body:**
```json
{ "masterId": "uuid" }
```

### 5.11 Copied Trades
```
GET /api/v1/child/copied-trades
```
**Response:**
```json
{
  "trades": [
    {
      "id": 1, "master": "Master Trader", "instrument": "RELIANCE",
      "type": "BUY", "masterQty": 10, "myQty": 10,
      "entry": 0, "current": 0, "ltp": 0, "pnl": 0,
      "time": "2026-04-20T09:32:00Z", "status": "SUCCESS|FAILED"
    }
  ]
}
```

### 5.12 Child Analytics
```
GET /api/v1/child/analytics
```
**Response:**
```json
{
  "totalPnl": 0, "totalPnL": 0, "personalPnL": 0, "copiedPnL": 0, "masterPnL": 0,
  "personalTrades": 0, "copiedTrades": 14, "failedReplications": 1,
  "portfolioValue": 0, "winRate": 0, "activeMasters": 0,
  "pnlHistory": [
    { "time": "2026-04-16", "personal": 0, "copied": 0 }
  ],
  "personalTradesList": [],
  "masterPnlComparison": { "masterPnl": 0, "childPnl": 0, "replicationAccuracy": 0 }
}
```

### 5.13 Copy Logs
```
GET /api/v1/child/copy/logs
```

---

## 6. Trade Engine (Auth Required)

### 6.1 Execute Trade
```
POST /api/v1/trades/execute
```
**Body:**
```json
{
  "brokerAccountId": "uuid", "instrument": "RELIANCE", "exchange": "NSE",
  "transactionType": "BUY", "quantity": 10, "orderType": "MARKET",
  "product": "MIS", "price": 0
}
```
If caller is MASTER, trade auto-replicates to all active children.

### 6.2 List Trades
```
GET /api/v1/trades?status=EXECUTED
```
**Response:**
```json
{
  "trades": [
    {
      "id": "uuid", "userId": "uuid", "brokerAccountId": "uuid",
      "brokerOrderId": "GMK...", "instrument": "RELIANCE", "exchange": "NSE",
      "segment": "EQUITY", "orderType": "MARKET", "transactionType": "BUY",
      "quantity": 10, "price": 0, "product": "MIS", "status": "EXECUTED",
      "placedAt": "...", "executedAt": "..."
    }
  ]
}
```

### 6.3 Get Trade Detail
```
GET /api/v1/trades/{tradeId}
```

### 6.4 Cancel Trade
```
DELETE /api/v1/trades/{tradeId}/cancel
```

### 6.5 Get Trade Replications
```
GET /api/v1/trades/{tradeId}/replications
```

### 6.6 Open Positions
```
GET /api/v1/trades/open-positions?brokerAccountId=uuid
```

### 6.7 Basket Order
```
POST /api/v1/trades/basket
```
**Body:**
```json
{
  "brokerAccountId": "uuid",
  "orders": [
    { "instrument": "RELIANCE", "exchange": "NSE", "transactionType": "BUY", "quantity": 10, "orderType": "MARKET", "product": "MIS" },
    { "instrument": "TCS", "exchange": "NSE", "transactionType": "BUY", "quantity": 5, "orderType": "MARKET", "product": "MIS" }
  ]
}
```

---

## 7. Copy Engine (Auth Required)

### 7.1 Manual Copy Trade
```
POST /api/v1/engine/copy-trade
```
**Body:**
```json
{ "symbol": "RELIANCE", "qty": 10, "side": "BUY", "product": "MIS", "orderType": "MARKET", "price": 0 }
```
**Response:**
```json
{
  "message": "Trade copy completed", "symbol": "RELIANCE", "side": "BUY",
  "masterQty": 10, "childrenTotal": 3, "success": 2, "failed": 1,
  "results": [
    { "childId": "uuid", "status": "SUCCESS", "message": "Order placed: ORD123", "broker": "GROWW", "scaledQty": 10 },
    { "childId": "uuid2", "status": "FAILED", "message": "Broker session inactive", "broker": "ZERODHA" }
  ]
}
```

### 7.2 Engine Status
```
GET /api/v1/engine/status
```
**Response:**
```json
{
  "engineStatus": "ACTIVE", "pollingEnabled": false, "pollingIntervalSeconds": 1,
  "supportedBrokers": ["GROWW", "ZERODHA", "FYERS", "UPSTOX", "DHAN"],
  "modes": ["manual", "polling", "postback", "websocket"],
  "detectionMethod": {
    "ZERODHA": "postback (~100ms)", "FYERS": "websocket (~50ms)",
    "UPSTOX": "websocket (~50ms)", "DHAN": "polling (1s)", "GROWW": "polling (1s)"
  }
}
```

### 7.3 Toggle Polling
```
POST /api/v1/engine/polling
```
**Body:**
```json
{ "enabled": true }
```

### 7.4 Reset Polling Cache
```
POST /api/v1/engine/polling/reset
```

### 7.5 Polling Status
```
GET /api/v1/engine/polling/status
```
**Response:**
```json
{ "pollingEnabled": true, "autoResetEnabled": true, "lastResetAt": "2026-04-20T03:45:00Z" }
```

---

## 8. Risk Engine (Auth Required)

### 8.1 Get Risk Rules
```
GET /api/v1/risk/rules
```
**Response:**
```json
{ "maxTradesPerDay": 50, "maxOpenPositions": 20, "maxCapitalExposure": 80.0, "marginCheckEnabled": true }
```

### 8.2 Update Risk Rules (Admin)
```
PUT /api/v1/admin/risk/rules/{userId}
```
**Body:**
```json
{ "maxTradesPerDay": 100, "maxOpenPositions": 30, "maxCapitalExposure": 90.0, "marginCheckEnabled": true }
```

### 8.3 Get Exposure
```
GET /api/v1/risk/exposure
```
**Response:**
```json
{ "currentOpenPositions": 5, "maxOpenPositions": 20, "tradesToday": 12, "maxTradesPerDay": 50, "capitalExposurePct": 0 }
```

### 8.4 Margin Check
```
GET /api/v1/risk/margin-check?brokerAccountId=uuid&instrument=RELIANCE&quantity=10&orderType=MARKET&price=0
```
**Response:**
```json
{ "sufficient": true, "requiredMargin": 1000, "availableMargin": 236.92, "shortfall": 0 }
```

---

## 9. P&L Engine (Auth Required)

### 9.1 Realized P&L
```
GET /api/v1/pnl/realized?from=2026-04-01&to=2026-04-20&brokerAccountId=uuid
```

### 9.2 Unrealized P&L
```
GET /api/v1/pnl/unrealized?brokerAccountId=uuid
```

### 9.3 P&L Summary
```
GET /api/v1/pnl/summary?period=DAILY
```
**Response:**
```json
{ "summary": [{ "period": "today", "realizedPnl": 0, "unrealizedPnl": 0, "totalTrades": 18, "winRate": 0 }] }
```

### 9.4 Child vs Master P&L
```
GET /api/v1/pnl/child-vs-master?masterId=uuid
```
**Response:**
```json
{ "masterPnl": 0, "childPnl": 0, "replicationAccuracy": 85.7, "failedReplications": 2 }
```

### 9.5 Admin P&L (Admin)
```
GET /api/v1/admin/pnl/all
```

---

## 10. Logs (Auth Required)

### 10.1 Trade Logs
```
GET /api/v1/logs/trades
```

### 10.2 Broker Error Logs
```
GET /api/v1/logs/broker-errors?brokerAccountId=uuid
```

### 10.3 Admin Trade Logs (Admin)
```
GET /api/v1/admin/logs/trades?userId=uuid&status=EXECUTED
```

### 10.4 Admin System Logs (Admin)
```
GET /api/v1/admin/logs/system
```

### 10.5 Admin Broker Errors (Admin)
```
GET /api/v1/admin/logs/broker-errors?userId=uuid
```

---

## 11. Notifications (Auth Required)

### 11.1 List Notifications
```
GET /api/v1/notifications
```
**Response:**
```json
{
  "notifications": [
    {
      "id": "uuid", "type": "TRADE_COPIED|TRADE_EXECUTED|TRADE_FAILED|SESSION_EXPIRED|SESSION_REMINDER",
      "title": "Trade Copied", "message": "Trade BUY RELIANCE ×10 copied to 2/3 children",
      "read": false, "createdAt": "2026-04-20T08:32:46Z"
    }
  ]
}
```

### 11.2 Mark as Read
```
PATCH /api/v1/notifications/{id}/read
```

### 11.3 Mark All as Read
```
POST /api/v1/notifications/read-all
```

### 11.4 Delete Notification
```
DELETE /api/v1/notifications/{id}
```

---

## 12. Admin Endpoints (Role: ADMIN)

### 12.1 List Users
```
GET /api/v1/admin/users?role=MASTER&status=ACTIVE&page=1&limit=20
```

### 12.2 Create Master
```
POST /api/v1/admin/users/master
```
**Body:**
```json
{ "name": "Master", "email": "master@email.com", "password": "Pass@123", "phone": "+919876543210" }
```

### 12.3 Create Child
```
POST /api/v1/admin/users/child
```
**Body:**
```json
{ "name": "Child", "email": "child@email.com", "password": "Pass@123", "phone": "+919876543210" }
```

### 12.4 Get User
```
GET /api/v1/admin/users/{userId}
```

### 12.5 Update User
```
PUT /api/v1/admin/users/{userId}
```

### 12.6 Activate User
```
PATCH /api/v1/admin/users/{userId}/activate
```

### 12.7 Deactivate User
```
PATCH /api/v1/admin/users/{userId}/deactivate
```

### 12.8 Delete User
```
DELETE /api/v1/admin/users/{userId}
```

### 12.9 Admin Analytics
```
GET /api/v1/admin/analytics
```

### 12.10 System Health
```
GET /api/v1/admin/system-health
```

### 12.11 Admin Subscriptions
```
GET /api/v1/admin/subscriptions?masterId=uuid&status=ACTIVE
```

### 12.12 Admin Trade Logs
```
GET /api/v1/admin/trade-logs?userId=uuid&status=EXECUTED
```

### 12.13 Admin Broker Accounts
```
GET /api/v1/admin/brokers/accounts?userId=uuid&brokerId=GROWW
```

### 12.14 Admin Broker Status
```
GET /api/v1/admin/brokers/status
```

---

## 13. Broker Postback (Public)

### 13.1 Zerodha Postback
```
POST /api/v1/brokers/postback/zerodha
```
Set this URL in Zerodha developer console. Zerodha POSTs order updates (~100ms detection).

---

## 14. WebSocket Channels

Connect via `wss://copy-trading-production-3981.up.railway.app/ws/{channel}`

| Channel | Events |
|---|---|
| `/ws/trades` | `TRADE_DETECTED`, `TRADE_COPIED`, `TRADE_FAILED` |
| `/ws/positions` | Position updates |
| `/ws/pnl` | P&L updates |
| `/ws/notifications` | `SESSION_EXPIRED`, notifications |

**Event format:**
```json
{ "event": "TRADE_DETECTED", "masterId": "uuid", "instrument": "RELIANCE", "side": "BUY", "qty": 10, "broker": "GROWW" }
```

---

## 15. Health Check (Public)

```
GET /health
```
**Response:**
```json
{ "time": "2026-04-20T12:37:54Z", "status": "UP" }
```

---

## Error Format

All errors follow this structure:
```json
{ "error": "Error message", "status": 400 }
```

Broker session errors:
```json
{ "error": "Session expired. Please re-login to Groww to continue.", "errorCode": "SESSION_EXPIRED", "action": "RE_LOGIN" }
```

---

## Subscription Flow

1. Child calls `POST /child/subscriptions` with `masterId` + `brokerAccountId` → status: `PENDING_APPROVAL`
2. Master sees it in `GET /master/children/pending`
3. Master calls `POST /master/children/{childId}/approve` → status: `ACTIVE`
4. If child unsubscribes and re-subscribes later → auto-approved (status: `ACTIVE` directly)

## Copy Trade Flow

1. Master sets active broker: `POST /master/active-account`
2. Enable polling: `POST /engine/polling { "enabled": true }`
3. Master places trade on their broker app (Groww/Zerodha/etc.)
4. Poller detects new COMPLETE order (every 1 second)
5. Copy engine replicates to all ACTIVE children in parallel
6. Each child gets notification + trade logged

**Total endpoints: 90+**
