# Ascentra — Broker, Master & Child API Reference
## Base URL: `https://copy-trading-production-3981.up.railway.app`
## All endpoints require: `Authorization: Bearer <accessToken>`

---

# SECTION 3: BROKER & DEMAT ACCOUNT (12 endpoints)

### 3.1 GET /api/v1/brokers
List supported brokers.
```
Response:
{
  "brokers": [
    {
      "brokerId": "GROWW",
      "name": "Groww",
      "requiredFields": ["apiKey", "apiSecret", "clientId"],
      "isActive": true
    },
    {
      "brokerId": "ZERODHA",
      "name": "Zerodha",
      "requiredFields": ["apiKey", "apiSecret", "clientId"],
      "isActive": false
    }
  ]
}
```

### 3.2 POST /api/v1/brokers/accounts
Link a demat account. Role: Master, Child
```
Request:
{
  "brokerId": "GROWW",
  "clientId": "user-groww-account-id",
  "apiKey": "groww-api-key-from-website",
  "apiSecret": "groww-api-secret",
  "accountNickname": "My Groww"
}

Response:
{
  "accountId": "65271aed-5abd-433d-8000-4bef1965152f",
  "brokerId": "GROWW",
  "status": "AUTH_REQUIRED"
}
```

### 3.3 GET /api/v1/brokers/accounts
List user's linked accounts.
```
Response:
{
  "accounts": [
    {
      "accountId": "65271aed-...",
      "brokerId": "GROWW",
      "brokerName": "GROWW",
      "clientId": "b1c30a3f-...",
      "nickname": "My Groww",
      "status": "AUTH_REQUIRED",
      "sessionActive": false,
      "linkedAt": "2026-04-02T05:10:00Z"
    }
  ]
}
```

### 3.4 GET /api/v1/brokers/accounts/{accountId}
Get single account details.
```
Response: same fields as above for one account
```

### 3.5 PUT /api/v1/brokers/accounts/{accountId}
Update credentials.
```
Request:
{
  "apiKey": "new-api-key",
  "apiSecret": "new-api-secret",
  "accountNickname": "Updated Name"
}

Response: { "message": "Account updated" }
```

### 3.6 DELETE /api/v1/brokers/accounts/{accountId}
Unlink account.
```
Response: { "message": "Account unlinked" }
```

### 3.7 POST /api/v1/brokers/accounts/{accountId}/login
Authenticate with broker. Creates a trading session.
```
Request:
{
  "totpCode": "123456"
}

Response:
{
  "status": "SESSION_ACTIVE",
  "expiresAt": "2026-04-03T06:00:00Z"
}
```
Note: totpCode is the 6-digit code from user's authenticator app (linked to Groww). Session expires daily at 6 AM. Send empty body `{}` for approval flow (if key is pre-approved on Groww website).

### 3.8 GET /api/v1/brokers/accounts/{accountId}/status
Check session status.
```
Response:
{
  "sessionActive": true,
  "expiresAt": "2026-04-03T06:00:00Z"
}
```

### 3.9 GET /api/v1/brokers/accounts/{accountId}/margin
Get margin/funds from broker. Requires active session.
```
Response:
{
  "availableMargin": 50000,
  "usedMargin": 10000,
  "totalFunds": 60000,
  "collateral": 0
}
```

### 3.10 GET /api/v1/brokers/accounts/{accountId}/positions
Get open positions from broker. Requires active session.
```
Response:
{
  "positions": [
    {
      "trading_symbol": "RELIANCE-EQ",
      "quantity": 10,
      "net_price": 2500,
      "realised_pnl": 150
    }
  ]
}
```

### 3.11 GET /api/v1/admin/brokers/accounts
Admin only: list all linked accounts across all users.
```
Query params: ?userId=uuid&brokerId=GROWW
Response: { "accounts": [...] }
```

### 3.12 GET /api/v1/admin/brokers/status
Admin only: broker health status.
```
Response:
{
  "brokers": [
    { "brokerId": "GROWW", "name": "Groww", "apiStatus": "UP", "latencyMs": 45 }
  ]
}
```

---

# SECTION 4: MASTER (7 endpoints)
Role: MASTER only

### 4.1 GET /api/v1/master/children
List all children linked to this master.
```
Response:
{
  "children": [
    {
      "childId": "5ea1cc92-...",
      "name": "Test Child",
      "email": "child@test.com",
      "scalingFactor": 1.5,
      "copyingStatus": "ACTIVE",
      "subscribedAt": "2026-04-02T06:00:00Z"
    }
  ]
}
```

### 4.2 POST /api/v1/master/children/{childId}/link
Link a child for copy trading.
```
Request:
{
  "scalingFactor": 1.5
}

Response: { "message": "Child linked successfully" }
```
scalingFactor is optional, defaults to 1.0. Range: 0.01 to 10.0

### 4.3 DELETE /api/v1/master/children/{childId}/unlink
Unlink a child. Copying stops immediately.
```
Response: { "message": "Child unlinked" }
```

### 4.4 GET /api/v1/master/children/{childId}/scaling
Get scaling factor for a child.
```
Response:
{
  "childId": "5ea1cc92-...",
  "scalingFactor": 1.5
}
```

### 4.5 PUT /api/v1/master/children/{childId}/scaling
Update scaling factor.
```
Request:
{
  "scalingFactor": 2.0
}

Response:
{
  "childId": "5ea1cc92-...",
  "scalingFactor": 2.0
}
```

### 4.6 GET /api/v1/master/analytics
Master dashboard data.
```
Response:
{
  "totalPnl": 0,
  "winRate": 0,
  "totalTrades": 5,
  "totalReplications": 12,
  "childPerformance": [
    { "childId": "...", "scalingFactor": 1.5, "copyingStatus": "ACTIVE" }
  ]
}
```

### 4.7 GET /api/v1/master/trade-history
Master's trade history.
```
Response:
{
  "trades": [
    { "id": 1, "masterId": "...", "type": "EXECUTED", "status": "SUCCESS", "broker": "GROWW", "createdAt": "..." }
  ]
}
```

---

# SECTION 5: CHILD (10 endpoints)
Role: CHILD only

### 5.1 GET /api/v1/child/masters
List available masters to subscribe to.
```
Response:
{
  "masters": [
    {
      "masterId": "fb7c6690-...",
      "name": "Aniket Master",
      "winRate": 0,
      "totalTrades": 0,
      "avgPnl": 0,
      "subscribers": 0
    }
  ]
}
```

### 5.2 POST /api/v1/child/subscriptions
Subscribe to a master.
```
Request:
{
  "masterId": "fb7c6690-45ec-40f9-acfa-20e8e474775c",
  "brokerAccountId": "65271aed-5abd-433d-8000-4bef1965152f"
}

Response:
{
  "subscriptionId": 1,
  "message": "Subscribed successfully"
}
```
brokerAccountId is optional — the child's demat account to use for copied trades.

### 5.3 DELETE /api/v1/child/subscriptions/{masterId}
Unsubscribe from a master. Open positions are NOT auto-closed.
```
Response: { "message": "Unsubscribed" }
```

### 5.4 GET /api/v1/child/subscriptions
List all current subscriptions.
```
Response:
{
  "subscriptions": [
    {
      "masterId": "fb7c6690-...",
      "masterName": "Aniket Master",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "subscribedAt": "2026-04-02T06:00:00Z",
      "brokerAccountId": "65271aed-..."
    }
  ]
}
```

### 5.5 GET /api/v1/child/scaling?masterId={uuid}
Get scaling factor for a subscription.
```
Response: { "scalingFactor": 1.0 }
```

### 5.6 PUT /api/v1/child/scaling
Update scaling factor. Range: 0.01 to 10.0
```
Request:
{
  "masterId": "fb7c6690-...",
  "scalingFactor": 0.5
}

Response: { "scalingFactor": 0.5 }
```

### 5.7 POST /api/v1/child/copying/pause
Pause trade copying from a master.
```
Request: { "masterId": "fb7c6690-..." }
Response: { "message": "Copying paused" }
```

### 5.8 POST /api/v1/child/copying/resume
Resume trade copying.
```
Request: { "masterId": "fb7c6690-..." }
Response: { "message": "Copying resumed" }
```

### 5.9 GET /api/v1/child/copied-trades
List all trades copied from masters.
```
Response:
{
  "trades": [
    { "id": 1, "childId": "...", "type": "REPLICATED", "status": "SUCCESS", "broker": "GROWW", "createdAt": "..." }
  ]
}
```

### 5.10 GET /api/v1/child/analytics
Child dashboard data.
```
Response:
{
  "totalPnl": 0,
  "copiedTrades": 12,
  "failedReplications": 1,
  "masterPnlComparison": {}
}
```

---

# BROKER LINKING FLOW (for frontend UI)

```
Step 1: GET /api/v1/brokers → show broker dropdown
Step 2: User selects broker, enters Client ID + API Key + API Secret
Step 3: POST /api/v1/brokers/accounts → link account (status: AUTH_REQUIRED)
Step 4: User enters TOTP code from authenticator app
Step 5: POST /api/v1/brokers/accounts/{id}/login → activate session
Step 6: GET /api/v1/brokers/accounts/{id}/margin → show funds
Step 7: GET /api/v1/brokers/accounts/{id}/positions → show positions
```

User gets API Key + Secret from: https://groww.in/trade-api/api-keys
User gets TOTP code from: Google Authenticator / Authy (linked to Groww)
Session expires daily at 6 AM — user must re-enter TOTP each day.
