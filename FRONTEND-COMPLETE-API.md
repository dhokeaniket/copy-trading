# Ascentra Copy Trading — Complete Frontend API Reference

**Base URL:** `https://api.ascentracapital.com` (or `http://13.53.246.13:8081`)
**Auth:** All endpoints except login/register require `Authorization: Bearer <accessToken>` header.
**Content-Type:** `application/json` for all POST/PUT requests.

---

## Frontend Tab Mapping

| FE Tab/Page | APIs Used |
|---|---|
| **Login Page** | `POST /auth/login`, `POST /auth/register`, `POST /auth/send-otp`, `POST /auth/verify-otp` |
| **Dashboard (Master)** | `GET /master/analytics`, `GET /master/children`, `GET /master/active-account`, `GET /engine/status` |
| **Dashboard (Child)** | `GET /child/analytics`, `GET /child/subscriptions`, `GET /child/copied-trades` |
| **Broker Accounts** | `GET /brokers`, `GET /brokers/accounts`, `POST /brokers/accounts`, `POST .../login`, `GET .../dashboard` |
| **Master → Children** | `GET /master/children`, `POST .../link`, `POST .../approve`, `POST .../reject`, `POST .../pause`, `POST .../resume` |
| **Child → Masters** | `GET /child/masters`, `POST /child/subscriptions`, `DELETE /child/subscriptions/:masterId` |
| **Copy Engine** | `POST /engine/copy-trade`, `GET /engine/status`, `POST /engine/polling` |
| **Trade History** | `GET /master/trade-history`, `GET /child/copied-trades`, `GET /master/copy/logs`, `GET /child/copy/logs` |
| **Profile/Settings** | `GET /auth/me`, `PUT /auth/me`, `POST /auth/2fa/enable`, `POST /auth/2fa/verify`, `DELETE /auth/2fa/disable` |
| **Admin Panel** | `GET /admin/users`, `POST /admin/users/master`, `POST /admin/users/child`, `GET /admin/analytics`, `GET /admin/system-health` |
| **Notifications** | WebSocket `ws://host:8081/ws/trades` |

---

## 1. Authentication APIs (`/api/v1/auth`)

### 1.1 Register
```
POST /api/v1/auth/register
```
**Request:**
```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "password": "password123",
  "role": "MASTER",
  "phone": "+919876543210"
}
```
- `role`: `MASTER`, `CHILD`, or `ADMIN`
- `password`: min 8 chars, at least 1 number

**Response (201):**
```json
{ "userId": "uuid", "message": "Registration successful" }
```

### 1.2 Login
```
POST /api/v1/auth/login
```
**Request:**
```json
{ "email": "john@example.com", "password": "password123" }
```
**Response (200):**
```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "user": {
    "userId": "uuid",
    "name": "John Doe",
    "email": "john@example.com",
    "role": "MASTER",
    "status": "ACTIVE",
    "phone": "+919876543210",
    "twoFactorEnabled": false,
    "createdAt": "2026-04-28T...",
    "brokerAccounts": []
  },
  "requires2FA": false
}
```
If `requires2FA: true`, call `POST /auth/2fa/verify` with the temporary `accessToken` + TOTP code.

### 1.3 Send OTP (Phone Login)
```
POST /api/v1/auth/send-otp
```
**Request:** `{ "phone": "+919876543210" }`
**Response:** `{ "success": true, "data": { "expiresIn": 300, "retryAfter": 30 }, "message": "OTP sent successfully" }`

### 1.4 Verify OTP
```
POST /api/v1/auth/verify-otp
```
**Request:** `{ "phone": "+919876543210", "otp": "123456" }`
**Response:** `{ "success": true, "data": { "accessToken": "...", "refreshToken": "...", "user": {...} } }`

### 1.5 Logout
```
POST /api/v1/auth/logout
```
**Request:** `{ "refreshToken": "eyJ..." }`
**Response:** `{ "message": "Logged out successfully" }`

### 1.6 Refresh Token
```
POST /api/v1/auth/refresh-token
```
**Request:** `{ "refreshToken": "eyJ..." }`
**Response:** `{ "accessToken": "new...", "refreshToken": "new..." }`

### 1.7 Forgot Password
```
POST /api/v1/auth/forgot-password
```
**Request:** `{ "email": "john@example.com" }`
**Response:** `{ "message": "If the email exists, a reset link has been sent" }`

### 1.8 Reset Password
```
POST /api/v1/auth/reset-password
```
**Request:** `{ "token": "reset-token-uuid", "newPassword": "newpass123" }`
**Response:** `{ "message": "Password reset successful" }`

### 1.9 Get Profile
```
GET /api/v1/auth/me
```
**Response:** UserDto (same shape as login response `user` object)

### 1.10 Update Profile
```
PUT /api/v1/auth/me
```
**Request:**
```json
{ "name": "New Name", "phone": "+91...", "currentPassword": "old", "newPassword": "new123" }
```
All fields optional. `currentPassword` required only if changing password.

### 1.11 Enable 2FA
```
POST /api/v1/auth/2fa/enable
```
**Response:** `{ "qrCodeUri": "otpauth://totp/...", "secret": "BASE32SECRET" }`
Show QR code to user. They scan with Google Authenticator.

### 1.12 Verify 2FA
```
POST /api/v1/auth/2fa/verify
```
**Request:** `{ "otp": "123456" }`
**Response:** `{ "accessToken": "...", "refreshToken": "...", "message": "2FA enabled and verified" }`

### 1.13 Disable 2FA
```
DELETE /api/v1/auth/2fa/disable
```
**Request:** `{ "password": "mypass", "otp": "123456" }`
**Response:** `{ "message": "2FA disabled successfully" }`

---

## 2. Broker Account APIs (`/api/v1/brokers`)

### 2.1 List Supported Brokers
```
GET /api/v1/brokers
```
**Response:**
```json
{
  "brokers": [
    { "brokerId": "GROWW", "name": "Groww", "isActive": true, "loginMethod": "token", "loginOptions": [...] },
    { "brokerId": "ZERODHA", "name": "Zerodha", "isActive": true, "loginMethod": "oauth", "loginField": "requestToken" },
    { "brokerId": "FYERS", "name": "Fyers", "isActive": true, "loginMethod": "oauth", "loginField": "authCode" },
    { "brokerId": "UPSTOX", "name": "Upstox", "isActive": true, "loginMethod": "oauth", "loginField": "authCode" },
    { "brokerId": "ANGELONE", "name": "Angel One", "isActive": true, "loginMethod": "totp", "loginField": "totpCode" },
    { "brokerId": "DHAN", "name": "Dhan", "isActive": true, "loginMethod": "oauth", "loginField": "tokenId" }
  ]
}
```

### 2.2 Link Broker Account
```
POST /api/v1/brokers/accounts
```
**Request:**
```json
{
  "brokerId": "GROWW",
  "clientId": "optional-client-id",
  "apiKey": "your-api-key",
  "apiSecret": "your-api-secret",
  "accessToken": "optional-direct-token",
  "accountNickname": "My Groww"
}
```
- For Groww: `apiKey` + `apiSecret` (or just `accessToken`)
- For Zerodha/Fyers/Upstox: platform handles keys, just provide `accountNickname`
- For Dhan: `clientId` (Dhan client ID) is required for order placement
- For Angel One: `clientId` (client code) + `apiSecret` (password)

**Response (201):** `{ "accountId": "uuid", "brokerId": "GROWW", "status": "ACTIVE" }`

### 2.3 List My Accounts
```
GET /api/v1/brokers/accounts
```
**Response:**
```json
{
  "accounts": [{
    "accountId": "uuid", "brokerId": "GROWW", "brokerName": "Groww",
    "clientId": "", "nickname": "My Groww", "status": "ACTIVE",
    "sessionActive": true, "linkedAt": "...", "lastSyncedAt": "...",
    "margin": 0, "pnl": 0, "positions": 0, "orders": 0
  }]
}
```

### 2.4 Get Account Detail
```
GET /api/v1/brokers/accounts/{accountId}
```

### 2.5 Update Account
```
PUT /api/v1/brokers/accounts/{accountId}
```
**Request:** `{ "apiKey": "new", "apiSecret": "new", "accountNickname": "new" }` (all optional)

### 2.6 Delete Account
```
DELETE /api/v1/brokers/accounts/{accountId}
```

### 2.7 Login to Broker (Create Session)
```
POST /api/v1/brokers/accounts/{accountId}/login
```
**Request varies by broker:**
- **Groww:** `{}` (empty body — uses stored apiKey+apiSecret) or `{ "totpCode": "123456" }`
- **Zerodha:** `{ "requestToken": "from-oauth-redirect" }`
- **Fyers:** `{ "authCode": "from-oauth-redirect" }`
- **Upstox:** `{ "authCode": "from-oauth-redirect" }`
- **Dhan:** `{}` first (returns loginUrl), then `{ "authCode": "tokenId-from-redirect" }`
- **Angel One:** `{ "totpCode": "123456" }`

**Response:** `{ "status": "SESSION_ACTIVE", "broker": "Groww", "expiresAt": "..." }`

### 2.8 Get OAuth URL
```
GET /api/v1/brokers/accounts/{accountId}/oauth-url?redirectUri=optional
```
Returns the broker-specific OAuth URL to open in browser.

### 2.9 Session Status
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

### 2.10 Test Connection
```
GET /api/v1/brokers/accounts/{accountId}/test
```

### 2.11 Get Margin
```
GET /api/v1/brokers/accounts/{accountId}/margin
```
**Response:** `{ "availableMargin": 50000, "usedMargin": 10000, "totalFunds": 60000, "collateral": 0 }`

### 2.12 Get Positions
```
GET /api/v1/brokers/accounts/{accountId}/positions
```

### 2.13 Get Orders
```
GET /api/v1/brokers/accounts/{accountId}/orders
```

### 2.14 Get Trades
```
GET /api/v1/brokers/accounts/{accountId}/trades
```

### 2.15 Get Holdings
```
GET /api/v1/brokers/accounts/{accountId}/holdings
```

### 2.16 Dashboard (All-in-One)
```
GET /api/v1/brokers/accounts/{accountId}/dashboard
```
Returns profile + margin + positions + holdings + orders + signal + balance alert in one call.

### 2.17 Close Position
```
POST /api/v1/brokers/accounts/{accountId}/orders/close-position
```
**Request:** `{ "symbol": "RELIANCE", "qty": 10, "type": "SELL", "product": "MIS" }`

### 2.18 Cancel Order
```
DELETE /api/v1/brokers/accounts/{accountId}/orders/{orderId}
```

### 2.19 Balance Alert
```
GET /api/v1/brokers/accounts/{accountId}/balance-alert
```

### 2.20 Connection Signal (Network Bars)
```
GET /api/v1/brokers/accounts/{accountId}/signal
```
**Response:**
```json
{
  "accountId": "uuid", "brokerId": "GROWW", "brokerName": "Groww",
  "signal": 4, "maxSignal": 4, "quality": "excellent", "color": "green",
  "message": "Connection excellent (423ms)", "sessionActive": true,
  "latencyMs": 423, "marginAvailable": 50000
}
```

### 2.21 OAuth Callback
```
GET /api/v1/brokers/callback?request_token=...&auth_code=...&code=...&tokenId=...
```
Captures broker OAuth redirects. Returns the token to use in login call.

---

## 3. Master APIs (`/api/v1/master`)

### 3.1 List Children
```
GET /api/v1/master/children
```
**Response:**
```json
{
  "children": [{
    "childId": "uuid", "name": "Child Name", "email": "child@example.com",
    "scalingFactor": 1.0, "copyingStatus": "ACTIVE", "subscribedAt": "..."
  }]
}
```

### 3.2 Link Child (Master-Initiated)
```
POST /api/v1/master/children/{childId}/link
```
**Request:** `{ "scalingFactor": 1.0 }` (optional)
Auto-resolves child's broker account. Bypasses approval.

### 3.3 Bulk Link
```
POST /api/v1/master/children/bulk-link
```
**Request:** `{ "children": [{ "childId": "uuid", "scalingFactor": 1.0 }] }`

### 3.4 Pending Approvals
```
GET /api/v1/master/children/pending
```

### 3.5 Approve Child
```
POST /api/v1/master/children/{childId}/approve
```

### 3.6 Reject Child
```
POST /api/v1/master/children/{childId}/reject
```

### 3.7 Unlink Child
```
DELETE /api/v1/master/children/{childId}/unlink
```

### 3.8 Pause/Resume Child
```
POST /api/v1/master/children/{childId}/pause
POST /api/v1/master/children/{childId}/resume
```

### 3.9 Get/Update Scaling
```
GET /api/v1/master/children/{childId}/scaling
PUT /api/v1/master/children/{childId}/scaling
```
**PUT Request:** `{ "scalingFactor": 1.5 }` (0.01 to 10.0)

### 3.10 Set Active Account (for polling)
```
POST /api/v1/master/active-account
```
**Request:** `{ "brokerAccountId": "uuid" }`
This tells the poller which broker account to monitor for new orders.

### 3.11 Get Active Account
```
GET /api/v1/master/active-account
```

### 3.12 Analytics
```
GET /api/v1/master/analytics
```
**Response:**
```json
{
  "totalPnl": 0, "winRate": 0, "totalTrades": 5, "totalReplications": 3,
  "totalChildren": 2, "totalFollowers": 2, "revenue": 0,
  "performanceChart": [...], "childPerformance": [...]
}
```

### 3.13 Trade History
```
GET /api/v1/master/trade-history
```

### 3.14 Copy Logs
```
GET /api/v1/master/copy/logs
```

### 3.15 Earnings
```
GET /api/v1/master/earnings
```

### 3.16 Payouts
```
GET /api/v1/master/payouts
```

---

## 4. Child APIs (`/api/v1/child`)

### 4.1 List Available Masters
```
GET /api/v1/child/masters
```
**Response:**
```json
{
  "masters": [{
    "masterId": "uuid", "name": "Master Trader", "winRate": 0,
    "totalTrades": 0, "subscribers": 2, "return30d": 0,
    "riskLevel": "Medium", "verified": true, "markets": ["Equity", "F&O"]
  }]
}
```

### 4.2 Subscribe to Master
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
`brokerAccountId` is required — child must link a broker account first.
New subscriptions go to `PENDING_APPROVAL`. Previously approved children get `ACTIVE` directly.

**Response:** `{ "subscriptionId": 1, "status": "PENDING_APPROVAL", "message": "..." }`

### 4.3 Unsubscribe
```
DELETE /api/v1/child/subscriptions/{masterId}
```

### 4.4 List Subscriptions
```
GET /api/v1/child/subscriptions
```

### 4.5 Get/Update Scaling
```
GET /api/v1/child/scaling?masterId=uuid
PUT /api/v1/child/scaling
```
**PUT Request:** `{ "masterId": "uuid", "scalingFactor": 1.5 }`

### 4.6 Pause/Resume Copying
```
POST /api/v1/child/copying/pause
POST /api/v1/child/copying/resume
```
**Request:** `{ "masterId": "uuid" }`

### 4.7 Copied Trades
```
GET /api/v1/child/copied-trades
```

### 4.8 Analytics
```
GET /api/v1/child/analytics
```

### 4.9 Copy Logs
```
GET /api/v1/child/copy/logs
```

---

## 5. Copy Engine APIs (`/api/v1/engine`)

### 5.1 Manual Copy Trade (Master Only)
```
POST /api/v1/engine/copy-trade
```
**Request:**
```json
{
  "symbol": "RELIANCE",
  "qty": 10,
  "side": "BUY",
  "product": "CNC",
  "orderType": "MARKET",
  "price": 0,
  "exchange": "NSE"
}
```
- `symbol`: Trading symbol (e.g. RELIANCE, SBIN, NIFTY25JUN25000CE)
- `side`: `BUY` or `SELL`
- `product`: `MIS` (intraday), `CNC` (delivery), `NRML` (F&O)
- `orderType`: `MARKET` or `LIMIT`
- `price`: 0 for MARKET, actual price for LIMIT
- `exchange`: `NSE` (default), `BSE`

**Response:**
```json
{
  "message": "Trade copy completed",
  "symbol": "RELIANCE", "side": "BUY", "masterQty": 10,
  "childrenTotal": 3, "success": 2, "failed": 1,
  "results": [
    { "childId": "uuid", "status": "SUCCESS", "message": "Order placed: GMK...", "broker": "GROWW", "scaledQty": 10 },
    { "childId": "uuid", "status": "FAILED", "message": "Order failed: 401 Unauthorized", "broker": "GROWW", "scaledQty": 10 }
  ]
}
```

### 5.2 Engine Status
```
GET /api/v1/engine/status
```

### 5.3 Toggle Polling
```
POST /api/v1/engine/polling
```
**Request:** `{ "enabled": true }`

### 5.4 Reset Polling Cache
```
POST /api/v1/engine/polling/reset
```

### 5.5 Polling Status
```
GET /api/v1/engine/polling/status
```

---

## 6. Admin APIs (`/api/v1/admin`)

### 6.1 List Users
```
GET /api/v1/admin/users?role=MASTER&status=ACTIVE&page=1&limit=20
```

### 6.2 Create Master
```
POST /api/v1/admin/users/master
```
**Request:** `{ "name": "...", "email": "...", "password": "...", "phone": "..." }`

### 6.3 Create Child
```
POST /api/v1/admin/users/child
```

### 6.4 Get User
```
GET /api/v1/admin/users/{userId}
```

### 6.5 Update User
```
PUT /api/v1/admin/users/{userId}
```
**Request:** `{ "name": "...", "email": "...", "phone": "..." }`

### 6.6 Activate/Deactivate User
```
PATCH /api/v1/admin/users/{userId}/activate
PATCH /api/v1/admin/users/{userId}/deactivate
```

### 6.7 Delete User
```
DELETE /api/v1/admin/users/{userId}
```

### 6.8 Analytics
```
GET /api/v1/admin/analytics
```

### 6.9 System Health
```
GET /api/v1/admin/system-health
```

### 6.10 All Subscriptions
```
GET /api/v1/admin/subscriptions?masterId=uuid&status=ACTIVE
```

### 6.11 Trade Logs
```
GET /api/v1/admin/trade-logs?userId=uuid&status=EXECUTED
```

### 6.12 Broker Accounts (Admin)
```
GET /api/v1/admin/brokers/accounts?userId=uuid&brokerId=GROWW
```

### 6.13 Broker Health
```
GET /api/v1/admin/brokers/status
```

---

## Supported Brokers & Copy Trading

| Broker | Equity | F&O | Login Method | Status |
|---|---|---|---|---|
| Groww | ✅ | ✅ | API Key + Secret / TOTP | Live |
| Zerodha | ✅ | ✅ | OAuth (requestToken) | Ready |
| Fyers | ✅ | ✅ | OAuth (authCode) | Ready |
| Upstox | ✅ | ✅ | OAuth (authCode) | Ready |
| Dhan | ✅ | ✅ | OAuth (tokenId) + clientId | Ready |
| Angel One | ✅ | ✅ | ClientCode + Password + TOTP | Ready |

### Copy Trading Flow
1. Master sets active broker account → `POST /master/active-account`
2. Poller detects master's new orders every 1 second
3. For each new COMPLETE/EXECUTED order → copies to all ACTIVE children
4. Each child's order is placed on their linked broker with scaled quantity
5. Symbol translation handles format differences between brokers automatically

### Error Handling
All error responses follow: `{ "error": "message", "status": 400 }`
Session errors include: `{ "errorCode": "SESSION_EXPIRED", "action": "RE_LOGIN" }`
