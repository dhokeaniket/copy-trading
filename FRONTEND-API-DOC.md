# Ascentra Trading Platform — Frontend API Documentation
## Base URL: `https://copy-trading-production-3981.up.railway.app`
## Swagger: `https://copy-trading-production-3981.up.railway.app/swagger-ui.html`
## Auth: All endpoints (except public) require `Authorization: Bearer <accessToken>`

---

# HOW BROKER INTEGRATION WORKS

## What is it?
Users (Master/Child) link their broker demat accounts (Groww, Zerodha, etc.) to our platform. Once linked and authenticated, our platform can:
- View their margin/funds
- View their positions/holdings
- Place trades on their behalf (copy trading)

## Flow for Groww:
```
1. User goes to https://groww.in/trade-api/api-keys
2. Generates an API Key + Secret on Groww's website
3. Sets up TOTP authenticator for Groww (Google Authenticator/Authy)
4. Enters API Key, Secret, Client ID on our platform → Links account
5. Enters TOTP code daily → Activates broker session
6. Now our platform can fetch margin, positions, and place trades
```

## What frontend needs to collect from user:
| Field | Where user gets it | Required |
|---|---|---|
| Broker | Dropdown (from GET /brokers) | Yes |
| Client ID | Groww profile / account settings | Yes |
| API Key | https://groww.in/trade-api/api-keys | Yes |
| API Secret | Shown once when generating key | Yes |
| TOTP Code | Authenticator app (daily) | Yes for session |
| Nickname | User chooses | Optional |

---

# SECTION 3: BROKER & DEMAT ACCOUNT (12 endpoints)

## 3.1 GET /api/v1/brokers
List supported brokers. **Public to all authenticated users.**
```
Response: { "brokers": [{ "brokerId", "name", "requiredFields", "isActive" }] }
```

## 3.2 POST /api/v1/brokers/accounts
Link a demat account. **Role: Master, Child**
```json
Request: { "brokerId": "GROWW", "clientId": "...", "apiKey": "...", "apiSecret": "...", "accountNickname": "My Groww" }
Response: { "accountId": "uuid", "brokerId": "GROWW", "status": "AUTH_REQUIRED" }
```

## 3.3 GET /api/v1/brokers/accounts
List user's linked accounts. **Role: Master, Child**
```
Response: { "accounts": [{ "accountId", "brokerId", "brokerName", "clientId", "nickname", "status", "sessionActive", "linkedAt" }] }
```

## 3.4 GET /api/v1/brokers/accounts/{accountId}
Get account details. **Role: Master, Child**

## 3.5 PUT /api/v1/brokers/accounts/{accountId}
Update credentials. **Role: Master, Child**
```json
Request: { "apiKey": "new_key", "apiSecret": "new_secret", "accountNickname": "Updated" }
```

## 3.6 DELETE /api/v1/brokers/accounts/{accountId}
Unlink account. **Role: Master, Child**

## 3.7 POST /api/v1/brokers/accounts/{accountId}/login
Authenticate with broker. **Role: Master, Child**
```json
Request: { "totpCode": "123456" }   ← 6-digit code from authenticator app
Response: { "status": "SESSION_ACTIVE", "expiresAt": "2026-04-03T06:00:00Z" }
```
Session expires daily at 6 AM. User must re-enter TOTP each day.

## 3.8 GET /api/v1/brokers/accounts/{accountId}/status
Check if session is active. **Role: Master, Child**
```
Response: { "sessionActive": true, "expiresAt": "..." }
```

## 3.9 GET /api/v1/brokers/accounts/{accountId}/margin
Get real margin from broker. **Requires active session.**
```
Response: { "availableMargin": 50000, "usedMargin": 10000, "totalFunds": 60000, "collateral": 0 }
```

## 3.10 GET /api/v1/brokers/accounts/{accountId}/positions
Get real positions from broker. **Requires active session.**
```
Response: { "positions": [...] }
```

## 3.11 GET /api/v1/admin/brokers/accounts
Admin: list all linked accounts. **Role: Admin**

## 3.12 GET /api/v1/admin/brokers/status
Admin: broker health status. **Role: Admin**

---

# SECTION 4: MASTER (7 endpoints)
All require `Authorization: Bearer <master_token>` with role MASTER.

## 4.1 GET /api/v1/master/children
List all children linked to this master.
```
Response: { "children": [{ "childId", "name", "email", "scalingFactor", "copyingStatus", "subscribedAt" }] }
```

## 4.2 POST /api/v1/master/children/{childId}/link
Link a child for copy trading.
```json
Request: { "scalingFactor": 1.5 }   ← optional, default 1.0
Response: { "message": "Child linked successfully" }
```

## 4.3 DELETE /api/v1/master/children/{childId}/unlink
Unlink a child. Copying stops immediately.
```
Response: { "message": "Child unlinked" }
```

## 4.4 GET /api/v1/master/children/{childId}/scaling
Get scaling factor for a child.
```
Response: { "childId": "uuid", "scalingFactor": 1.5 }
```

## 4.5 PUT /api/v1/master/children/{childId}/scaling
Update scaling factor. Range: 0.01 to 10.0
```json
Request: { "scalingFactor": 2.0 }
Response: { "childId": "uuid", "scalingFactor": 2.0 }
```

## 4.6 GET /api/v1/master/analytics
Master dashboard analytics.
```
Response: { "totalPnl", "winRate", "totalTrades", "totalReplications", "childPerformance": [...] }
```

## 4.7 GET /api/v1/master/trade-history
Master's trade execution history.
```
Response: { "trades": [...] }
```

---

# SECTION 5: CHILD (10 endpoints)
All require `Authorization: Bearer <child_token>` with role CHILD.

## 5.1 GET /api/v1/child/masters
List available masters to subscribe to.
```
Response: { "masters": [{ "masterId", "name", "winRate", "totalTrades", "avgPnl", "subscribers" }] }
```

## 5.2 POST /api/v1/child/subscriptions
Subscribe to a master for copy trading.
```json
Request: { "masterId": "uuid", "brokerAccountId": "uuid" }
Response: { "subscriptionId": 1, "message": "Subscribed successfully" }
```

## 5.3 DELETE /api/v1/child/subscriptions/{masterId}
Unsubscribe from a master. Open positions are NOT auto-closed.
```
Response: { "message": "Unsubscribed" }
```

## 5.4 GET /api/v1/child/subscriptions
List all current subscriptions.
```
Response: { "subscriptions": [{ "masterId", "masterName", "scalingFactor", "copyingStatus", "subscribedAt", "brokerAccountId" }] }
```

## 5.5 GET /api/v1/child/scaling?masterId={uuid}
Get scaling factor for a subscription.
```
Response: { "scalingFactor": 1.0 }
```

## 5.6 PUT /api/v1/child/scaling
Update scaling factor. Range: 0.01 to 10.0
```json
Request: { "masterId": "uuid", "scalingFactor": 0.5 }
Response: { "scalingFactor": 0.5 }
```

## 5.7 POST /api/v1/child/copying/pause
Pause trade copying from a master.
```json
Request: { "masterId": "uuid" }
Response: { "message": "Copying paused" }
```

## 5.8 POST /api/v1/child/copying/resume
Resume trade copying.
```json
Request: { "masterId": "uuid" }
Response: { "message": "Copying resumed" }
```

## 5.9 GET /api/v1/child/copied-trades
List all trades copied from masters.
```
Response: { "trades": [...] }
```

## 5.10 GET /api/v1/child/analytics
Child dashboard analytics.
```
Response: { "totalPnl", "copiedTrades", "failedReplications", "masterPnlComparison": {} }
```

---

# FRONTEND UI SCREENS NEEDED

## Broker Linking Screen
- Dropdown: Select broker (from GET /brokers)
- Fields: Client ID, API Key, API Secret, Nickname
- Button: "Link Account" → POST /brokers/accounts

## Broker Session Screen
- Show linked accounts with status
- TOTP input field
- Button: "Activate Session" → POST /brokers/accounts/{id}/login
- Show margin + positions after session is active

## Master Dashboard
- List of linked children with scaling factors
- Link/unlink children
- Analytics: P&L, win rate, trade count
- Trade history

## Child Dashboard
- Browse available masters
- Subscribe/unsubscribe
- Pause/resume copying
- Adjust scaling factor
- View copied trades
- Analytics: P&L comparison with master
