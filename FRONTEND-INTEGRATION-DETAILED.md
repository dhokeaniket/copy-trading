# Ascentra — Frontend integration (detailed request / response)

**Use this doc instead of Swagger** for request bodies (Swagger shows `additionalProp` for generic JSON maps).

| | |
|--|--|
| **Base URL** | `https://api.ascentracapital.com` or `http://13.53.246.13:8081` |
| **Auth** | `Authorization: Bearer <accessToken>` on all routes except login/register/OTP/callback |
| **Content-Type** | `application/json` for POST/PUT/PATCH |
| **Access token TTL** | ~15 minutes — refresh with `/api/v1/auth/refresh-token` |

**Test admin (prod DB):** `admin@gmail.com` / `admin@123`

---

## 1. Authentication

### POST `/api/v1/auth/login`

**Request**
```json
{
  "email": "admin@gmail.com",
  "password": "admin@123"
}
```

**Response 200**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "userId": "0be55f3e-d6a3-4a0f-925e-a3f00e9a6c73",
    "name": "Platform Admin",
    "email": "admin@gmail.com",
    "role": "ADMIN",
    "status": "ACTIVE",
    "phone": null,
    "telegramChatId": null,
    "twoFactorEnabled": false,
    "createdAt": "2026-05-28T10:29:03.817295Z",
    "brokerAccounts": []
  },
  "requires2FA": false
}
```

If `requires2FA: true` → call `POST /api/v1/auth/2fa/verify` with `{ "otp": "123456" }` using the temporary token.

---

### POST `/api/v1/auth/register`

**Request**
```json
{
  "name": "John Doe",
  "email": "child@example.com",
  "password": "Pass@1234",
  "role": "CHILD",
  "phone": "+919876543210"
}
```
`role`: `MASTER` | `CHILD` | `ADMIN`  
Password: min 8 chars, at least one digit and one special character.

**Response 201**
```json
{
  "userId": "uuid",
  "message": "Registration successful"
}
```

---

### POST `/api/v1/auth/refresh-token`

**Request**
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**Response 200**
```json
{
  "accessToken": "new...",
  "refreshToken": "new..."
}
```

---

### POST `/api/v1/auth/send-otp`

**Request**
```json
{
  "phone": "+919876543210",
  "purpose": "login"
}
```

**Response 200**
```json
{
  "success": true,
  "message": "OTP sent successfully",
  "data": {
    "expiresIn": 300,
    "retryAfter": 60
  }
}
```

---

### POST `/api/v1/auth/verify-otp`

**Request**
```json
{
  "phone": "+919876543210",
  "otp": "123456",
  "purpose": "login"
}
```

**Response 200 (success)**
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "user": { "userId": "...", "email": "...", "role": "CHILD", ... }
  }
}
```

**Response 200 (failure)**
```json
{
  "success": false,
  "error": "INVALID_OTP",
  "message": "Invalid OTP code"
}
```

---

### POST `/api/v1/auth/2fa/enable`

**Headers:** `Authorization: Bearer <token>`

**Response 200**
```json
{
  "qrCodeUri": "otpauth://totp/Ascentra:email?secret=XXXX&issuer=Ascentra",
  "qrCode": "otpauth://totp/Ascentra:email?secret=XXXX&issuer=Ascentra",
  "secret": "BASE32SECRET"
}
```
Render QR from `qrCodeUri` or `qrCode` (same value).

---

### POST `/api/v1/auth/2fa/verify`

**Request**
```json
{
  "otp": "123456"
}
```

---

## 2. Admin

All require `role: ADMIN` JWT.

### GET `/api/v1/admin/users?page=1&limit=20&role=MASTER&status=ACTIVE`

**Response 200**
```json
{
  "users": [
    {
      "userId": "uuid",
      "name": "Master User",
      "email": "master@gmail.com",
      "role": "MASTER",
      "status": "ACTIVE",
      "phone": null,
      "twoFactorEnabled": false,
      "createdAt": "2026-01-01T00:00:00Z"
    }
  ],
  "total": 42,
  "page": 1
}
```

---

### POST `/api/v1/admin/users/master`

**Request**
```json
{
  "name": "New Master",
  "email": "newmaster@gmail.com",
  "password": "Pass@1234",
  "phone": "+919876543210"
}
```

**Response 201**
```json
{
  "userId": "uuid",
  "message": "Master account created"
}
```

---

### POST `/api/v1/admin/users/child`

**Request** — same shape as master; creates `CHILD` role.

**Response 201**
```json
{
  "userId": "uuid",
  "message": "Child account created"
}
```

---

### GET `/api/v1/admin/system-health`

**Response 200**
```json
{
  "status": "UP",
  "database": "connected",
  "timestamp": "2026-05-28T10:00:00Z"
}
```

---

## 3. Master

All require `role: MASTER` JWT.

### GET `/api/v1/master/dashboard`

**Response 200**
```json
{
  "activeChildren": 3,
  "totalChildren": 5,
  "totalTradesCopied": 120,
  "totalFailed": 8,
  "todayTradesCopied": 12,
  "successRate": 94,
  "children": [
    {
      "childId": "uuid",
      "name": "Child One",
      "email": "child@example.com",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "marginAvailable": 50000,
      "marginUsed": 10000,
      "pnlToday": 250.5,
      "openPositionsCount": 2,
      "tradesCopied": 45,
      "sessionActive": true
    }
  ]
}
```

---

### GET `/api/v1/master/pnl-analytics`

**Response 200**
```json
{
  "summary": {
    "masterUnrealizedPnl": 1500.25,
    "followersUnrealizedPnl": 800.0,
    "combinedUnrealizedPnl": 2300.25,
    "totalFollowerMarginAvailable": 250000,
    "activeFollowers": 3,
    "totalCopiesSuccess": 500,
    "totalCopiesFailed": 20,
    "todayCopiesSuccess": 15,
    "replicationSuccessRate": 96
  },
  "masterActiveAccount": {
    "connected": true,
    "brokerAccountId": "uuid",
    "broker": "GROWW",
    "sessionActive": true
  },
  "masterPositions": {
    "positions": [],
    "totalPnl": 1500.25,
    "count": 2
  },
  "childPerformance": [
    {
      "childId": "uuid",
      "name": "Child One",
      "marginAvailable": 50000,
      "pnlToday": 250.5,
      "openPositionsCount": 2,
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "tradesCopied": 45
    }
  ],
  "dailyChart": [
    { "date": "2026-05-22", "success": 10, "failed": 1 },
    { "date": "2026-05-23", "success": 8, "failed": 0 }
  ],
  "earningsBreakdown": [
    { "name": "Replication volume", "value": 500, "unit": "trades" }
  ]
}
```

---

### GET `/api/v1/master/trade-logs` or `/api/v1/master/trade-history`

Same data (copy_logs). **Do not use old trade_logs table.**

**Response 200**
```json
{
  "logs": [
    {
      "id": 12345,
      "masterId": "uuid",
      "childId": "uuid",
      "childName": "Child One",
      "symbol": "NIFTY 24850 CE 02 JUN 26",
      "qty": 75,
      "tradeType": "BUY",
      "masterStatus": "COMPLETE",
      "childStatus": "SUCCESS",
      "errorMessage": null,
      "skipReason": null,
      "latencyMs": 95,
      "copyGroupId": "group-uuid",
      "engineReceivedAt": "2026-05-28T10:00:00Z",
      "childPlacedAt": "2026-05-28T10:00:00.095Z",
      "createdAt": "2026-05-28T10:00:00.100Z"
    },
    {
      "id": 12346,
      "childStatus": "FAILED",
      "errorMessage": "Order failed: 401 Unauthorized [childQty=75]",
      "skipReason": null
    },
    {
      "id": 12347,
      "childStatus": "SKIPPED",
      "skipReason": "SUB_LOT_SIZE",
      "errorMessage": "Scaled qty below 1 lot"
    }
  ],
  "trades": [],
  "total": 3
}
```
Note: `trades` mirrors `logs` on trade-history endpoint.

---

### GET `/api/v1/engine/trade-history?page=0&size=20&symbol=NIFTY&side=BUY`

Grouped by `copyGroupId` (latency / history UI).

**Response 200**
```json
{
  "totalElements": 50,
  "page": 0,
  "size": 20,
  "content": [
    {
      "eventId": "copy-group-uuid",
      "symbol": "RELIANCE",
      "side": "BUY",
      "masterQty": 10,
      "masterTriggeredAt": "2026-05-28T10:00:00Z",
      "engineReceivedAt": "2026-05-28T10:00:00.010Z",
      "avgChildLatencyMs": 120,
      "minChildLatencyMs": 80,
      "maxChildLatencyMs": 200,
      "childrenTotal": 3,
      "childrenSucceeded": 2,
      "childrenFailed": 0,
      "childrenSkipped": 1,
      "failures": [
        {
          "childId": "uuid",
          "status": "SKIPPED",
          "errorMessage": null,
          "skipReason": "SUB_LOT_SIZE",
          "latencyMs": 0
        }
      ]
    }
  ]
}
```

---

### GET `/api/v1/engine/trade-history/{eventId}`

**Response 200** — same as list item plus `children` array:
```json
{
  "eventId": "copy-group-uuid",
  "symbol": "RELIANCE",
  "side": "BUY",
  "childrenTotal": 3,
  "childrenSucceeded": 2,
  "childrenFailed": 0,
  "childrenSkipped": 1,
  "failures": [],
  "children": [
    {
      "childName": "Child One",
      "childId": "uuid",
      "broker": "—",
      "status": "SUCCESS",
      "symbol": "RELIANCE",
      "side": "BUY",
      "qty": 10,
      "masterQty": 10,
      "orderId": "broker-order-id",
      "errorMessage": null,
      "skipReason": null,
      "failureReason": null,
      "totalChildLatencyMs": 95,
      "brokerLatencyMs": 95,
      "childPlacedAt": "2026-05-28T10:00:00.095Z"
    },
    {
      "childName": "Child Two",
      "status": "FAILED",
      "errorMessage": "SESSION_EXPIRED: Re-login required",
      "failureReason": "SESSION_EXPIRED: Re-login required",
      "skipReason": null
    }
  ]
}
```

---

### GET `/api/v1/master/open-book`

**Response 200**
```json
{
  "orders": [
    {
      "order_id": "123",
      "tradingsymbol": "RELIANCE",
      "status": "OPEN",
      "transaction_type": "BUY",
      "quantity": 10
    }
  ],
  "total": 1,
  "brokerAccountId": "uuid",
  "broker": "GROWW"
}
```

**Response (no session)**
```json
{
  "orders": [],
  "total": 0,
  "error": "No active broker session. Login to your broker first.",
  "errorCode": "SESSION_EXPIRED",
  "action": "RE_LOGIN"
}
```

---

### GET `/api/v1/master/open-options`

**Response 200**
```json
{
  "positions": [
    {
      "symbol": "NIFTY 24850 CE 02 JUN 26",
      "qty": 75,
      "avgPrice": 120.5,
      "ltp": 125.0,
      "pnl": 337.5,
      "product": "NRML",
      "exchange": "NSE"
    }
  ],
  "total": 1,
  "totalPnl": 337.5,
  "brokerAccountId": "uuid"
}
```

---

### GET `/api/v1/master/option-status`

**Response 200**
```json
{
  "items": [
    {
      "id": 12345,
      "copyGroupId": "group-uuid",
      "symbol": "NIFTY 24850 CE 02 JUN 26",
      "side": "BUY",
      "qty": 75,
      "masterQty": 75,
      "status": "SKIPPED",
      "masterStatus": "COMPLETE",
      "errorMessage": "Scaled qty below 1 lot",
      "skipReason": "SUB_LOT_SIZE",
      "failureReason": "Scaled qty below 1 lot",
      "latencyMs": 0,
      "masterId": "uuid",
      "childId": "uuid",
      "orderId": "master-order-id",
      "createdAt": "2026-05-28T10:00:00Z",
      "childPlacedAt": null
    }
  ],
  "total": 1,
  "success": 0,
  "failed": 0,
  "skipped": 1
}
```

---

### POST `/api/v1/master/positions/square-off`

**Request**
```json
{
  "symbol": "RELIANCE",
  "qty": 10,
  "type": "SELL",
  "product": "MIS"
}
```

| Field | Required | Values |
|-------|----------|--------|
| symbol | yes | Trading symbol |
| qty | yes | Quantity to close |
| type | no | `BUY` or `SELL` (default `SELL` for long) |
| product | no | `MIS`, `CNC`, `NRML` |

**Response 200**
```json
{
  "status": "SUCCESS",
  "orderId": "broker-order-id",
  "message": "Close order placed"
}
```

Uses master's **active broker account** (`POST /master/active-account`).

---

### POST `/api/v1/master/active-account`

**Request**
```json
{
  "brokerAccountId": "uuid-of-linked-broker-account"
}
```

**Response 200**
```json
{
  "message": "Active account set",
  "brokerAccountId": "uuid",
  "broker": "GROWW",
  "connected": true
}
```

---

### GET `/api/v1/master/active-account`

**Response 200**
```json
{
  "connected": true,
  "brokerAccountId": "uuid",
  "broker": "GROWW",
  "sessionActive": true,
  "isTokenExpired": false,
  "clientId": "..."
}
```

---

### DELETE `/api/v1/master/children/{childId}` or `.../unlink`

**Response 200**
```json
{
  "message": "Child unlinked"
}
```

---

### PUT `/api/v1/master/children/{childId}/scaling`

**Request**
```json
{
  "scalingFactor": 1.5
}
```
Range: `0.01` – `10.0`

---

## 4. Child

All require `role: CHILD` JWT.

### GET `/api/v1/child/masters`

**Headers:** `Authorization: Bearer <token>` (**required**)

**Response 200**
```json
{
  "masters": [
    {
      "masterId": "uuid",
      "name": "Master Trader",
      "email": "master@gmail.com",
      "winRate": 85,
      "totalTrades": 120,
      "totalCopies": 500,
      "failedCopies": 20,
      "avgPnl": 0,
      "subscribers": 5,
      "return30d": 85,
      "returnYTD": 85,
      "riskLevel": "Medium",
      "verified": true,
      "description": "Master Trader — Master trader on Ascentra",
      "markets": ["Equity", "F&O"],
      "equityCurve": [100, 100, 100, 100, 100, 100],
      "mySubscriptionStatus": "ACTIVE",
      "myScalingFactor": 1.0,
      "subscribed": true
    },
    {
      "masterId": "uuid2",
      "mySubscriptionStatus": "NOT_SUBSCRIBED",
      "subscribed": false
    }
  ]
}
```

---

### POST `/api/v1/child/subscriptions`

**Request**
```json
{
  "masterId": "uuid",
  "brokerAccountId": "uuid",
  "scalingFactor": 1.0,
  "copySides": "BUY_ONLY",
  "allowShortSelling": false
}
```

| Field | Required | Notes |
|-------|----------|-------|
| masterId | yes | |
| brokerAccountId | yes | Child's linked broker for copies |
| scalingFactor | no | Default `1.0` |
| copySides | no | `BUY_ONLY`, `BUY_AND_SELL`, `MIRROR` |
| allowShortSelling | no | Default `false` |

**Response 201**
```json
{
  "subscriptionId": 1,
  "status": "PENDING_APPROVAL",
  "message": "Subscription request sent. Waiting for master approval."
}
```

Or `"status": "ACTIVE"` if re-subscribing after prior approval.

---

### DELETE `/api/v1/child/subscriptions/{masterId}`  
### DELETE `/api/v1/child/remove/{masterId}`

**Response 200**
```json
{
  "message": "Unsubscribed"
}
```

---

### GET `/api/v1/child/subscriptions`

**Response 200**
```json
{
  "subscriptions": [
    {
      "subscriptionId": 1,
      "masterId": "uuid",
      "masterName": "Master Trader",
      "scalingFactor": 1.0,
      "copySides": "BUY_ONLY",
      "allowShortSelling": false,
      "copyingStatus": "ACTIVE",
      "subscribedAt": "2026-05-01T00:00:00Z",
      "brokerAccountId": "uuid",
      "pnl": 0,
      "totalPnL": 0,
      "tradesCopied": 45,
      "tradesCopiedToday": 3,
      "allocation": 0,
      "allocationAmount": 0
    }
  ]
}
```

---

### GET `/api/v1/child/trade-timeline`

**Response 200**
```json
{
  "trades": [
    {
      "eventId": "copy-group-uuid",
      "masterName": "Master Trader",
      "symbol": "RELIANCE",
      "side": "BUY",
      "masterTriggeredAt": "2026-05-28T10:00:00Z",
      "myOrderPlacedAt": "2026-05-28T10:00:00.095Z",
      "totalChildLatencyMs": 95,
      "status": "FAILED",
      "skipReason": null,
      "errorMessage": "Order failed: insufficient margin",
      "failureReason": "Order failed: insufficient margin",
      "masterStatus": "COMPLETE",
      "qty": 10,
      "masterQty": 10,
      "orderId": "broker-order-id"
    }
  ]
}
```

---

### GET `/api/v1/child/pnl-dashboard` or `/api/v1/child/analytics`

**Response 200**
```json
{
  "totalPnl": 1250.5,
  "totalPnL": 1250.5,
  "personalPnL": 0,
  "copiedPnL": 1250.5,
  "masterPnL": 0,
  "unrealizedPnl": 1250.5,
  "personalTrades": 0,
  "copiedTrades": 80,
  "skippedCopies": 5,
  "failedReplications": 10,
  "portfolioValue": 1250.5,
  "winRate": 89,
  "activeMasters": 2,
  "openPositions": [],
  "pnlHistory": [
    { "time": "2026-05-24", "personal": 0, "copied": 0 },
    { "time": "2026-05-28", "personal": 0, "copied": 80 }
  ],
  "personalTradesList": [],
  "masterPnlComparison": {
    "masterPnl": 0,
    "childPnl": 1250.5,
    "replicationAccuracy": 89
  }
}
```

---

### GET `/api/v1/child/open-book` / `open-options` / `option-status`

Same response shapes as master endpoints (§3).

---

### GET `/api/v1/child/copied-trades`

**Response 200**
```json
{
  "trades": [
    {
      "id": 12345,
      "copyGroupId": "group-uuid",
      "masterId": "uuid",
      "masterName": "Master Trader",
      "instrument": "RELIANCE",
      "type": "BUY",
      "masterQty": 10,
      "myQty": 10,
      "status": "SUCCESS",
      "skipReason": null,
      "errorMessage": null,
      "failureReason": null,
      "latencyMs": 95,
      "entry": 2450.0,
      "current": 2460.0,
      "ltp": 2460.0,
      "pnl": 100.0,
      "time": "2026-05-28T10:00:00Z"
    }
  ]
}
```

---

## 5. Profile

### GET `/api/v1/users/me/profile`

**Response 200**
```json
{
  "userId": "uuid",
  "name": "John",
  "email": "john@example.com",
  "mobile": "+919876543210",
  "role": "CHILD",
  "createdAt": "2026-01-01T00:00:00Z",
  "telegramLinked": false,
  "brokerAccounts": [
    {
      "accountId": "uuid",
      "broker": "GROWW",
      "brokerName": "GROWW",
      "nickname": "My Groww",
      "status": "ACTIVE",
      "clientId": "",
      "fullName": "John Doe",
      "email": "john@example.com",
      "marginAvailable": 50000,
      "marginUsed": 10000,
      "totalMargin": 60000,
      "openPositionsCount": 2,
      "positions": [
        {
          "symbol": "RELIANCE",
          "qty": 10,
          "avgPrice": 2450,
          "ltp": 2460,
          "pnl": 100
        }
      ],
      "sessionActive": true,
      "isTokenExpired": false,
      "tokenExpiresAt": "2026-05-29T10:00:00Z",
      "tokenExpiresInHours": 18.5,
      "lastLoginAt": "2026-05-28T08:00:00Z"
    }
  ]
}
```

---

### PUT `/api/v1/users/me/profile`

**Request** (all fields optional)
```json
{
  "name": "John Updated",
  "displayName": "John Updated",
  "telegramChatId": "123456789"
}
```

---

## 6. Risk (child)

### GET `/api/v1/risk/rules`

**Response 200**
```json
{
  "userId": "uuid",
  "maxTradesPerDay": 50,
  "maxOpenPositions": 20,
  "maxCapitalExposure": 80,
  "marginCheckEnabled": true,
  "updatedAt": "2026-05-28T10:00:00Z"
}
```

---

### PUT `/api/v1/risk/rules`

**Request**
```json
{
  "maxTradesPerDay": 30,
  "maxOpenPositions": 10,
  "maxCapitalExposure": 70,
  "marginCheckEnabled": true
}
```

**Response 200**
```json
{
  "message": "Risk rules updated",
  "maxTradesPerDay": 30,
  "maxOpenPositions": 10,
  "maxCapitalExposure": 70,
  "marginCheckEnabled": true
}
```

---

### GET `/api/v1/risk/status?brokerAccountId=uuid`

**Response 200**
```json
{
  "maxTradesPerDay": 50,
  "tradesToday": 5,
  "tradesRemaining": 45,
  "maxOpenPositions": 20,
  "openPositions": 3,
  "positionsRemaining": 17,
  "maxCapitalExposure": 80,
  "marginCheckEnabled": true,
  "marginUtilizationPct": 45.2,
  "marginBlocked": false,
  "availableMargin": 50000,
  "usedMargin": 10000,
  "totalFunds": 60000,
  "copyPaused": false,
  "pausedUntil": null,
  "allowed": true
}
```

---

### POST `/api/v1/risk/pause`

**Request**
```json
{
  "reason": "Manual pause",
  "pauseUntil": "2026-05-29T09:15:00Z"
}
```

---

### POST `/api/v1/risk/check-trade?brokerAccountId=uuid`

**Request**
```json
{
  "symbol": "RELIANCE",
  "side": "BUY",
  "qty": 10
}
```

**Response 200**
```json
{
  "allowed": true,
  "warnings": [],
  "checks": [
    { "rule": "composite", "status": "OK", "message": "OK" }
  ],
  "symbol": "RELIANCE"
}
```

---

## 7. Broker

### POST `/api/v1/brokers/accounts/{accountId}/orders/close-position`

**Request**
```json
{
  "symbol": "RELIANCE",
  "qty": 10,
  "type": "SELL",
  "product": "MIS"
}
```

---

### POST `/api/v1/brokers/accounts/{accountId}/disconnect`

**Response 200** — Groww keeps API key for TOTP reconnect.

---

### POST `/api/v1/brokers/accounts/{accountId}/login`

**Groww:** `{}`  
**Zerodha:** `{ "requestToken": "..." }`  
**Fyers/Upstox:** `{ "authCode": "..." }`  
**Angel:** `{ "totpCode": "123456" }`

---

## 8. P&L

### GET `/api/v1/pnl/unrealized?brokerAccountId=uuid`

**Response 200**
```json
{
  "unrealizedPnl": 1250.5,
  "positions": [
    { "symbol": "RELIANCE", "qty": 10, "pnl": 100 }
  ]
}
```

---

### GET `/api/v1/pnl/summary?period=DAILY`

**Response 200**
```json
{
  "summary": [
    {
      "period": "daily",
      "realizedPnl": 0,
      "unrealizedPnl": 1250.5,
      "totalTrades": 0,
      "copiedTrades": 80,
      "failedCopies": 10,
      "winRate": 89
    }
  ]
}
```

---

## 9. Common errors

| HTTP | Meaning | FE action |
|------|---------|-----------|
| 401 | Invalid/expired JWT | Refresh token or re-login |
| 403 | Wrong role or not owner | Check user role |
| 404 | Subscription/account not found | Show message |
| 409 | Email already registered | Prompt login |
| 400 | Validation (password, scaling) | Show `message` from body |

**Broker session errors** in JSON:
```json
{
  "error": "Session expired. Please login again.",
  "errorCode": "SESSION_EXPIRED",
  "action": "RE_LOGIN"
}
```

---

## 10. UI → API quick map

| Screen | Primary APIs |
|--------|----------------|
| Login | `POST /auth/login` |
| Admin users | `GET /admin/users`, `POST /admin/users/master\|child` |
| Master overview | `GET /master/dashboard`, `/master/pnl-analytics` |
| Master trade logs | `GET /master/trade-logs` |
| Master latency/history | `GET /engine/trade-history`, `.../{eventId}` |
| Master open book/options | `GET /master/open-book`, `open-options`, `option-status` |
| Master square off | `POST /master/positions/square-off` |
| Child find masters | `GET /child/masters` (with JWT) |
| Child timeline | `GET /child/trade-timeline` |
| Child P&L | `GET /child/pnl-dashboard` |
| Profile | `GET /users/me/profile` |
| Risk | `GET/PUT /risk/rules`, `GET /risk/status` |

**Older full guide:** `FRONTEND-INTEGRATION-COMPLETE.md`  
**Swagger:** `{baseUrl}/swagger-ui.html` (request bodies may show `additionalProp` — use this doc instead)
