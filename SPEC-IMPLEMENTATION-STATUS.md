# Backend API Spec — Implementation Status
## All endpoints are LIVE on production
## Base URL: `https://copy-trading-production-3981.up.railway.app`
## Total: 63 endpoints deployed

---

# SECTION 1: OTP Authentication ✅ IMPLEMENTED

### POST /api/v1/auth/send-otp (PUBLIC)
```json
Request:  { "phone": "+919876543210", "purpose": "login" }
Success:  { "success": true, "data": { "expiresIn": 300, "retryAfter": 60 }, "message": "OTP sent successfully" }
Error 404: { "success": false, "error": "PHONE_NOT_REGISTERED", "message": "No account found with this phone number" }
Error 429: { "success": false, "error": "RATE_LIMITED", "message": "Please wait before requesting another OTP", "data": { "retryAfter": 45 } }
```
SMS delivery: AWS SNS (needs AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY env vars on Railway). Until configured, OTP is logged to server console.

### POST /api/v1/auth/verify-otp (PUBLIC)
```json
Request:  { "phone": "+919876543210", "otp": "482910", "purpose": "login" }
Success:  { "success": true, "data": { "accessToken": "jwt...", "refreshToken": "jwt...", "user": { "id": "uuid", "name": "Ravi Kumar", "email": "ravi@example.com", "phone": "+919876543210", "role": "Master", "twoFactorEnabled": false } } }
Error 400: { "success": false, "error": "INVALID_OTP", "message": "Invalid OTP code" }
Error 400: { "success": false, "error": "OTP_EXPIRED", "message": "OTP has expired. Please request a new one." }
Error 429: { "success": false, "error": "TOO_MANY_ATTEMPTS", "message": "Too many failed attempts. Please request a new OTP." }
```
Role values: "Master", "Child", "Admin" (capital first letter as spec requires)

---

# SECTION 2: Broker Enhancements ✅ IMPLEMENTED

### GET /api/v1/brokers/accounts (Enhanced)
Now returns additional fields per account:
```json
{
  "accounts": [{
    "accountId": "uuid",
    "brokerId": "ZERODHA",
    "brokerName": "Zerodha",
    "clientId": "ZX1234",
    "nickname": "My Zerodha",
    "status": "ACTIVE",
    "sessionActive": true,
    "linkedAt": "2026-04-08T10:00:00Z",
    "lastSyncedAt": "2026-04-08T10:30:00Z",
    "margin": 0.0,
    "pnl": 0.0,
    "positions": 0,
    "orders": 0
  }]
}
```

### GET /api/v1/brokers/accounts/{accountId}/status (Enhanced)
Now returns connectionHealth, margin, brokerName:
```json
{
  "accountId": "uuid",
  "status": "ACTIVE",
  "sessionActive": true,
  "broker": "ZERODHA",
  "brokerName": "Zerodha",
  "clientId": "ZX1234",
  "connectionHealth": "good",
  "lastSyncedAt": "2026-04-08T10:30:00Z",
  "expiresAt": "2026-04-09T06:00:00Z"
}
```
connectionHealth values: "good" | "degraded" | "down"

### GET /api/v1/brokers/accounts/{accountId}/test (NEW)
Test Connection button — tries to fetch margin from broker to verify connection:
```json
Success: { "accountId": "uuid", "connectionHealth": "good", "sessionActive": true, "margin": 100.0, "brokerName": "Groww", "message": "Connection successful" }
Failed:  { "accountId": "uuid", "connectionHealth": "degraded", "message": "Connection issue: ..." }
Down:    { "accountId": "uuid", "connectionHealth": "down", "message": "No active session. Login first." }
```

---

# SECTION 3: Subscription Approval ✅ IMPLEMENTED

### POST /api/v1/child/subscriptions — Subscribe (PENDING_APPROVAL)
```json
Request:  { "masterId": "uuid", "brokerAccountId": "uuid", "scalingFactor": 1.0 }
Response: { "subscriptionId": 1, "status": "PENDING_APPROVAL", "message": "Subscription request sent. Waiting for master approval." }
```

### GET /api/v1/master/children/pending — Pending approvals
```json
Response: { "pendingApprovals": [{ "childId": "uuid", "name": "Child Name", "email": "child@email.com", "requestedAt": "2026-04-08T09:00:00Z", "subscriptionId": 1 }] }
```

### POST /api/v1/master/children/{childId}/approve
```json
Response: { "message": "Child approved" }
```

### POST /api/v1/master/children/{childId}/decline
```json
Response: { "message": "Child rejected" }
```

---

# SECTION 4: 2FA ✅ ALREADY EXISTED

- POST /api/v1/auth/2fa/enable ✅
- POST /api/v1/auth/2fa/verify ✅
- DELETE /api/v1/auth/2fa/disable ✅

---

# SECTION 5: Subscription Statuses ✅ MATCH

| Status | Implemented |
|--------|------------|
| PENDING_APPROVAL | ✅ |
| ACTIVE | ✅ |
| PAUSED | ✅ |
| REJECTED | ✅ |
| INACTIVE | ✅ |

---

# SECTION 6: Open Questions — ANSWERED

| Question | Answer |
|----------|--------|
| SMS provider | AWS SNS (Transactional SMS) |
| OTP expiry | 5 minutes |
| Resend wait | 60 seconds |
| verify-otp format | Same as login (accessToken + refreshToken + user) |
| lastSyncedAt on expired | Yes, returns even on expired sessions |
| Notification mechanism | Polling every 60s (no webhook yet) |

---

# ADDITIONAL ENDPOINTS (Beyond Spec)

These were built during this session and are also live:

| Endpoint | Description |
|----------|-------------|
| POST /master/children/bulk-link | Bulk link/approve children |
| POST /master/children/bulk-unlink | Bulk unlink children |
| POST /master/children/{childId}/pause | Master pauses child |
| POST /master/children/{childId}/resume | Master resumes child |
| POST /master/subscribe/{childId} | Master follows child's trades |
| POST /child/subscriptions/bulk | Bulk subscribe to masters |
| POST /child/subscriptions/bulk-unsubscribe | Bulk unsubscribe |
| GET /brokers/accounts/{id}/oauth-url | Get OAuth login URL |
| GET /brokers/callback | OAuth callback (captures tokens) |

---

# BROKER INTEGRATIONS (5 Live)

| Broker | Status | Login Method |
|--------|--------|-------------|
| Groww | ✅ Live | Access Token or API Key + TOTP |
| Zerodha | ✅ Live | OAuth (platform key) |
| Fyers | ✅ Live | OAuth (platform key) |
| Upstox | ✅ Live | OAuth (platform key) |
| Dhan | ✅ Live | Access Token or OAuth (platform key) |
| Angel One | ❌ Needs static IP | — |
