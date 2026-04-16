# Ascentra Copy Trading — Complete API Documentation

**Base URL:** `https://copy-trading-production-3981.up.railway.app`  
**Auth:** All endpoints except public ones require `Authorization: Bearer <accessToken>` header.  
**Content-Type:** `application/json` for all POST/PUT/PATCH/DELETE with body.

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Admin](#2-admin)
3. [Broker Accounts](#3-broker-accounts)
4. [Master](#4-master)
5. [Child](#5-child)
6. [Notifications](#6-notifications)
7. [Copy Logs](#7-copy-logs)

---

## 1. Authentication

### 1.1 Register

```
POST /api/v1/auth/register
```

**Auth:** None

**Request:**
```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "password": "SecurePass123",
  "role": "MASTER",
  "phone": "+919876543210"
}
```

| Field    | Type   | Required | Notes                          |
|----------|--------|----------|--------------------------------|
| name     | string | yes      |                                |
| email    | string | yes      | Must be unique                 |
| password | string | yes      | Min 6 chars                    |
| role     | string | yes      | `MASTER` or `CHILD`            |
| phone    | string | no       | For OTP login                  |

**Response (201):**
```json
{
  "userId": "uuid",
  "message": "User registered successfully"
}
```

---

### 1.2 Login (Email + Password)

```
POST /api/v1/auth/login
```

**Auth:** None

**Request:**
```json
{
  "email": "john@example.com",
  "password": "SecurePass123"
}
```

**Response (200):**
```json
{
  "accessToken": "eyJhbGci...",
  "refreshToken": "eyJhbGci...",
  "requires2FA": false,
  "user": {
    "userId": "uuid",
    "name": "John Doe",
    "email": "john@example.com",
    "role": "MASTER",
    "status": "ACTIVE",
    "phone": "+919876543210",
    "twoFactorEnabled": false,
    "createdAt": "2025-06-01T10:00:00Z",
    "brokerAccounts": []
  }
}
```

If 2FA is enabled, `requires2FA: true` and tokens will be null. Call `/auth/2fa/verify` next.

---

### 1.3 Send OTP

```
POST /api/v1/auth/send-otp
```

**Auth:** None

**Request:**
```json
{
  "phone": "+919876543210"
}
```

**Response (200) — Success:**
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

**Response (200) — Rate limited:**
```json
{
  "success": false,
  "error": "RATE_LIMITED",
  "message": "Please wait before requesting another OTP",
  "data": { "retryAfter": 45 }
}
```

**Response (200) — Phone not found:**
```json
{
  "success": false,
  "error": "PHONE_NOT_REGISTERED",
  "message": "No account found with this phone number"
}
```

---

### 1.4 Verify OTP

```
POST /api/v1/auth/verify-otp
```

**Auth:** None

**Request:**
```json
{
  "phone": "+919876543210",
  "otp": "123456"
}
```

**Response (200) — Success:** Same as login response (accessToken, refreshToken, user).

**Response (200) — Failure:**
```json
{
  "success": false,
  "error": "INVALID_OTP",
  "message": "Invalid OTP code"
}
```

Possible errors: `INVALID_OTP`, `OTP_EXPIRED`, `TOO_MANY_ATTEMPTS`

---

### 1.5 Refresh Token

```
POST /api/v1/auth/refresh-token
```

**Auth:** None

**Request:**
```json
{
  "refreshToken": "eyJhbGci..."
}
```

**Response (200):**
```json
{
  "accessToken": "eyJhbGci...",
  "refreshToken": "eyJhbGci..."
}
```

---

### 1.6 Logout

```
POST /api/v1/auth/logout
```

**Auth:** Bearer token

**Request:**
```json
{
  "refreshToken": "eyJhbGci..."
}
```

**Response (200):**
```json
{
  "message": "Logged out"
}
```

---

### 1.7 Forgot Password

```
POST /api/v1/auth/forgot-password
```

**Auth:** None

**Request:**
```json
{
  "email": "john@example.com"
}
```

**Response (200):**
```json
{
  "message": "Password reset link sent",
  "resetToken": "uuid-token"
}
```

---

### 1.8 Reset Password

```
POST /api/v1/auth/reset-password
```

**Auth:** None

**Request:**
```json
{
  "token": "uuid-reset-token",
  "newPassword": "NewSecurePass456"
}
```

**Response (200):**
```json
{
  "message": "Password reset successful"
}
```

---

### 1.9 Get Profile

```
GET /api/v1/auth/me
```

**Auth:** Bearer token

**Response (200):**
```json
{
  "userId": "uuid",
  "name": "John Doe",
  "email": "john@example.com",
  "role": "MASTER",
  "status": "ACTIVE",
  "phone": "+919876543210",
  "twoFactorEnabled": false,
  "createdAt": "2025-06-01T10:00:00Z",
  "brokerAccounts": []
}
```

---

### 1.10 Update Profile

```
PUT /api/v1/auth/me
```

**Auth:** Bearer token

**Request:**
```json
{
  "name": "John Updated",
  "phone": "+919876543211",
  "currentPassword": "OldPass123",
  "newPassword": "NewPass456"
}
```

All fields optional. `currentPassword` required only when changing password.

**Response (200):** Updated UserDto (same shape as GET /me).

---

### 1.11 Enable 2FA

```
POST /api/v1/auth/2fa/enable
```

**Auth:** Bearer token  
**Request:** No body.

**Response (200):**
```json
{
  "secret": "JBSWY3DPEHPK3PXP",
  "qrCodeUrl": "otpauth://totp/CopyTrading:john@example.com?secret=JBSWY3DPEHPK3PXP&issuer=CopyTrading"
}
```

---

### 1.12 Verify 2FA

```
POST /api/v1/auth/2fa/verify
```

**Auth:** Bearer token

**Request:**
```json
{
  "otp": "123456"
}
```

**Response (200):**
```json
{
  "verified": true,
  "accessToken": "eyJhbGci...",
  "refreshToken": "eyJhbGci..."
}
```

---

### 1.13 Disable 2FA

```
DELETE /api/v1/auth/2fa/disable
```

**Auth:** Bearer token

**Request:**
```json
{
  "password": "MyPassword123",
  "otp": "123456"
}
```

**Response (200):**
```json
{
  "message": "2FA disabled"
}
```

---

## 2. Admin

> All admin endpoints require `Authorization: Bearer <token>` from an ADMIN user.

### 2.1 List Users

```
GET /api/v1/admin/users?role=MASTER&status=ACTIVE&page=1&limit=20
```

| Param  | Type   | Required | Notes                              |
|--------|--------|----------|------------------------------------|
| role   | string | no       | `ADMIN`, `MASTER`, `CHILD`         |
| status | string | no       | `ACTIVE`, `INACTIVE`               |
| page   | int    | no       | Default 1                          |
| limit  | int    | no       | Default 20, max 100                |

**Response (200):**
```json
{
  "users": [
    {
      "userId": "uuid",
      "name": "John Doe",
      "email": "john@example.com",
      "role": "MASTER",
      "status": "ACTIVE",
      "phone": "+919876543210",
      "twoFactorEnabled": false,
      "createdAt": "2025-06-01T10:00:00Z",
      "brokerAccounts": []
    }
  ],
  "total": 50,
  "page": 1,
  "limit": 20
}
```

---

### 2.2 Create Master

```
POST /api/v1/admin/users/master
```

**Request:**
```json
{
  "name": "Master Trader",
  "email": "master@example.com",
  "password": "SecurePass123",
  "phone": "+919876543210"
}
```

**Response (201):**
```json
{
  "userId": "uuid",
  "message": "Master created"
}
```

---

### 2.3 Create Child

```
POST /api/v1/admin/users/child
```

**Request:**
```json
{
  "name": "Child Trader",
  "email": "child@example.com",
  "password": "SecurePass123",
  "phone": "+919876543211",
  "assignedMasterId": "uuid"
}
```

`assignedMasterId` is optional — auto-creates subscription if provided.

**Response (201):**
```json
{
  "userId": "uuid",
  "message": "Child created"
}
```

---

### 2.4 Get User

```
GET /api/v1/admin/users/{userId}
```

**Response (200):** UserDto object (same as /auth/me shape).

---

### 2.5 Update User

```
PUT /api/v1/admin/users/{userId}
```

**Request:**
```json
{
  "name": "Updated Name",
  "email": "new@example.com",
  "phone": "+919876543212"
}
```

All fields optional.

**Response (200):** Updated UserDto.

---

### 2.6 Activate User

```
PATCH /api/v1/admin/users/{userId}/activate
```

**Response (200):**
```json
{ "message": "User activated" }
```

---

### 2.7 Deactivate User

```
PATCH /api/v1/admin/users/{userId}/deactivate
```

**Response (200):**
```json
{ "message": "User deactivated" }
```

---

### 2.8 Delete User

```
DELETE /api/v1/admin/users/{userId}
```

**Response (200):**
```json
{ "message": "User deleted" }
```

---

### 2.9 Admin Analytics

```
GET /api/v1/admin/analytics
```

**Response (200):**
```json
{
  "totalUsers": 100,
  "totalMasters": 10,
  "totalChildren": 85,
  "totalAdmins": 5,
  "activeSubscriptions": 60,
  "totalTrades": 500
}
```

---

### 2.10 System Health

```
GET /api/v1/admin/system-health
```

**Response (200):**
```json
{
  "status": "UP",
  "database": "UP",
  "kafka": "DISABLED",
  "redis": "UP",
  "uptime": "..."
}
```

---

### 2.11 Admin Subscriptions

```
GET /api/v1/admin/subscriptions?masterId=uuid&status=ACTIVE
```

**Response (200):**
```json
{
  "subscriptions": [
    {
      "id": 1,
      "masterId": "uuid",
      "childId": "uuid",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ]
}
```

---

### 2.12 Admin Trade Logs

```
GET /api/v1/admin/trade-logs?userId=uuid&status=EXECUTED
```

**Response (200):**
```json
{
  "tradeLogs": [
    {
      "id": 1,
      "masterId": "uuid",
      "childId": "uuid",
      "type": "REPLICATED",
      "status": "EXECUTED",
      "message": "Order placed",
      "broker": "ZERODHA",
      "reference": "order-123",
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ]
}
```

---

### 2.13 Admin Broker Accounts

```
GET /api/v1/admin/brokers/accounts?userId=uuid&brokerId=ZERODHA
```

**Response (200):**
```json
{
  "accounts": [
    {
      "accountId": "uuid",
      "userId": "uuid",
      "brokerId": "ZERODHA",
      "clientId": "DRX617",
      "status": "ACTIVE"
    }
  ]
}
```

---

### 2.14 Admin Broker Status

```
GET /api/v1/admin/brokers/status
```

**Response (200):**
```json
{
  "brokers": [
    {
      "brokerId": "GROWW",
      "name": "Groww",
      "apiStatus": "UP",
      "latencyMs": 45,
      "lastChecked": "2025-06-01T10:00:00Z"
    },
    {
      "brokerId": "ZERODHA",
      "name": "Zerodha",
      "apiStatus": "UP",
      "latencyMs": 0,
      "lastChecked": "2025-06-01T10:00:00Z"
    }
  ]
}
```

---

## 3. Broker Accounts

> All broker endpoints require `Authorization: Bearer <token>`.

### 3.1 List Supported Brokers

```
GET /api/v1/brokers
```

**Response (200):**
```json
{
  "brokers": [
    {
      "brokerId": "GROWW",
      "name": "Groww",
      "isActive": true,
      "loginMethod": "token",
      "loginOptions": [
        {
          "method": "accessToken",
          "description": "Paste access token from Groww settings (no API key needed)",
          "requiredFields": ["accessToken"]
        },
        {
          "method": "apiKeyWithTotp",
          "description": "API key + TOTP code from authenticator app",
          "requiredFields": ["apiKey", "totpCode"]
        }
      ],
      "note": "Groww requires per-user credentials."
    },
    {
      "brokerId": "ZERODHA",
      "name": "Zerodha",
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "requestToken"
    },
    {
      "brokerId": "FYERS",
      "name": "Fyers",
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "authCode"
    },
    {
      "brokerId": "UPSTOX",
      "name": "Upstox",
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "authCode"
    },
    {
      "brokerId": "DHAN",
      "name": "Dhan",
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "tokenId",
      "loginOptions": [
        {
          "method": "accessToken",
          "description": "Paste access token from Dhan Web (Profile → DhanHQ Trading APIs)",
          "requiredFields": ["accessToken"]
        },
        {
          "method": "oauth",
          "description": "Login via Dhan (platform handles everything, user just clicks Connect)",
          "requiredFields": []
        }
      ]
    }
  ]
}
```

---

### 3.2 Link Broker Account

```
POST /api/v1/brokers/accounts
```

**Request (OAuth brokers — Zerodha, Fyers, Upstox, Dhan):**
```json
{
  "brokerId": "ZERODHA",
  "clientId": "DRX617",
  "accountNickname": "My Zerodha"
}
```

**Request (Groww — with access token):**
```json
{
  "brokerId": "GROWW",
  "clientId": "my-groww-id",
  "apiKey": "groww-api-key",
  "apiSecret": "groww-api-secret",
  "accessToken": "groww-access-token",
  "accountNickname": "My Groww"
}
```

| Field           | Type   | Required | Notes                                                    |
|-----------------|--------|----------|----------------------------------------------------------|
| brokerId        | string | yes      | `GROWW`, `ZERODHA`, `FYERS`, `UPSTOX`, `DHAN`           |
| clientId        | string | no       | Broker client ID (e.g. Zerodha: DRX617, Dhan: 1110569575) |
| apiKey          | string | no       | Only for Groww (per-user)                                |
| apiSecret       | string | no       | Only for Groww (per-user)                                |
| accessToken     | string | no       | If provided, account becomes ACTIVE immediately          |
| accountNickname | string | no       | Display name                                             |

**Response (201):**
```json
{
  "accountId": "uuid",
  "brokerId": "ZERODHA",
  "status": "AUTH_REQUIRED"
}
```

Status is `AUTH_REQUIRED` if no accessToken provided (need to login next), or `ACTIVE` if accessToken was provided.

---

### 3.3 List My Broker Accounts

```
GET /api/v1/brokers/accounts
```

**Response (200):**
```json
{
  "accounts": [
    {
      "accountId": "uuid",
      "brokerId": "ZERODHA",
      "brokerName": "Zerodha",
      "clientId": "DRX617",
      "nickname": "My Zerodha",
      "status": "ACTIVE",
      "sessionActive": true,
      "linkedAt": "2025-06-01T10:00:00Z",
      "lastSyncedAt": "2025-06-05T14:30:00Z",
      "margin": 0,
      "pnl": 0,
      "positions": 0,
      "orders": 0
    }
  ]
}
```

---

### 3.4 Get Broker Account Detail

```
GET /api/v1/brokers/accounts/{accountId}
```

**Response (200):** Same shape as single item in list above.

---

### 3.5 Update Broker Account

```
PUT /api/v1/brokers/accounts/{accountId}
```

**Request:**
```json
{
  "apiKey": "new-key",
  "apiSecret": "new-secret",
  "accountNickname": "Updated Name"
}
```

All fields optional.

**Response (200):**
```json
{ "message": "Account updated" }
```

---

### 3.6 Delete Broker Account

```
DELETE /api/v1/brokers/accounts/{accountId}
```

**Response (200):**
```json
{ "message": "Account unlinked" }
```

---

### 3.7 Login to Broker (Create Session)

```
POST /api/v1/brokers/accounts/{accountId}/login
```

**Request varies by broker:**

**Zerodha (after OAuth callback):**
```json
{ "requestToken": "oJf66qdo2VT2Dc3sf7sD8MlUV535mITR" }
```

**Fyers (after OAuth callback):**
```json
{ "authCode": "eyJhbGci..." }
```

**Upstox (after OAuth callback):**
```json
{ "authCode": "0SOtbJ" }
```

**Dhan — Step 1 (generate consent URL, send empty body):**
```json
{}
```

**Dhan — Step 2 (after browser login, send tokenId):**
```json
{ "authCode": "26f067e9-c242-46ac-9dc0-f839fcbcc70b" }
```

**Groww (TOTP method):**
```json
{ "totpCode": "123456" }
```

**Groww (secret method — empty body, uses stored apiKey+apiSecret):**
```json
{}
```

**Response (200) — Session created:**
```json
{
  "status": "SESSION_ACTIVE",
  "broker": "Zerodha",
  "expiresAt": "2025-06-06T10:00:00Z"
}
```

**Response (200) — Dhan consent generated (Step 1):**
```json
{
  "status": "CONSENT_GENERATED",
  "loginUrl": "https://auth.dhan.co/login/consentApp-login?consentAppId=uuid",
  "message": "Open loginUrl in browser. After login, Dhan redirects with tokenId. Call login again with {\"authCode\": \"tokenId\"}"
}
```

---

### 3.8 Get OAuth URL

```
GET /api/v1/brokers/accounts/{accountId}/oauth-url
```

Optional query param: `?redirectUri=https://your-frontend.com/callback`

**Response (200):**
```json
{
  "broker": "ZERODHA",
  "loginMethod": "oauth",
  "loginField": "requestToken",
  "oauthUrl": "https://kite.zerodha.com/connect/login?v=3&api_key=dm1fk4i61xyi29iu",
  "message": "Open oauthUrl in browser. After login, the callback will receive the requestToken automatically."
}
```

Frontend should open `oauthUrl` in a new window/popup. After broker login, user is redirected to the callback URL which returns the token.

---

### 3.9 Get Session Status

```
GET /api/v1/brokers/accounts/{accountId}/status
```

**Response (200):**
```json
{
  "accountId": "uuid",
  "status": "ACTIVE",
  "sessionActive": true,
  "broker": "ZERODHA",
  "brokerName": "Zerodha",
  "clientId": "DRX617",
  "connectionHealth": "good",
  "lastSyncedAt": "2025-06-05T14:30:00Z",
  "expiresAt": "2025-06-06T10:00:00Z"
}
```

`connectionHealth`: `good` | `degraded` | `down`

---

### 3.10 Dashboard (All-in-One)

```
GET /api/v1/brokers/accounts/{accountId}/dashboard
```

Returns profile + margin + positions + holdings + orders in a single call. Use this after connecting to a broker to show the user their demat account details.

**Response (200):**
```json
{
  "accountId": "uuid",
  "brokerId": "ZERODHA",
  "brokerName": "Zerodha",
  "clientId": "DRX617",
  "nickname": "My Zerodha",
  "status": "ACTIVE",
  "sessionActive": true,
  "profile": {
    "name": "John Doe",
    "email": "john@example.com",
    "clientId": "DRX617",
    "broker": "Zerodha",
    "exchanges": ["NSE", "BSE"],
    "products": ["CNC", "MIS", "NRML"]
  },
  "margin": {
    "availableMargin": 75000.50,
    "usedMargin": 25000.00,
    "totalFunds": 100000.50,
    "collateral": 0
  },
  "positions": [
    {
      "tradingsymbol": "RELIANCE",
      "quantity": 10,
      "average_price": 2500.0,
      "pnl": 150.0
    }
  ],
  "holdings": [
    {
      "tradingsymbol": "TCS",
      "quantity": 5,
      "average_price": 3500.0,
      "last_price": 3600.0
    }
  ],
  "orders": [
    {
      "order_id": "123456",
      "tradingsymbol": "RELIANCE",
      "transaction_type": "BUY",
      "quantity": 10,
      "status": "COMPLETE"
    }
  ]
}
```

> Profile fields vary by broker. `name`, `email`, `clientId`, `broker` are always present. Positions/holdings/orders arrays contain broker-native objects. If any section fails, it returns with an `error` field instead of data.

---

### 3.11 Test Connection

```
GET /api/v1/brokers/accounts/{accountId}/test
```

**Response (200) — Success:**
```json
{
  "accountId": "uuid",
  "connectionHealth": "good",
  "sessionActive": true,
  "margin": 50000.0,
  "brokerName": "Zerodha",
  "message": "Connection successful"
}
```

**Response (200) — No session:**
```json
{
  "accountId": "uuid",
  "connectionHealth": "down",
  "message": "No active session. Login first."
}
```

---

### 3.11 Get Margin

```
GET /api/v1/brokers/accounts/{accountId}/margin
```

**Response (200):**
```json
{
  "availableMargin": 75000.50,
  "usedMargin": 25000.00,
  "totalFunds": 100000.50,
  "collateral": 0
}
```

---

### 3.12 Get Positions

```
GET /api/v1/brokers/accounts/{accountId}/positions
```

**Response (200):**
```json
{
  "positions": [
    {
      "tradingsymbol": "RELIANCE",
      "quantity": 10,
      "average_price": 2500.0,
      "pnl": 150.0
    }
  ]
}
```

> Position object shape varies by broker. Frontend should handle flexible fields.

---

### 3.13 Get Orders

```
GET /api/v1/brokers/accounts/{accountId}/orders
```

**Response (200):**
```json
{
  "orders": [
    {
      "order_id": "123456",
      "tradingsymbol": "RELIANCE",
      "transaction_type": "BUY",
      "quantity": 10,
      "status": "COMPLETE"
    }
  ]
}
```

> Order object shape varies by broker.

---

### 3.14 Get Trades

```
GET /api/v1/brokers/accounts/{accountId}/trades
```

**Response (200):**
```json
{
  "trades": [
    {
      "trade_id": "789",
      "order_id": "123456",
      "tradingsymbol": "RELIANCE",
      "quantity": 10,
      "average_price": 2500.0
    }
  ]
}
```

---

### 3.15 Get Holdings

```
GET /api/v1/brokers/accounts/{accountId}/holdings
```

**Response (200):**
```json
{
  "holdings": [
    {
      "tradingsymbol": "TCS",
      "quantity": 5,
      "average_price": 3500.0,
      "last_price": 3600.0,
      "pnl": 500.0
    }
  ]
}
```

---

### 3.16 Close Position

```
POST /api/v1/brokers/accounts/{accountId}/orders/close-position
```

**Request:**
```json
{
  "symbol": "RELIANCE",
  "qty": 10,
  "type": "SELL",
  "product": "MIS"
}
```

| Field   | Type   | Required | Notes                        |
|---------|--------|----------|------------------------------|
| symbol  | string | yes      | Trading symbol               |
| qty     | int    | yes      | Quantity to close            |
| type    | string | no       | `BUY` or `SELL` (default SELL) |
| product | string | no       | `MIS`, `CNC`, etc (default MIS) |

**Response (200):**
```json
{
  "message": "Position close order placed",
  "response": { ... }
}
```

---

### 3.17 Cancel Order

```
DELETE /api/v1/brokers/accounts/{accountId}/orders/{orderId}
```

**Response (200):**
```json
{
  "message": "Order cancelled",
  "response": { ... }
}
```

---

### 3.18 Broker OAuth Callback

```
GET /api/v1/brokers/callback?request_token=xxx&status=success
```

**Auth:** None (public endpoint, called by broker redirect)

This is the redirect URL that brokers call after user login. The response shows the token to use for the login API call.

**Response (200):**
```json
{
  "message": "Broker OAuth callback received. Use the token below to call the login API.",
  "broker": "ZERODHA",
  "requestToken": "oJf66qdo...",
  "loginBody": { "requestToken": "oJf66qdo..." }
}
```

Frontend flow: Open OAuth URL → broker redirects to callback → read token from callback response → call POST /login with the `loginBody`.

---

## 4. Master

> All master endpoints require `Authorization: Bearer <token>` from a MASTER user.

### 4.1 List Children

```
GET /api/v1/master/children
```

**Response (200):**
```json
{
  "children": [
    {
      "childId": "uuid",
      "name": "Child Trader",
      "email": "child@example.com",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "subscribedAt": "2025-06-01T10:00:00Z"
    }
  ]
}
```

`copyingStatus`: `ACTIVE` | `PAUSED` | `INACTIVE` | `PENDING_APPROVAL` | `REJECTED`

---

### 4.2 Link Child (Master-initiated, bypasses approval)

```
POST /api/v1/master/children/{childId}/link
```

**Request:**
```json
{
  "scalingFactor": 1.5
}
```

`scalingFactor` is optional (default 1.0).

**Response (200):**
```json
{ "message": "Child linked successfully" }
```

If child had a pending request, it gets auto-approved.

---

### 4.3 Bulk Link Children

```
POST /api/v1/master/children/bulk-link
```

**Request:**
```json
{
  "children": [
    { "childId": "uuid-1", "scalingFactor": 1.0 },
    { "childId": "uuid-2", "scalingFactor": 2.0 }
  ]
}
```

**Response (200):**
```json
{
  "results": [
    { "childId": "uuid-1", "status": "LINKED", "subscriptionId": 1 },
    { "childId": "uuid-2", "status": "APPROVED", "subscriptionId": 2 }
  ]
}
```

Possible statuses: `LINKED`, `APPROVED`, `REACTIVATED`, `ALREADY_LINKED`

---

### 4.4 Unlink Child

```
DELETE /api/v1/master/children/{childId}/unlink
```

**Response (200):**
```json
{ "message": "Child unlinked" }
```

Sets status to INACTIVE (preserves record for re-subscribe auto-approval).

---

### 4.5 Bulk Unlink Children

```
POST /api/v1/master/children/bulk-unlink
```

**Request:**
```json
{
  "childIds": ["uuid-1", "uuid-2"]
}
```

**Response (200):**
```json
{
  "results": [
    { "childId": "uuid-1", "status": "UNLINKED" },
    { "childId": "uuid-2", "status": "NOT_FOUND" }
  ]
}
```

---

### 4.6 Pause Child

```
POST /api/v1/master/children/{childId}/pause
```

**Response (200):**
```json
{ "message": "Child copying paused" }
```

Only works on ACTIVE subscriptions.

---

### 4.7 Resume Child

```
POST /api/v1/master/children/{childId}/resume
```

**Response (200):**
```json
{ "message": "Child copying resumed" }
```

Only works on PAUSED subscriptions.

---

### 4.8 List Pending Approvals

```
GET /api/v1/master/children/pending
```

**Response (200):**
```json
{
  "pendingApprovals": [
    {
      "childId": "uuid",
      "name": "Child Trader",
      "email": "child@example.com",
      "requestedAt": "2025-06-01T10:00:00Z",
      "subscriptionId": 5
    }
  ]
}
```

---

### 4.9 Approve Child

```
POST /api/v1/master/children/{childId}/approve
```

**Response (200):**
```json
{ "message": "Child approved" }
```

---

### 4.10 Reject Child

```
POST /api/v1/master/children/{childId}/reject
```

**Response (200):**
```json
{ "message": "Child rejected" }
```

---

### 4.11 Decline Child (alias for reject)

```
POST /api/v1/master/children/{childId}/decline
```

Same as reject.

---

### 4.12 Get Scaling

```
GET /api/v1/master/children/{childId}/scaling
```

**Response (200):**
```json
{
  "childId": "uuid",
  "scalingFactor": 1.5
}
```

---

### 4.13 Update Scaling

```
PUT /api/v1/master/children/{childId}/scaling
```

**Request:**
```json
{
  "scalingFactor": 2.0
}
```

Range: 0.01 to 10.0

**Response (200):**
```json
{
  "childId": "uuid",
  "scalingFactor": 2.0
}
```

---

### 4.14 Master Analytics

```
GET /api/v1/master/analytics
```

**Response (200):**
```json
{
  "totalPnl": 0,
  "winRate": 0,
  "totalTrades": 15,
  "totalReplications": 12,
  "childPerformance": [
    {
      "childId": "uuid",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE"
    }
  ]
}
```

---

### 4.15 Trade History

```
GET /api/v1/master/trade-history
```

**Response (200):**
```json
{
  "trades": [
    {
      "id": 1,
      "masterId": "uuid",
      "childId": "uuid",
      "type": "REPLICATED",
      "status": "EXECUTED",
      "message": "Order placed",
      "broker": "ZERODHA",
      "reference": "order-123",
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ]
}
```

---

### 4.16 Set Active Account

```
POST /api/v1/master/active-account
```

**Request:**
```json
{
  "brokerAccountId": "uuid"
}
```

**Response (200):**
```json
{
  "brokerAccountId": "uuid",
  "message": "Active account set"
}
```

---

### 4.17 Get Active Account

```
GET /api/v1/master/active-account
```

**Response (200):**
```json
{
  "brokerAccountId": "uuid"
}
```

If none set:
```json
{
  "brokerAccountId": "",
  "message": "No active account set"
}
```

---

### 4.18 Clear Active Account

```
DELETE /api/v1/master/active-account
```

**Response (200):**
```json
{ "message": "Active account cleared" }
```

---

### 4.19 Master Copy Logs

```
GET /api/v1/master/copy/logs
```

**Response (200):**
```json
{
  "logs": [
    {
      "id": 1,
      "masterId": "uuid",
      "childId": "uuid",
      "masterTradeId": "order-123",
      "symbol": "RELIANCE",
      "qty": 10,
      "tradeType": "BUY",
      "masterStatus": "EXECUTED",
      "childStatus": "EXECUTED",
      "errorMessage": null,
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ]
}
```

---

### 4.20 Master Earnings

```
GET /api/v1/master/earnings
```

**Response (200):**
```json
{
  "totalEarnings": 0,
  "thisMonth": 0,
  "lastMonth": 0,
  "pendingPayout": 0,
  "currency": "INR",
  "monthlyBreakdown": [
    { "month": "2025-01", "earnings": 0 },
    { "month": "2025-02", "earnings": 0 }
  ]
}
```

---

### 4.21 Master Payouts

```
GET /api/v1/master/payouts
```

**Response (200):**
```json
{
  "payouts": [],
  "totalPaid": 0,
  "currency": "INR"
}
```

---

## 5. Child

> All child endpoints require `Authorization: Bearer <token>` from a CHILD user.

### 5.1 List Available Masters

```
GET /api/v1/child/masters
```

**Response (200):**
```json
{
  "masters": [
    {
      "masterId": "uuid",
      "name": "Master Trader",
      "winRate": 0,
      "totalTrades": 0,
      "avgPnl": 0,
      "subscribers": 0
    }
  ]
}
```

---

### 5.2 Subscribe to Master

```
POST /api/v1/child/subscriptions
```

**Request:**
```json
{
  "masterId": "uuid",
  "brokerAccountId": "uuid",
  "scalingFactor": 1.0
}
```

| Field           | Type   | Required | Notes                    |
|-----------------|--------|----------|--------------------------|
| masterId        | UUID   | yes      | Master to subscribe to   |
| brokerAccountId | UUID   | no       | Broker account to use    |
| scalingFactor   | double | no       | 0.01–10.0, default 1.0   |

**Response (201) — New subscription (needs approval):**
```json
{
  "subscriptionId": 5,
  "status": "PENDING_APPROVAL",
  "message": "Subscription request sent. Waiting for master approval."
}
```

**Response (201) — Re-subscribe (previously approved):**
```json
{
  "subscriptionId": 5,
  "status": "ACTIVE",
  "message": "Re-subscribed successfully (previously approved)"
}
```

---

### 5.3 Bulk Subscribe

```
POST /api/v1/child/subscriptions/bulk
```

**Request:**
```json
{
  "masters": [
    { "masterId": "uuid-1", "brokerAccountId": "uuid", "scalingFactor": 1.0 },
    { "masterId": "uuid-2", "scalingFactor": 2.0 }
  ]
}
```

**Response (201):**
```json
{
  "results": [
    { "masterId": "uuid-1", "status": "PENDING_APPROVAL", "subscriptionId": 5 },
    { "masterId": "uuid-2", "status": "RE_SUBSCRIBED", "subscriptionId": 6 }
  ]
}
```

Possible statuses: `PENDING_APPROVAL`, `RE_SUBSCRIBED`, `ALREADY_SUBSCRIBED`

---

### 5.4 Unsubscribe from Master

```
DELETE /api/v1/child/subscriptions/{masterId}
```

**Response (200):**
```json
{ "message": "Unsubscribed" }
```

Sets status to INACTIVE (preserves record so re-subscribe skips approval).

---

### 5.5 Bulk Unsubscribe

```
POST /api/v1/child/subscriptions/bulk-unsubscribe
```

**Request:**
```json
{
  "masterIds": ["uuid-1", "uuid-2"]
}
```

**Response (200):**
```json
{
  "results": [
    { "masterId": "uuid-1", "status": "UNSUBSCRIBED" },
    { "masterId": "uuid-2", "status": "NOT_FOUND" }
  ]
}
```

---

### 5.6 List My Subscriptions

```
GET /api/v1/child/subscriptions
```

**Response (200):**
```json
{
  "subscriptions": [
    {
      "masterId": "uuid",
      "masterName": "Master Trader",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "subscribedAt": "2025-06-01T10:00:00Z",
      "brokerAccountId": "uuid"
    }
  ]
}
```

Excludes INACTIVE subscriptions.

---

### 5.7 Get Scaling

```
GET /api/v1/child/scaling?masterId=uuid
```

`masterId` is optional. If omitted, returns first subscription's scaling.

**Response (200):**
```json
{
  "scalingFactor": 1.5
}
```

---

### 5.8 Update Scaling

```
PUT /api/v1/child/scaling
```

**Request:**
```json
{
  "masterId": "uuid",
  "scalingFactor": 2.0
}
```

Range: 0.01 to 10.0

**Response (200):**
```json
{
  "scalingFactor": 2.0
}
```

---

### 5.9 Pause Copying

```
POST /api/v1/child/copying/pause
```

**Request:**
```json
{
  "masterId": "uuid"
}
```

**Response (200):**
```json
{ "message": "Copying paused" }
```

---

### 5.10 Resume Copying

```
POST /api/v1/child/copying/resume
```

**Request:**
```json
{
  "masterId": "uuid"
}
```

**Response (200):**
```json
{ "message": "Copying resumed" }
```

---

### 5.11 Copied Trades

```
GET /api/v1/child/copied-trades
```

**Response (200):**
```json
{
  "trades": [
    {
      "id": 1,
      "masterId": "uuid",
      "childId": "uuid",
      "type": "REPLICATED",
      "status": "EXECUTED",
      "message": "Order placed",
      "broker": "ZERODHA",
      "reference": "order-123",
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ]
}
```

---

### 5.12 Child Analytics

```
GET /api/v1/child/analytics
```

**Response (200):**
```json
{
  "totalPnl": 0,
  "copiedTrades": 12,
  "failedReplications": 1,
  "masterPnlComparison": {}
}
```

---

### 5.13 Child Copy Logs

```
GET /api/v1/child/copy/logs
```

**Response (200):**
```json
{
  "logs": [
    {
      "id": 1,
      "masterId": "uuid",
      "childId": "uuid",
      "masterTradeId": "order-123",
      "symbol": "RELIANCE",
      "qty": 10,
      "tradeType": "BUY",
      "masterStatus": "EXECUTED",
      "childStatus": "EXECUTED",
      "errorMessage": null,
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ]
}
```

---

## 6. Notifications

> All notification endpoints require `Authorization: Bearer <token>`.

### 6.1 List Notifications

```
GET /api/v1/notifications
```

**Response (200):**
```json
{
  "notifications": [
    {
      "id": "uuid",
      "type": "SUBSCRIPTION_REQUEST",
      "title": "New subscriber",
      "message": "Child Trader wants to copy your trades",
      "read": false,
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ]
}
```

Sorted by createdAt descending (newest first).

---

### 6.2 Mark as Read

```
PATCH /api/v1/notifications/{id}/read
```

**Response (200):**
```json
{
  "message": "Marked as read",
  "id": "uuid"
}
```

---

### 6.3 Mark All as Read

```
POST /api/v1/notifications/read-all
```

**Response (200):**
```json
{
  "message": "All notifications marked as read",
  "updated": 5
}
```

---

### 6.4 Delete Notification

```
DELETE /api/v1/notifications/{id}
```

**Response (200):**
```json
{ "message": "Notification deleted" }
```

---

## 7. Copy Logs

### 7.1 Get All Copy Logs

```
GET /api/v1/copy/logs
```

**Auth:** Bearer token

**Response (200):**
```json
{
  "logs": [
    {
      "id": 1,
      "masterId": "uuid",
      "childId": "uuid",
      "masterTradeId": "order-123",
      "symbol": "RELIANCE",
      "qty": 10,
      "tradeType": "BUY",
      "masterStatus": "EXECUTED",
      "childStatus": "EXECUTED",
      "errorMessage": null,
      "createdAt": "2025-06-01T10:00:00Z"
    }
  ]
}
```

---

## Error Responses

All errors follow this format:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Descriptive error message"
}
```

| HTTP Code | Meaning                                    |
|-----------|--------------------------------------------|
| 400       | Bad request / validation error             |
| 401       | Unauthorized (missing or invalid token)    |
| 403       | Forbidden (wrong role)                     |
| 404       | Resource not found                         |
| 409       | Conflict (duplicate resource)              |
| 502       | Bad Gateway (broker API error)             |

---

## Broker Connection Flow (Frontend Integration)

### OAuth Brokers (Zerodha, Fyers, Upstox, Dhan)

```
1. POST /brokers/accounts          → Link account (status: AUTH_REQUIRED)
2. GET  /brokers/accounts/{id}/oauth-url  → Get OAuth URL
3. Open oauthUrl in popup/new tab  → User logs in at broker
4. Broker redirects to /brokers/callback → Returns token
5. POST /brokers/accounts/{id}/login     → Send token, get SESSION_ACTIVE
6. GET  /brokers/accounts/{id}/margin    → Verify connection works
```

### Groww (Direct Token)

```
1. POST /brokers/accounts          → Link with accessToken (status: ACTIVE immediately)
   OR
1. POST /brokers/accounts          → Link with apiKey + apiSecret
2. POST /brokers/accounts/{id}/login → Send {} (uses stored secret) or {"totpCode":"123456"}
```

### Dhan (Direct Token Alternative)

```
1. POST /brokers/accounts          → Link with accessToken from Dhan Web (status: ACTIVE immediately)
```

---

## Quick Reference — All Endpoints

| # | Method | Endpoint | Auth | Description |
|---|--------|----------|------|-------------|
| | **AUTH** | | | |
| 1 | POST | `/api/v1/auth/register` | No | Register user |
| 2 | POST | `/api/v1/auth/login` | No | Login |
| 3 | POST | `/api/v1/auth/send-otp` | No | Send OTP |
| 4 | POST | `/api/v1/auth/verify-otp` | No | Verify OTP |
| 5 | POST | `/api/v1/auth/refresh-token` | No | Refresh token |
| 6 | POST | `/api/v1/auth/logout` | Yes | Logout |
| 7 | POST | `/api/v1/auth/forgot-password` | No | Forgot password |
| 8 | POST | `/api/v1/auth/reset-password` | No | Reset password |
| 9 | GET | `/api/v1/auth/me` | Yes | Get profile |
| 10 | PUT | `/api/v1/auth/me` | Yes | Update profile |
| 11 | POST | `/api/v1/auth/2fa/enable` | Yes | Enable 2FA |
| 12 | POST | `/api/v1/auth/2fa/verify` | Yes | Verify 2FA |
| 13 | DELETE | `/api/v1/auth/2fa/disable` | Yes | Disable 2FA |
| | **ADMIN** | | | |
| 14 | GET | `/api/v1/admin/users` | Admin | List users |
| 15 | POST | `/api/v1/admin/users/master` | Admin | Create master |
| 16 | POST | `/api/v1/admin/users/child` | Admin | Create child |
| 17 | GET | `/api/v1/admin/users/{userId}` | Admin | Get user |
| 18 | PUT | `/api/v1/admin/users/{userId}` | Admin | Update user |
| 19 | PATCH | `/api/v1/admin/users/{userId}/activate` | Admin | Activate user |
| 20 | PATCH | `/api/v1/admin/users/{userId}/deactivate` | Admin | Deactivate user |
| 21 | DELETE | `/api/v1/admin/users/{userId}` | Admin | Delete user |
| 22 | GET | `/api/v1/admin/analytics` | Admin | Analytics |
| 23 | GET | `/api/v1/admin/system-health` | Admin | System health |
| 24 | GET | `/api/v1/admin/subscriptions` | Admin | Subscriptions |
| 25 | GET | `/api/v1/admin/trade-logs` | Admin | Trade logs |
| 26 | GET | `/api/v1/admin/brokers/accounts` | Admin | Broker accounts |
| 27 | GET | `/api/v1/admin/brokers/status` | Admin | Broker status |
| | **BROKERS** | | | |
| 28 | GET | `/api/v1/brokers` | Yes | List brokers |
| 29 | POST | `/api/v1/brokers/accounts` | Yes | Link account |
| 30 | GET | `/api/v1/brokers/accounts` | Yes | List accounts |
| 31 | GET | `/api/v1/brokers/accounts/{id}` | Yes | Get account |
| 32 | PUT | `/api/v1/brokers/accounts/{id}` | Yes | Update account |
| 33 | DELETE | `/api/v1/brokers/accounts/{id}` | Yes | Delete account |
| 34 | POST | `/api/v1/brokers/accounts/{id}/login` | Yes | Login to broker |
| 35 | GET | `/api/v1/brokers/accounts/{id}/oauth-url` | Yes | Get OAuth URL |
| 36 | GET | `/api/v1/brokers/accounts/{id}/status` | Yes | Session status |
| 37 | GET | `/api/v1/brokers/accounts/{id}/test` | Yes | Test connection |
| 38 | GET | `/api/v1/brokers/accounts/{id}/dashboard` | Yes | **Dashboard (all-in-one)** |
| 39 | GET | `/api/v1/brokers/accounts/{id}/margin` | Yes | Get margin |
| 40 | GET | `/api/v1/brokers/accounts/{id}/positions` | Yes | Get positions |
| 41 | GET | `/api/v1/brokers/accounts/{id}/orders` | Yes | Get orders |
| 42 | GET | `/api/v1/brokers/accounts/{id}/trades` | Yes | Get trades |
| 43 | GET | `/api/v1/brokers/accounts/{id}/holdings` | Yes | Get holdings |
| 44 | POST | `/api/v1/brokers/accounts/{id}/orders/close-position` | Yes | Close position |
| 45 | DELETE | `/api/v1/brokers/accounts/{id}/orders/{orderId}` | Yes | Cancel order |
| 46 | GET | `/api/v1/brokers/callback` | No | OAuth callback |
| | **MASTER** | | | |
| 46 | GET | `/api/v1/master/children` | Yes | List children |
| 47 | POST | `/api/v1/master/children/{childId}/link` | Yes | Link child |
| 48 | POST | `/api/v1/master/children/bulk-link` | Yes | Bulk link |
| 49 | DELETE | `/api/v1/master/children/{childId}/unlink` | Yes | Unlink child |
| 50 | POST | `/api/v1/master/children/bulk-unlink` | Yes | Bulk unlink |
| 51 | POST | `/api/v1/master/children/{childId}/pause` | Yes | Pause child |
| 52 | POST | `/api/v1/master/children/{childId}/resume` | Yes | Resume child |
| 53 | GET | `/api/v1/master/children/pending` | Yes | Pending approvals |
| 54 | POST | `/api/v1/master/children/{childId}/approve` | Yes | Approve child |
| 55 | POST | `/api/v1/master/children/{childId}/reject` | Yes | Reject child |
| 56 | POST | `/api/v1/master/children/{childId}/decline` | Yes | Decline child |
| 57 | GET | `/api/v1/master/children/{childId}/scaling` | Yes | Get scaling |
| 58 | PUT | `/api/v1/master/children/{childId}/scaling` | Yes | Update scaling |
| 59 | GET | `/api/v1/master/analytics` | Yes | Analytics |
| 60 | GET | `/api/v1/master/trade-history` | Yes | Trade history |
| 61 | POST | `/api/v1/master/active-account` | Yes | Set active account |
| 62 | GET | `/api/v1/master/active-account` | Yes | Get active account |
| 63 | DELETE | `/api/v1/master/active-account` | Yes | Clear active account |
| 64 | GET | `/api/v1/master/copy/logs` | Yes | Master copy logs |
| 65 | GET | `/api/v1/master/earnings` | Yes | Earnings |
| 66 | GET | `/api/v1/master/payouts` | Yes | Payouts |
| | **CHILD** | | | |
| 67 | GET | `/api/v1/child/masters` | Yes | List masters |
| 68 | POST | `/api/v1/child/subscriptions` | Yes | Subscribe |
| 69 | POST | `/api/v1/child/subscriptions/bulk` | Yes | Bulk subscribe |
| 70 | DELETE | `/api/v1/child/subscriptions/{masterId}` | Yes | Unsubscribe |
| 71 | POST | `/api/v1/child/subscriptions/bulk-unsubscribe` | Yes | Bulk unsubscribe |
| 72 | GET | `/api/v1/child/subscriptions` | Yes | List subscriptions |
| 73 | GET | `/api/v1/child/scaling` | Yes | Get scaling |
| 74 | PUT | `/api/v1/child/scaling` | Yes | Update scaling |
| 75 | POST | `/api/v1/child/copying/pause` | Yes | Pause copying |
| 76 | POST | `/api/v1/child/copying/resume` | Yes | Resume copying |
| 77 | GET | `/api/v1/child/copied-trades` | Yes | Copied trades |
| 78 | GET | `/api/v1/child/analytics` | Yes | Analytics |
| 79 | GET | `/api/v1/child/copy/logs` | Yes | Child copy logs |
| | **NOTIFICATIONS** | | | |
| 80 | GET | `/api/v1/notifications` | Yes | List notifications |
| 81 | PATCH | `/api/v1/notifications/{id}/read` | Yes | Mark as read |
| 82 | POST | `/api/v1/notifications/read-all` | Yes | Mark all read |
| 83 | DELETE | `/api/v1/notifications/{id}` | Yes | Delete notification |
| | **COPY LOGS** | | | |
| 84 | GET | `/api/v1/copy/logs` | Yes | All copy logs |


---

## 8. Broker Dashboard — Demat Account Details (NEW)

> After a user connects any broker and logs in, call this single API to fetch all their demat account details.

### Endpoint

```
GET /api/v1/brokers/accounts/{accountId}/dashboard
```

**Auth:** Bearer token

### When to Call

Call this after the broker login is successful (status = `SESSION_ACTIVE`). This is the main API to show the user's connected broker account page.

### Flow

```
User connects broker → Login succeeds → Call /dashboard → Show all details
```

```
Step 1: POST /brokers/accounts                    → Link broker (get accountId)
Step 2: POST /brokers/accounts/{id}/login          → Login (get SESSION_ACTIVE)
Step 3: GET  /brokers/accounts/{id}/dashboard      → Fetch everything ✅
```

### Response

```json
{
  "accountId": "60120a19-23b8-4f6a-8007-81c2054a509e",
  "brokerId": "ZERODHA",
  "brokerName": "Zerodha",
  "clientId": "DRX617",
  "nickname": "My Zerodha",
  "status": "ACTIVE",
  "sessionActive": true,

  "profile": {
    "name": "Aniket Dhoke",
    "email": "aniket@example.com",
    "clientId": "DRX617",
    "broker": "Zerodha",
    "exchanges": ["NSE", "BSE", "NFO"],
    "products": ["CNC", "MIS", "NRML"]
  },

  "margin": {
    "availableMargin": 75000.50,
    "usedMargin": 25000.00,
    "totalFunds": 100000.50,
    "collateral": 0
  },

  "positions": [
    {
      "tradingsymbol": "RELIANCE",
      "quantity": 10,
      "average_price": 2500.0,
      "pnl": 150.0,
      "product": "MIS"
    }
  ],

  "holdings": [
    {
      "tradingsymbol": "TCS",
      "quantity": 5,
      "average_price": 3500.0,
      "last_price": 3600.0,
      "pnl": 500.0
    }
  ],

  "orders": [
    {
      "order_id": "123456789",
      "tradingsymbol": "RELIANCE",
      "transaction_type": "BUY",
      "quantity": 10,
      "price": 2500.0,
      "status": "COMPLETE",
      "order_type": "MARKET"
    }
  ]
}
```

### Response Fields Explained

| Field | Type | Description |
|-------|------|-------------|
| `accountId` | string | Broker account UUID in our system |
| `brokerId` | string | `GROWW`, `ZERODHA`, `FYERS`, `UPSTOX`, `DHAN` |
| `brokerName` | string | Display name (e.g. "Zerodha") |
| `clientId` | string | User's broker client ID |
| `nickname` | string | User-given nickname for this account |
| `status` | string | `ACTIVE`, `AUTH_REQUIRED`, `INACTIVE` |
| `sessionActive` | boolean | Whether broker session is live |
| `profile` | object | User's broker profile (name, email, clientId, broker) |
| `margin` | object | Balance details (availableMargin, usedMargin, totalFunds, collateral) |
| `positions` | array | Open intraday/F&O positions |
| `holdings` | array | Long-term stock holdings |
| `orders` | array | Today's orders |

### Profile Fields by Broker

| Broker | Fields Returned |
|--------|----------------|
| Zerodha | name, email, clientId, broker, exchanges, products |
| Groww | name, email, clientId, broker |
| Fyers | name, email, clientId, broker, pan |
| Upstox | name, email, clientId, broker, exchanges |
| Dhan | name, email, clientId, broker |

### Error Handling

If any section fails (e.g. broker API is down), that section returns with an `error` field instead of crashing the whole response:

```json
{
  "accountId": "uuid",
  "brokerId": "ZERODHA",
  "brokerName": "Zerodha",
  "profile": { "error": "Zerodha API timeout" },
  "margin": { "availableMargin": 75000, "usedMargin": 0, "totalFunds": 75000, "collateral": 0 },
  "positions": [],
  "holdings": [],
  "orders": { "error": "Zerodha orders API 502" }
}
```

Frontend should check for `error` key in each section and show a fallback message.

### If Session Expired

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "No active broker session. Login first."
}
```

Frontend should redirect user to re-login to the broker when this happens.

### Individual Endpoints (if you need just one section)

If you don't need everything at once, you can call these individually:

| What | Endpoint | Description |
|------|----------|-------------|
| Balance only | `GET /brokers/accounts/{id}/margin` | Available margin, used margin, total funds |
| Positions only | `GET /brokers/accounts/{id}/positions` | Open positions |
| Holdings only | `GET /brokers/accounts/{id}/holdings` | Long-term holdings |
| Orders only | `GET /brokers/accounts/{id}/orders` | Today's orders |
| Trades only | `GET /brokers/accounts/{id}/trades` | Today's executed trades |
| Connection check | `GET /brokers/accounts/{id}/test` | Quick health check (tries margin) |
| Session status | `GET /brokers/accounts/{id}/status` | Session active/expired, connection health |

### Example: Frontend Page Layout

```
┌─────────────────────────────────────────────────┐
│  Zerodha - DRX617 (My Zerodha)        [Active]  │
│  Aniket Dhoke | aniket@example.com               │
├─────────────────────────────────────────────────┤
│  💰 Balance                                      │
│  Available: ₹75,000.50                           │
│  Used:      ₹25,000.00                           │
│  Total:     ₹1,00,000.50                         │
├─────────────────────────────────────────────────┤
│  📊 Positions (1)                                │
│  RELIANCE  BUY 10  ₹2,500  P&L: +₹150          │
├─────────────────────────────────────────────────┤
│  📦 Holdings (1)                                 │
│  TCS  5 shares  Avg: ₹3,500  LTP: ₹3,600       │
├─────────────────────────────────────────────────┤
│  📋 Orders (1)                                   │
│  RELIANCE  BUY 10  MARKET  ✅ COMPLETE           │
└─────────────────────────────────────────────────┘
```

### cURL Example

```bash
# Get auth token
TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"aniket@trading.com","password":"Trade1234"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

# Fetch dashboard
curl -s "$BASE/api/v1/brokers/accounts/{accountId}/dashboard" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```


---

## 8. Trade Engine

### 8.1 Execute Trade

```
POST /api/v1/trades/execute
```

**Auth:** Bearer token (Master or Child)

When a Master places a trade, it auto-replicates to all subscribed children.

**Request:**
```json
{
  "brokerAccountId": "uuid",
  "instrument": "RELIANCE",
  "exchange": "NSE",
  "segment": "EQUITY",
  "orderType": "MARKET",
  "transactionType": "BUY",
  "quantity": 10,
  "price": 0,
  "product": "MIS",
  "validity": "DAY"
}
```

**Response:**
```json
{
  "tradeId": "uuid",
  "brokerOrderId": "123456",
  "status": "EXECUTED",
  "replicationsTriggered": 3,
  "replicationDetails": [
    { "childId": "uuid", "status": "SUCCESS", "message": "Order placed: 789", "broker": "FYERS" },
    { "childId": "uuid", "status": "FAILED", "message": "Broker session inactive", "broker": "DHAN" }
  ]
}
```

### 8.2 List Trades

```
GET /api/v1/trades?status=EXECUTED
```

**Response:**
```json
{ "trades": [ { "id": "uuid", "instrument": "RELIANCE", "transactionType": "BUY", "quantity": 10, "status": "EXECUTED", "placedAt": "..." } ] }
```

### 8.3 Get Trade Detail

```
GET /api/v1/trades/{tradeId}
```

### 8.4 Cancel Trade

```
DELETE /api/v1/trades/{tradeId}/cancel
```

**Response:**
```json
{ "message": "Order cancelled", "brokerResponse": "..." }
```

### 8.5 Get Trade Replications

```
GET /api/v1/trades/{tradeId}/replications
```

**Response:**
```json
{ "replications": [ { "childId": "uuid", "symbol": "RELIANCE", "childStatus": "SUCCESS", "qty": 10 } ] }
```

### 8.6 Open Positions

```
GET /api/v1/trades/open-positions?brokerAccountId=uuid
```

### 8.7 Basket Order

```
POST /api/v1/trades/basket
```

**Request:**
```json
{
  "brokerAccountId": "uuid",
  "basketName": "My Basket",
  "orders": [
    { "instrument": "RELIANCE", "transactionType": "BUY", "quantity": 10, "orderType": "MARKET", "product": "MIS" },
    { "instrument": "TCS", "transactionType": "BUY", "quantity": 5, "orderType": "MARKET", "product": "MIS" }
  ]
}
```

---

## 9. Copy Engine

### 9.1 Manual Copy Trade

```
POST /api/v1/engine/copy-trade
```

**Auth:** Bearer token (Master)

Manually trigger a trade copy to all active children.

**Request:**
```json
{ "symbol": "RELIANCE", "qty": 10, "side": "BUY", "product": "MIS", "orderType": "MARKET", "price": 0 }
```

**Response:**
```json
{
  "message": "Trade copy completed",
  "symbol": "RELIANCE",
  "side": "BUY",
  "masterQty": 10,
  "childrenTotal": 3,
  "success": 2,
  "failed": 1,
  "results": [
    { "childId": "uuid", "status": "SUCCESS", "message": "Order placed: 123", "broker": "ZERODHA", "scaledQty": 10 },
    { "childId": "uuid", "status": "FAILED", "message": "Broker session inactive", "broker": "DHAN", "scaledQty": 10 }
  ]
}
```

### 9.2 Engine Status

```
GET /api/v1/engine/status
```

**Response:**
```json
{ "engineStatus": "ACTIVE", "pollingEnabled": false, "pollingIntervalSeconds": 3, "supportedBrokers": ["GROWW","ZERODHA","FYERS","UPSTOX","DHAN"], "modes": ["manual","polling"] }
```

### 9.3 Toggle Polling

```
POST /api/v1/engine/polling
```

**Request:**
```json
{ "enabled": true }
```

### 9.4 Reset Polling Cache

```
POST /api/v1/engine/polling/reset
```

---

## 10. Risk Engine

### 10.1 Get Risk Rules

```
GET /api/v1/risk/rules
```

**Response:**
```json
{ "maxTradesPerDay": 50, "maxOpenPositions": 20, "maxCapitalExposure": 80.0, "marginCheckEnabled": true }
```

### 10.2 Set Risk Rules (Admin)

```
PUT /api/v1/admin/risk/rules/{userId}
```

**Request:**
```json
{ "maxTradesPerDay": 30, "maxOpenPositions": 10, "maxCapitalExposure": 60, "marginCheckEnabled": true }
```

### 10.3 Get Exposure

```
GET /api/v1/risk/exposure
```

**Response:**
```json
{ "currentOpenPositions": 3, "maxOpenPositions": 20, "tradesToday": 5, "maxTradesPerDay": 50, "capitalExposurePct": 0 }
```

### 10.4 Margin Check

```
GET /api/v1/risk/margin-check?brokerAccountId=uuid&instrument=RELIANCE&quantity=10&orderType=MARKET
```

**Response:**
```json
{ "sufficient": true, "requiredMargin": 1000, "availableMargin": 75000, "shortfall": 0 }
```

---

## 11. P&L Engine

### 11.1 Realized P&L

```
GET /api/v1/pnl/realized?from=2026-01-01&to=2026-04-16
```

**Response:**
```json
{ "realizedPnl": 0, "trades": [...] }
```

### 11.2 Unrealized P&L

```
GET /api/v1/pnl/unrealized?brokerAccountId=uuid
```

**Response:**
```json
{ "unrealizedPnl": 0, "positions": [...] }
```

### 11.3 P&L Summary

```
GET /api/v1/pnl/summary?period=DAILY
```

**Response:**
```json
{ "summary": [ { "period": "today", "realizedPnl": 0, "unrealizedPnl": 0, "totalTrades": 5, "winRate": 0 } ] }
```

### 11.4 Child vs Master

```
GET /api/v1/pnl/child-vs-master?masterId=uuid
```

**Response:**
```json
{ "masterPnl": 0, "childPnl": 0, "replicationAccuracy": 80, "failedReplications": 2 }
```

### 11.5 Admin P&L (All Users)

```
GET /api/v1/admin/pnl/all
```

---

## 12. Logs

### 12.1 User Trade Logs

```
GET /api/v1/logs/trades
```

### 12.2 Broker Error Logs

```
GET /api/v1/logs/broker-errors?brokerAccountId=uuid
```

### 12.3 Admin Trade Logs

```
GET /api/v1/admin/logs/trades?userId=uuid&status=EXECUTED
```

### 12.4 Admin System Logs

```
GET /api/v1/admin/logs/system
```

**Response:**
```json
{ "logs": [ { "level": "INFO", "service": "copy-trading", "message": "System running", "freeMemoryMB": 200, "totalMemoryMB": 512 } ] }
```

### 12.5 Admin Broker Error Logs

```
GET /api/v1/admin/logs/broker-errors?brokerId=ZERODHA
```

---

## 13. Balance Alert

```
GET /api/v1/brokers/accounts/{accountId}/balance-alert
```

**Response:**
```json
{ "alertLevel": "WARNING", "message": "Balance kam hai. Add funds.", "availableMargin": 3500, "thresholds": { "critical": 1000, "warning": 5000, "low": 10000 } }
```

Alert levels: `CRITICAL` (<₹1K) | `WARNING` (<₹5K) | `LOW` (<₹10K) | `OK` (≥₹10K)

---

## 14. Connection Signal

```
GET /api/v1/brokers/accounts/{accountId}/signal
```

**Response:**
```json
{ "signal": 4, "maxSignal": 4, "quality": "excellent", "color": "green", "message": "Connection excellent (120ms)", "latencyMs": 120 }
```

Signal: 0 (disconnected) → 1 (poor/red) → 2 (fair/yellow) → 3 (good/green) → 4 (excellent/green)

---

## 15. Dashboard (All-in-One)

```
GET /api/v1/brokers/accounts/{accountId}/dashboard
```

Returns profile + margin + positions + holdings + orders + signal + balanceAlert in one call.

---

## 16. WebSocket Channels

Connect: `ws://copy-trading-production-3981.up.railway.app/ws/{channel}?token=JWT`

| Channel | Events |
|---------|--------|
| `/ws/trades` | TRADE_EXECUTED, TRADE_FAILED, TRADE_CANCELLED, TRADE_DETECTED |
| `/ws/positions` | POSITION_OPENED, POSITION_CLOSED, POSITION_UPDATED |
| `/ws/pnl` | Real-time P&L updates |
| `/ws/notifications` | MARGIN_WARNING, TRADE_FAILED, REPLICATION_FAILED, SESSION_EXPIRED |

---

## Quick Reference — All Endpoints (Updated)

| # | Method | Endpoint | Auth | Description |
|---|--------|----------|------|-------------|
| | **AUTH** | | | |
| 1 | POST | `/api/v1/auth/register` | No | Register |
| 2 | POST | `/api/v1/auth/login` | No | Login |
| 3 | POST | `/api/v1/auth/send-otp` | No | Send OTP |
| 4 | POST | `/api/v1/auth/verify-otp` | No | Verify OTP |
| 5 | POST | `/api/v1/auth/refresh-token` | No | Refresh token |
| 6 | POST | `/api/v1/auth/logout` | Yes | Logout |
| 7 | POST | `/api/v1/auth/forgot-password` | No | Forgot password |
| 8 | POST | `/api/v1/auth/reset-password` | No | Reset password |
| 9 | GET | `/api/v1/auth/me` | Yes | Get profile |
| 10 | PUT | `/api/v1/auth/me` | Yes | Update profile |
| 11 | POST | `/api/v1/auth/2fa/enable` | Yes | Enable 2FA |
| 12 | POST | `/api/v1/auth/2fa/verify` | Yes | Verify 2FA |
| 13 | DELETE | `/api/v1/auth/2fa/disable` | Yes | Disable 2FA |
| | **ADMIN** | | | |
| 14 | GET | `/api/v1/admin/users` | Admin | List users |
| 15 | POST | `/api/v1/admin/users/master` | Admin | Create master |
| 16 | POST | `/api/v1/admin/users/child` | Admin | Create child |
| 17 | GET | `/api/v1/admin/users/{userId}` | Admin | Get user |
| 18 | PUT | `/api/v1/admin/users/{userId}` | Admin | Update user |
| 19 | PATCH | `/api/v1/admin/users/{userId}/activate` | Admin | Activate |
| 20 | PATCH | `/api/v1/admin/users/{userId}/deactivate` | Admin | Deactivate |
| 21 | DELETE | `/api/v1/admin/users/{userId}` | Admin | Delete user |
| 22 | GET | `/api/v1/admin/analytics` | Admin | Analytics |
| 23 | GET | `/api/v1/admin/system-health` | Admin | System health |
| 24 | GET | `/api/v1/admin/subscriptions` | Admin | Subscriptions |
| 25 | GET | `/api/v1/admin/trade-logs` | Admin | Trade logs |
| 26 | GET | `/api/v1/admin/brokers/accounts` | Admin | Broker accounts |
| 27 | GET | `/api/v1/admin/brokers/status` | Admin | Broker status |
| 28 | PUT | `/api/v1/admin/risk/rules/{userId}` | Admin | Set risk rules |
| 29 | GET | `/api/v1/admin/pnl/all` | Admin | Platform P&L |
| 30 | GET | `/api/v1/admin/logs/trades` | Admin | All trade logs |
| 31 | GET | `/api/v1/admin/logs/system` | Admin | System logs |
| 32 | GET | `/api/v1/admin/logs/broker-errors` | Admin | Broker errors |
| | **BROKERS** | | | |
| 33 | GET | `/api/v1/brokers` | Yes | List brokers |
| 34 | POST | `/api/v1/brokers/accounts` | Yes | Link account |
| 35 | GET | `/api/v1/brokers/accounts` | Yes | List accounts |
| 36 | GET | `/api/v1/brokers/accounts/{id}` | Yes | Get account |
| 37 | PUT | `/api/v1/brokers/accounts/{id}` | Yes | Update account |
| 38 | DELETE | `/api/v1/brokers/accounts/{id}` | Yes | Delete account |
| 39 | POST | `/api/v1/brokers/accounts/{id}/login` | Yes | Login to broker |
| 40 | GET | `/api/v1/brokers/accounts/{id}/oauth-url` | Yes | Get OAuth URL |
| 41 | GET | `/api/v1/brokers/accounts/{id}/status` | Yes | Session status |
| 42 | GET | `/api/v1/brokers/accounts/{id}/test` | Yes | Test connection |
| 43 | GET | `/api/v1/brokers/accounts/{id}/dashboard` | Yes | Dashboard |
| 44 | GET | `/api/v1/brokers/accounts/{id}/margin` | Yes | Margin |
| 45 | GET | `/api/v1/brokers/accounts/{id}/positions` | Yes | Positions |
| 46 | GET | `/api/v1/brokers/accounts/{id}/orders` | Yes | Orders |
| 47 | GET | `/api/v1/brokers/accounts/{id}/trades` | Yes | Trades |
| 48 | GET | `/api/v1/brokers/accounts/{id}/holdings` | Yes | Holdings |
| 49 | GET | `/api/v1/brokers/accounts/{id}/signal` | Yes | Connection signal |
| 50 | GET | `/api/v1/brokers/accounts/{id}/balance-alert` | Yes | Balance alert |
| 51 | POST | `/api/v1/brokers/accounts/{id}/orders/close-position` | Yes | Close position |
| 52 | DELETE | `/api/v1/brokers/accounts/{id}/orders/{orderId}` | Yes | Cancel order |
| 53 | GET | `/api/v1/brokers/callback` | No | OAuth callback |
| | **MASTER** | | | |
| 54 | GET | `/api/v1/master/children` | Yes | List children |
| 55 | POST | `/api/v1/master/children/{childId}/link` | Yes | Link child |
| 56 | POST | `/api/v1/master/children/bulk-link` | Yes | Bulk link |
| 57 | DELETE | `/api/v1/master/children/{childId}/unlink` | Yes | Unlink |
| 58 | POST | `/api/v1/master/children/bulk-unlink` | Yes | Bulk unlink |
| 59 | POST | `/api/v1/master/children/{childId}/pause` | Yes | Pause |
| 60 | POST | `/api/v1/master/children/{childId}/resume` | Yes | Resume |
| 61 | GET | `/api/v1/master/children/pending` | Yes | Pending approvals |
| 62 | POST | `/api/v1/master/children/{childId}/approve` | Yes | Approve |
| 63 | POST | `/api/v1/master/children/{childId}/reject` | Yes | Reject |
| 64 | POST | `/api/v1/master/children/{childId}/decline` | Yes | Decline |
| 65 | GET | `/api/v1/master/children/{childId}/scaling` | Yes | Get scaling |
| 66 | PUT | `/api/v1/master/children/{childId}/scaling` | Yes | Update scaling |
| 67 | GET | `/api/v1/master/analytics` | Yes | Analytics |
| 68 | GET | `/api/v1/master/trade-history` | Yes | Trade history |
| 69 | POST | `/api/v1/master/active-account` | Yes | Set active account |
| 70 | GET | `/api/v1/master/active-account` | Yes | Get active account |
| 71 | DELETE | `/api/v1/master/active-account` | Yes | Clear active account |
| 72 | GET | `/api/v1/master/copy/logs` | Yes | Copy logs |
| 73 | GET | `/api/v1/master/earnings` | Yes | Earnings |
| 74 | GET | `/api/v1/master/payouts` | Yes | Payouts |
| | **CHILD** | | | |
| 75 | GET | `/api/v1/child/masters` | Yes | List masters |
| 76 | POST | `/api/v1/child/subscriptions` | Yes | Subscribe |
| 77 | POST | `/api/v1/child/subscriptions/bulk` | Yes | Bulk subscribe |
| 78 | DELETE | `/api/v1/child/subscriptions/{masterId}` | Yes | Unsubscribe |
| 79 | POST | `/api/v1/child/subscriptions/bulk-unsubscribe` | Yes | Bulk unsubscribe |
| 80 | GET | `/api/v1/child/subscriptions` | Yes | List subscriptions |
| 81 | GET | `/api/v1/child/scaling` | Yes | Get scaling |
| 82 | PUT | `/api/v1/child/scaling` | Yes | Update scaling |
| 83 | POST | `/api/v1/child/copying/pause` | Yes | Pause copying |
| 84 | POST | `/api/v1/child/copying/resume` | Yes | Resume copying |
| 85 | GET | `/api/v1/child/copied-trades` | Yes | Copied trades |
| 86 | GET | `/api/v1/child/analytics` | Yes | Analytics |
| 87 | GET | `/api/v1/child/copy/logs` | Yes | Copy logs |
| | **TRADE ENGINE** | | | |
| 88 | POST | `/api/v1/trades/execute` | Yes | Execute trade |
| 89 | GET | `/api/v1/trades` | Yes | List trades |
| 90 | GET | `/api/v1/trades/{tradeId}` | Yes | Get trade |
| 91 | DELETE | `/api/v1/trades/{tradeId}/cancel` | Yes | Cancel trade |
| 92 | GET | `/api/v1/trades/{tradeId}/replications` | Yes | Replications |
| 93 | GET | `/api/v1/trades/open-positions` | Yes | Open positions |
| 94 | POST | `/api/v1/trades/basket` | Yes | Basket order |
| | **COPY ENGINE** | | | |
| 95 | POST | `/api/v1/engine/copy-trade` | Yes | Manual copy |
| 96 | GET | `/api/v1/engine/status` | Yes | Engine status |
| 97 | POST | `/api/v1/engine/polling` | Yes | Toggle polling |
| 98 | POST | `/api/v1/engine/polling/reset` | Yes | Reset cache |
| | **RISK ENGINE** | | | |
| 99 | GET | `/api/v1/risk/rules` | Yes | Get rules |
| 100 | GET | `/api/v1/risk/exposure` | Yes | Get exposure |
| 101 | GET | `/api/v1/risk/margin-check` | Yes | Margin check |
| | **P&L ENGINE** | | | |
| 102 | GET | `/api/v1/pnl/realized` | Yes | Realized P&L |
| 103 | GET | `/api/v1/pnl/unrealized` | Yes | Unrealized P&L |
| 104 | GET | `/api/v1/pnl/summary` | Yes | P&L summary |
| 105 | GET | `/api/v1/pnl/child-vs-master` | Yes | Child vs Master |
| | **LOGS** | | | |
| 106 | GET | `/api/v1/logs/trades` | Yes | Trade logs |
| 107 | GET | `/api/v1/logs/broker-errors` | Yes | Broker errors |
| | **NOTIFICATIONS** | | | |
| 108 | GET | `/api/v1/notifications` | Yes | List |
| 109 | PATCH | `/api/v1/notifications/{id}/read` | Yes | Mark read |
| 110 | POST | `/api/v1/notifications/read-all` | Yes | Mark all read |
| 111 | DELETE | `/api/v1/notifications/{id}` | Yes | Delete |
| | **COPY LOGS** | | | |
| 112 | GET | `/api/v1/copy/logs` | Yes | All copy logs |
| | **WEBSOCKET** | | | |
| 113 | WS | `/ws/trades` | Token | Trade events |
| 114 | WS | `/ws/positions` | Token | Position events |
| 115 | WS | `/ws/pnl` | Token | P&L updates |
| 116 | WS | `/ws/notifications` | Token | Alerts |
