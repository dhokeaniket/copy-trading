# Broker Connection — Frontend Implementation Guide
## Base URL: `https://copy-trading-production-3981.up.railway.app`

---

# STEP 1: Get Broker List

### GET /api/v1/brokers

```json
Response:
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
      "note": "Groww requires per-user credentials. Each user generates their own from Groww settings."
    },
    {
      "brokerId": "ZERODHA",
      "name": "Zerodha",
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "requestToken",
      "requiredFields": []
    },
    {
      "brokerId": "FYERS",
      "name": "Fyers",
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "authCode",
      "requiredFields": []
    },
    {
      "brokerId": "UPSTOX",
      "name": "Upstox",
      "isActive": true,
      "loginMethod": "oauth",
      "loginField": "authCode",
      "requiredFields": []
    }
  ]
}
```

---

# STEP 2: Show UI Based on Broker Type

## If `loginMethod` is "token" (Groww):
Show login options as tabs or radio buttons. Read from `loginOptions` array.
User selects one option → show only the fields listed in that option's `requiredFields`.
All fields should be `type="password"` (masked).

## If `loginMethod` is "oauth" (Zerodha/Fyers/Upstox):
Show just a "Connect" button. No form fields needed.
`requiredFields` is empty — user doesn't enter anything.

---

# STEP 3: Connect — API Calls Per Flow

---

## GROWW — Option 1: Access Token (Recommended, Simplest)

User gets token from: Groww App → Profile → Settings → Trading APIs → Generate Access Token

**One API call, immediately active:**
```
POST /api/v1/brokers/accounts
{
  "brokerId": "groww",
  "accessToken": "paste_token_here",
  "accountNickname": "My Groww"
}

Response 201:
{
  "accountId": "uuid",
  "brokerId": "GROWW",
  "status": "ACTIVE"       ← session active immediately, no login step needed
}
```

**Next:** Call margin/positions directly. No login step.

---

## GROWW — Option 2: API Key + TOTP

User gets API key from: Groww Cloud API Keys Page
User gets TOTP from: their authenticator app (Google/Microsoft Authenticator)

**Two API calls:**
```
Step A: Link account
POST /api/v1/brokers/accounts
{
  "brokerId": "groww",
  "apiKey": "user_api_key",
  "accountNickname": "My Groww"
}
Response: { "accountId": "uuid", "status": "AUTH_REQUIRED" }

Step B: Login with TOTP
POST /api/v1/brokers/accounts/{accountId}/login
{
  "totpCode": "123456"
}
Response: { "status": "SESSION_ACTIVE", "broker": "Groww", "expiresAt": "..." }
```

---

## ZERODHA — OAuth Flow

**Four API calls:**
```
Step A: Link account (no user input needed)
POST /api/v1/brokers/accounts
{
  "brokerId": "zerodha",
  "accountNickname": "My Zerodha"
}
Response: { "accountId": "uuid", "status": "AUTH_REQUIRED" }

Step B: Get OAuth URL
GET /api/v1/brokers/accounts/{accountId}/oauth-url
Response:
{
  "broker": "ZERODHA",
  "loginMethod": "oauth",
  "loginField": "requestToken",
  "oauthUrl": "https://kite.zerodha.com/connect/login?v=3&api_key=xxx"
}

Step C: Open oauthUrl in popup
User logs in with their Zerodha credentials on Zerodha's page.
Zerodha redirects to callback with ?request_token=xxx&status=success
Frontend captures request_token from the redirect URL.

Step D: Login with token
POST /api/v1/brokers/accounts/{accountId}/login
{
  "requestToken": "captured_token"
}
Response: { "status": "SESSION_ACTIVE", "broker": "Zerodha", "expiresAt": "..." }
```

---

## FYERS — OAuth Flow

Same as Zerodha but different field name:
```
Step A: POST /api/v1/brokers/accounts  →  {"brokerId": "fyers", "accountNickname": "My Fyers"}
Step B: GET /api/v1/brokers/accounts/{accountId}/oauth-url  →  get oauthUrl
Step C: Open popup, user logs in, capture auth_code from redirect
Step D: POST /api/v1/brokers/accounts/{accountId}/login  →  {"authCode": "captured_code"}
```

---

## UPSTOX — OAuth Flow

Same as Fyers:
```
Step A: POST /api/v1/brokers/accounts  →  {"brokerId": "upstox", "accountNickname": "My Upstox"}
Step B: GET /api/v1/brokers/accounts/{accountId}/oauth-url  →  get oauthUrl
Step C: Open popup, user logs in, capture code from redirect
Step D: POST /api/v1/brokers/accounts/{accountId}/login  →  {"authCode": "captured_code"}
```

---

# STEP 4: After Connection (Same for ALL Brokers)

### Check status
```
GET /api/v1/brokers/accounts/{accountId}/status
Response: { "sessionActive": true, "broker": "GROWW", "expiresAt": "2026-04-08T06:00:00Z" }
```

### Get margin
```
GET /api/v1/brokers/accounts/{accountId}/margin
Response: { "availableMargin": 100.0, "usedMargin": 0.0, "totalFunds": 100.0, "collateral": 0.0 }
```

### Get positions
```
GET /api/v1/brokers/accounts/{accountId}/positions
Response: { "positions": [...] }
```

### List all connected accounts
```
GET /api/v1/brokers/accounts
Response: { "accounts": [{ "accountId", "brokerId", "nickname", "status", "sessionActive", "linkedAt" }] }
```

### Update account
```
PUT /api/v1/brokers/accounts/{accountId}
{ "apiKey": "new", "apiSecret": "new", "accountNickname": "New Name" }
```

### Delete account
```
DELETE /api/v1/brokers/accounts/{accountId}
Response: { "message": "Account unlinked" }
```

---

# SUMMARY TABLE

| Broker | User Enters | Steps | Daily Re-login |
|--------|------------|-------|----------------|
| Groww (Access Token) | Paste token | 1 call | Yes (token expires 6 AM) |
| Groww (API Key + TOTP) | API key + 6-digit code | 2 calls | Yes (TOTP daily) |
| Zerodha | Nothing (OAuth popup) | 4 calls | Yes (daily) |
| Fyers | Nothing (OAuth popup) | 4 calls | Yes (daily) |
| Upstox | Nothing (OAuth popup) | 4 calls | Yes (daily) |

---

# STATUS VALUES

| Status | Meaning | UI |
|--------|---------|-----|
| AUTH_REQUIRED | Linked but not logged in | Show "Login" button |
| ACTIVE | Session live | Show green dot + balance |
| LINKED | Has token, not verified | Show "Verify" button |

| sessionActive | Meaning |
|---------------|---------|
| true | Connected, can trade |
| false | Needs re-login |
