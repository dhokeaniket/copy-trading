# Frontend Changes — Zerodha Per-User + Upstox Fix

---

## 1. Zerodha: Link Account (with user's own API credentials)

### Request

```
POST /api/v1/brokers/accounts
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "brokerId": "ZERODHA",
  "nickname": "my-zerodha",
  "apiKey": "pl2lh6a21bdk4rbe",
  "apiSecret": "k8s9d7f6g5h4j3k2l1"
}
```

### Response (201)

```json
{
  "accountId": "14901704-d728-4e73-a18a-4e936beae4e7",
  "brokerId": "ZERODHA",
  "nickname": "my-zerodha",
  "status": "LINKED",
  "sessionActive": false
}
```

---

## 2. Zerodha: Update Credentials (if user needs to change keys)

### Request

```
PUT /api/v1/brokers/accounts/{accountId}
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "apiKey": "new_api_key_here",
  "apiSecret": "new_api_secret_here"
}
```

### Response (200)

```json
{
  "message": "Account updated"
}
```

---

## 3. Zerodha: Get Login Options (shows OAuth URL built with user's key)

### Request

```
GET /api/v1/brokers/accounts/{accountId}/status
Authorization: Bearer <jwt>
```

### Response (200) — when user HAS set apiKey

```json
{
  "accountId": "14901704-d728-4e73-a18a-4e936beae4e7",
  "status": "LINKED",
  "sessionActive": false,
  "broker": "ZERODHA",
  "connectionHealth": "down",
  "requiresReconnect": true,
  "oauthUrl": "https://kite.zerodha.com/connect/login?v=3&api_key=USER_OWN_KEY",
  "message": "Open oauthUrl in browser, then POST login with requestToken from callback.",
  "loginOptions": [
    {
      "method": "oauth",
      "description": "Login with Zerodha Kite in browser (requires your own API key from developers.kite.trade)",
      "requiredFields": [],
      "api": "GET .../oauth-url → open popup → POST .../login { requestToken }"
    }
  ]
}
```

### Response (200) — when user has NOT set apiKey

```json
{
  "accountId": "14901704-d728-4e73-a18a-4e936beae4e7",
  "sessionActive": false,
  "needsCredentials": true,
  "requiredFields": ["apiKey", "apiSecret"],
  "message": "Set your Zerodha API key+secret first (from developers.kite.trade), then open oauthUrl."
}
```

---

## 4. Zerodha: Login with Request Token (after OAuth redirect)

### Request

```
POST /api/v1/brokers/accounts/{accountId}/login
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "requestToken": "abc123xyz456"
}
```

### Response (200) — success

```json
{
  "status": "SESSION_ACTIVE",
  "broker": "Zerodha",
  "expiresAt": "2026-06-10T06:00:00Z"
}
```

### Response (400) — missing key

```json
{
  "status": 400,
  "error": "Zerodha API key and secret are required. Go to developers.kite.trade, create an app, and update your broker account with apiKey + apiSecret."
}
```

---

## 5. Zerodha: Broker List (shows requiresUserCredentials flag)

### Request

```
GET /api/v1/brokers
Authorization: Bearer <jwt>
```

### Response (200) — Zerodha section

```json
{
  "brokerId": "ZERODHA",
  "brokerName": "Zerodha",
  "loginMethod": "oauth",
  "requiresUserCredentials": true,
  "credentialFields": ["apiKey", "apiSecret"],
  "credentialNote": "Each user needs their own Kite Connect app. Go to developers.kite.trade → create app → set redirect URL to: https://api.ascentracapital.com/api/v1/brokers/callback",
  "loginOptions": [
    {
      "method": "oauth",
      "description": "Login with Zerodha Kite in browser (requires your own API key from developers.kite.trade)",
      "requiredFields": [],
      "api": "GET .../oauth-url → open popup → POST .../login { requestToken }"
    }
  ]
}
```

---

## 6. Upstox: Login (auth code — single use)

### Request

```
POST /api/v1/brokers/accounts/{accountId}/login
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "authCode": "one_time_code_from_oauth_redirect"
}
```

### Response (200) — success

```json
{
  "status": "SESSION_ACTIVE",
  "broker": "Upstox",
  "expiresAt": "2026-06-10T06:00:00Z"
}
```

### Response (400) — duplicate code (NEW)

```json
{
  "status": 400,
  "error": "This auth code was already used. Please get a fresh one by opening the Upstox login page again."
}
```

### Response (502) — Upstox rejected the code

```json
{
  "status": 502,
  "error": "Upstox API error: Upstox 401 UNAUTHORIZED: {\"errors\":[{\"message\":\"Invalid Auth code\"}]}"
}
```

**Frontend rule**: On 400 or 502, do NOT retry. Show "Retry Login" button that opens fresh OAuth URL.

---

## Summary

| Broker | Link Request Fields | Login Request Fields |
|--------|--------------------|--------------------|
| ZERODHA | `brokerId`, `nickname`, `apiKey`, `apiSecret` | `requestToken` |
| UPSTOX | `brokerId`, `nickname` | `authCode` (single-use, no retry) |
| GROWW | `brokerId`, `nickname`, `apiKey`, `apiSecret` | `totpCode` or PUT `/token` with `accessToken` |
| DHAN | `brokerId`, `nickname` | `authCode` (tokenId) or PUT `/token` |
| FYERS | `brokerId`, `nickname` | `authCode` |
| ANGELONE | `brokerId`, `nickname`, `apiKey`, `apiSecret` | `totpCode` |
