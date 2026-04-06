# Latest Changes — API Documentation
## Base URL: `https://copy-trading-production-3981.up.railway.app`
## Auth: All endpoints require `Authorization: Bearer <accessToken>`

---

# WHAT'S NEW

1. Live broker integrations (Groww, Zerodha, Fyers, Upstox)
2. OAuth URL endpoint for broker login
3. Master can subscribe to child (reverse copy trading)
4. Bulk subscribe/link endpoints
5. Subscription approval system (master must approve new children)

---

# 1. BROKER INTEGRATION (4 Live Brokers)

## GET /api/v1/brokers — List brokers with login method info

```json
Response 200:
{
  "brokers": [
    { "brokerId": "GROWW", "name": "Groww", "isActive": true, "loginMethod": "secret", "loginField": null },
    { "brokerId": "ZERODHA", "name": "Zerodha", "isActive": true, "loginMethod": "oauth", "loginField": "requestToken" },
    { "brokerId": "FYERS", "name": "Fyers", "isActive": true, "loginMethod": "oauth", "loginField": "authCode" },
    { "brokerId": "UPSTOX", "name": "Upstox", "isActive": true, "loginMethod": "oauth", "loginField": "authCode" }
  ]
}
```

New fields: `loginMethod` ("secret" or "oauth"), `loginField` (field name to send in login body)

## GET /api/v1/brokers/accounts/{accountId}/oauth-url — Get OAuth login URL (NEW)

```json
Response 200 (Groww):
{ "broker": "GROWW", "loginMethod": "secret", "message": "No OAuth needed. Call login with empty body {}" }

Response 200 (Zerodha):
{
  "broker": "ZERODHA",
  "loginMethod": "oauth",
  "loginField": "requestToken",
  "oauthUrl": "https://kite.zerodha.com/connect/login?v=3&api_key=xxx",
  "message": "Open oauthUrl in browser. After login, capture request_token from redirect URL and POST it as {\"requestToken\":\"...\"}"
}

Response 200 (Fyers):
{
  "broker": "FYERS",
  "loginMethod": "oauth",
  "loginField": "authCode",
  "oauthUrl": "https://api-t1.fyers.in/api/v3/generate-authcode?client_id=xxx&redirect_uri=https://localhost&response_type=code&state=ok",
  "message": "Open oauthUrl in browser. After login, capture auth_code from redirect URL and POST it as {\"authCode\":\"...\"}"
}

Response 200 (Upstox):
{
  "broker": "UPSTOX",
  "loginMethod": "oauth",
  "loginField": "authCode",
  "oauthUrl": "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=xxx&redirect_uri=https://localhost",
  "message": "Open oauthUrl in browser. After login, capture code from redirect URL and POST it as {\"authCode\":\"...\"}"
}
```

## POST /api/v1/brokers/accounts/{accountId}/login — Login (updated)

Now supports different body per broker:

| Broker | Body | Notes |
|--------|------|-------|
| Groww | `{}` | Uses stored API secret automatically |
| Groww (TOTP) | `{"totpCode": "123456"}` | Optional TOTP |
| Zerodha | `{"requestToken": "xxx"}` | From OAuth redirect |
| Fyers | `{"authCode": "xxx"}` | From OAuth redirect |
| Upstox | `{"authCode": "xxx"}` | From OAuth redirect |

```json
Response 200: { "status": "SESSION_ACTIVE", "broker": "Zerodha", "expiresAt": "2026-04-07T06:00:00Z" }
Response 400: { "error": "requestToken required. Open this URL to login: https://kite.zerodha.com/...", "status": 400 }
Response 502: { "error": "Zerodha API error: ...", "status": 502 }
```

---

# 2. MASTER SUBSCRIBE TO CHILD (NEW)

Master can now follow a child's trades (reverse copy trading).

## POST /api/v1/master/subscribe/{childId} — Master subscribes to child

```json
Request (optional): { "scalingFactor": 1.0 }

Response 201: { "subscriptionId": 5, "message": "Subscribed to child successfully" }
Response 409: { "error": "Already subscribed to this child", "status": 409 }
```

---

# 3. BULK ENDPOINTS (NEW)

## POST /api/v1/master/children/bulk-link — Master bulk links multiple children

```json
Request:
{
  "children": [
    { "childId": "uuid-1", "scalingFactor": 1.0 },
    { "childId": "uuid-2", "scalingFactor": 0.5 },
    { "childId": "uuid-3" }
  ]
}

Response 200:
{
  "results": [
    { "childId": "uuid-1", "status": "LINKED", "subscriptionId": 1 },
    { "childId": "uuid-2", "status": "LINKED", "subscriptionId": 2 },
    { "childId": "uuid-3", "status": "ALREADY_LINKED" }
  ]
}
```

## POST /api/v1/child/subscriptions/bulk — Child bulk subscribes to multiple masters

```json
Request:
{
  "masters": [
    { "masterId": "uuid-1", "brokerAccountId": "uuid-broker", "scalingFactor": 1.0 },
    { "masterId": "uuid-2", "brokerAccountId": "uuid-broker" },
    { "masterId": "uuid-3", "brokerAccountId": "uuid-broker", "scalingFactor": 0.5 }
  ]
}

Response 201:
{
  "results": [
    { "masterId": "uuid-1", "status": "SUBSCRIBED", "subscriptionId": 1 },
    { "masterId": "uuid-2", "status": "SUBSCRIBED", "subscriptionId": 2 },
    { "masterId": "uuid-3", "status": "ALREADY_SUBSCRIBED" }
  ]
}
```

Note: Bulk subscribe also follows approval rules — new children go to PENDING_APPROVAL.

---

# 4. SUBSCRIPTION APPROVAL SYSTEM (NEW)

New children require master approval before copy trading starts. Previously approved children can re-subscribe instantly.

## Flow diagram:
```
NEW CHILD subscribes
  → status: PENDING_APPROVAL
  → Master sees in /master/children/pending
  → Master calls /approve or /reject

PREVIOUSLY APPROVED CHILD re-subscribes (after unsubscribing)
  → status: ACTIVE (auto-approved, no master action needed)

MASTER links child directly via /master/children/{childId}/link
  → status: ACTIVE (bypasses approval)
```

## Subscription statuses:

| Status | Meaning |
|--------|---------|
| PENDING_APPROVAL | New child waiting for master to approve |
| ACTIVE | Approved, actively copying trades |
| PAUSED | Temporarily paused by child |
| REJECTED | Master rejected the subscription request |
| INACTIVE | Unsubscribed (record kept for re-subscribe auto-approval) |

## GET /api/v1/master/children/pending — List pending approval requests

```json
Response 200:
{
  "pendingApprovals": [
    {
      "childId": "0e1fbcc0-41d2-494a-9bc6-ff5de0262414",
      "name": "Child One",
      "email": "child1@trading.com",
      "requestedAt": "2026-04-06T06:10:30.803346Z",
      "subscriptionId": 13
    }
  ]
}
```

## POST /api/v1/master/children/{childId}/approve — Approve child

```json
Response 200: { "message": "Child approved" }
Response 400: { "error": "Subscription is not pending approval", "status": 400 }
Response 404: { "error": "Subscription not found", "status": 404 }
```

## POST /api/v1/master/children/{childId}/reject — Reject child

```json
Response 200: { "message": "Child rejected" }
Response 400: { "error": "Subscription is not pending approval", "status": 400 }
Response 404: { "error": "Subscription not found", "status": 404 }
```

## POST /api/v1/child/subscriptions — Subscribe (updated behavior)

```json
Request: { "masterId": "uuid", "brokerAccountId": "uuid" }

Response 201 (new child — needs approval):
{ "subscriptionId": 13, "status": "PENDING_APPROVAL", "message": "Subscription request sent. Waiting for master approval." }

Response 201 (previously approved child re-subscribing):
{ "subscriptionId": 14, "status": "ACTIVE", "message": "Re-subscribed successfully (previously approved)" }

Response 409 (already active/pending):
{ "error": "Already subscribed or pending approval", "status": 409 }
```

## DELETE /api/v1/child/subscriptions/{masterId} — Unsubscribe (updated behavior)

Now sets status to INACTIVE instead of deleting the record. This preserves the approval history so re-subscribing is instant.

```json
Response 200: { "message": "Unsubscribed" }
```

---

# 5. FRONTEND INTEGRATION GUIDE

## Broker login flow:
```
1. GET /brokers → check loginMethod for each broker
2. If "secret" → POST /login with {}
3. If "oauth" → GET /oauth-url → open popup → capture code → POST /login with code
```

## Subscription flow:
```
1. Child calls POST /child/subscriptions → gets PENDING_APPROVAL or ACTIVE
2. If PENDING → show "Waiting for approval" in UI
3. Master checks GET /master/children/pending → shows approve/reject buttons
4. Master approves → child status becomes ACTIVE → copy trading starts
```

## Re-subscribe flow:
```
1. Child unsubscribes → status becomes INACTIVE (not deleted)
2. Child re-subscribes → status becomes ACTIVE immediately (no approval needed)
```
