# Ascentra Trading Platform — Frontend API Documentation
## Sections 3, 4, 5
## Base URL: `https://copy-trading-production-3981.up.railway.app`
## Swagger: `https://copy-trading-production-3981.up.railway.app/swagger-ui.html`

All authenticated endpoints require: `Authorization: Bearer <accessToken>`

---

# HOW BROKER INTEGRATION WORKS

## What is it?
Users (Master/Child) link their broker demat accounts (Groww, Zerodha, etc.) to our platform. Once linked and authenticated, our platform can:
- View their margin/funds
- View their positions/holdings
- Place trades on their behalf (copy trading)
- Cancel trades

## What the user needs:
1. A broker account (e.g., Groww trading account)
2. API Key — generated on the broker's website
3. API Secret — shown once when generating the key
4. TOTP Code — from their authenticator app (for daily session activation)

## Flow:
```
User registers on Ascentra → Links broker account → Enters TOTP daily → Session active → Trading enabled
```

## Groww-specific:
- API Keys page: https://groww.in/trade-api/api-keys
- User generates API key + secret there
- TOTP is set up via Groww's authenticator
- Session expires daily at 6 AM — user must re-enter TOTP each day
- Our backend handles all Groww API calls (margin, positions, orders)

---

# SECTION 3: BROKER & DEMAT ACCOUNT (12 endpoints)

## 3.1 List Supported Brokers
```
GET /api/v1/brokers
Auth: Any logged-in user
```
Response:
```json
{
  "brokers": [
    {"brokerId":"GROWW","name":"Groww","requiredFields":["apiKey","apiSecret","clientId"],"isActive":true},
    {"brokerId":"ZERODHA","name":"Zerodha","requiredFields":["apiKey","apiSecret","clientId"],"isActive":false}
  ]
}
```
Frontend: Use this to populate the broker dropdown. Show `requiredFields` as form inputs. Grey out brokers where `isActive: false`.

---

## 3.2 Link Demat Account
```
POST /api/v1/brokers/accounts
Auth: Master, Child
```
Request:
```json
{
  "brokerId": "GROWW",
  "clientId": "user's broker client ID",
  "apiKey": "user's API key from broker",
  "apiSecret": "user's API secret from broker",
  "accountNickname": "My Groww Account"
}
```
Response:
```json
{"accountId":"<uuid>","brokerId":"GROWW","status":"AUTH_REQUIRED"}
```
Frontend: Show a form with fields from 3.1's `requiredFields`. After success, show the account with status badge.

---

## 3.3 List My Linked Accounts
```
GET /api/v1/brokers/accounts
Auth: Master, Child
```
Response:
```json
{
  "accounts": [
    {"accountId":"<uuid>","brokerId":"GROWW","brokerName":"GROWW","clientId":"...","nickname":"My Groww","status":"AUTH_REQUIRED","sessionActive":false,"linkedAt":"..."}
  ]
}
```

---

## 3.4 Get Account Details
```
GET /api/v1/brokers/accounts/{accountId}
Auth: Master, Child (own accounts only)
```

---

## 3.5 Update Account
```
PUT /api/v1/brokers/accounts/{accountId}
Auth: Master, Child
```
Request:
```json
{"apiKey":"new_key","apiSecret":"new_secret","accountNickname":"Updated Name"}
```

---

## 3.6 Delete/Unlink Account
```
DELETE /api/v1/brokers/accounts/{accountId}
Auth: Master, Child
```

---

## 3.7 Login to Broker (Activate Session)
```
POST /api/v1/brokers/accounts/{accountId}/login
Auth: Master, Child
```
Request:
```json
{"totpCode":"123456"}
```
Response (success):
```json
{"status":"SESSION_ACTIVE","expiresAt":"2026-04-03T06:00:00Z"}
```
Frontend: Show a TOTP input field. User opens their authenticator app, gets the 6-digit code, enters it. Must be done within 30 seconds. Session lasts until 6 AM next day.

If `totpCode` is empty/null, the approval flow is used (requires daily approval on broker website).

---

## 3.8 Check Session Status
```
GET /api/v1/brokers/accounts/{accountId}/status
Auth: Master, Child
```
Response:
```json
{"sessionActive":true,"expiresAt":"2026-04-03T06:00:00Z"}
```
Frontend: Poll this to show green/red session indicator.

---

## 3.9 Get Margin/Funds
```
GET /api/v1/brokers/accounts/{accountId}/margin
Auth: Master, Child (requires active session)
```
Response:
```json
{"availableMargin":50000,"usedMargin":25000,"totalFunds":75000,"collateral":0}
```

---

## 3.10 Get Positions
```
GET /api/v1/brokers/accounts/{accountId}/positions
Auth: Master, Child (requires active session)
```
Response:
```json
{"positions":[...]}
```

---

## 3.11 Admin: List All Broker Accounts
```
GET /api/v1/admin/brokers/accounts?userId=<uuid>&brokerId=GROWW
Auth: Admin only
```

---

## 3.12 Admin: Broker Health Status
```
GET /api/v1/admin/brokers/status
Auth: Admin only
```

---
