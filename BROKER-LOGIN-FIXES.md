# Broker Login Fixes — AngelOne, Fyers, Upstox

## Summary of Issues Fixed

### 1. **AngelOne - Login Flow Clarification** ✅ FIXED
**Problem:** UI was asking for `clientId`, `apiSecret`, and `totpCode` all at once, causing confusion. Users didn't know they needed to set credentials BEFORE calling login.

**Root Cause:** `loginOptions` listed all three fields as `requiredFields`, making it seem like they should all be provided in the login request.

**Fix:**
- Changed `requiredFields` to only `["totpCode"]` for the login call
- Added `requiresUserCredentials: true` and `credentialFields: ["clientId", "apiSecret"]` to indicate these must be set via Update Account first
- Updated description: *"TOTP from authenticator app (requires client code + password set first via Update Account)"*
- Added `credentialNote`: *"First set your Angel One client code (clientId) and password (apiSecret) via Update Account, then login with TOTP code."*

**Correct Flow:**
```
Step 1: Link Account
POST /api/v1/brokers/accounts
{
  "brokerId": "ANGELONE",
  "accountNickname": "My Angel Account"
}
→ Returns accountId

Step 2: Set Client Code & Password
PUT /api/v1/brokers/accounts/{accountId}
{
  "clientId": "A12345",        // Your Angel One client code
  "apiSecret": "your_password"  // Your Angel One password
}

Step 3: Login with TOTP
POST /api/v1/brokers/accounts/{accountId}/login
{
  "totpCode": "123456"  // 6-digit code from authenticator app
}
→ Session active
```

---

### 2. **Fyers - Missing Access Token Option** ✅ FIXED
**Problem:** Fyers only showed OAuth login. Users who generate access tokens from Fyers API dashboard had no way to paste them.

**Fix:**
- Added `accessToken` login option to Fyers (same pattern as Groww/Dhan)
- Users can now either:
  - **Option A (Recommended):** Paste access token from Fyers dashboard → `PUT /api/v1/brokers/accounts/{accountId}/token`
  - **Option B:** OAuth browser login → captures `authCode` → exchanges for token

**New Fyers Login Options:**
```json
{
  "brokerId": "FYERS",
  "loginOptions": [
    {
      "method": "accessToken",
      "description": "Paste access token from Fyers API dashboard",
      "requiredFields": ["accessToken"],
      "endpoint": "PUT /api/v1/brokers/accounts/{accountId}/token"
    },
    {
      "method": "oauth",
      "description": "Login with Fyers in browser",
      "requiredFields": [],
      "endpoint": "GET .../oauth-url → open popup → POST .../login { authCode }"
    }
  ]
}
```

---

### 3. **Upstox - "Double Login" Issue** ✅ FIXED
**Problem:** Backend was tracking used auth codes in a local cache (`usedAuthCodes` map) for 2 minutes to prevent "reuse." This caused issues:
- If user's first login attempt **failed** (network error, expired session, etc.), they couldn't retry with the same auth code
- Upstox API **already rejects reused codes** at the broker level, so the backend dedup was redundant

**Fix:**
- **Removed** the `usedAuthCodes` deduplication logic entirely
- Upstox API will return an error if a code is actually reused (already consumed)
- Users can now retry failed login attempts without waiting 2 minutes

**Code Removed:**
```java
// OLD (REMOVED):
String codeKey = "UPSTOX:" + req.getAuthCode().substring(0, Math.min(10, req.getAuthCode().length()));
Long prev = usedAuthCodes.putIfAbsent(codeKey, System.currentTimeMillis());
if (prev != null) {
    log.warn("UPSTOX_DUPLICATE_AUTH_CODE accountId={} — rejecting reused code", a.getId());
    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "This auth code was already used. Please get a fresh one..."));
}
usedAuthCodes.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > 120_000);

// NEW (SIMPLIFIED):
// Upstox auth codes are single-use at the broker API level — no need for backend dedup.
// Removed local dedup to allow retries after failed attempts (network errors, etc.).
```

**Benefit:** Users can now retry immediately if login fails due to transient errors.

---

## Complete Login Flow Reference

### **Groww** (Already Working)
```
Option A: Paste access token from Groww App → Settings → Trading APIs
  PUT /api/v1/brokers/accounts/{accountId}/token { "accessToken": "..." }

Option B: API key + TOTP (requires IP whitelisting in Groww dashboard)
  1. Link account with apiKey + apiSecret
  2. POST .../login { "totpCode": "123456" }
```

### **Zerodha** (OAuth)
```
1. Link account (platform API key used)
2. GET .../oauth-url → open browser popup
3. User logs in on Zerodha Kite → redirect with request_token
4. POST .../login { "requestToken": "..." }
```

### **Fyers** (Access Token OR OAuth) ✅
```
Option A: Paste access token from Fyers dashboard
  PUT /api/v1/brokers/accounts/{accountId}/token { "accessToken": "..." }

Option B: OAuth (each user’s MyAPI app)
  1. PUT .../accounts/{accountId} { "apiKey": "<MyAPI app id>", "apiSecret": "<secret>" }
  2. GET .../oauth-url → open browser popup
  3. User logs in on Fyers → redirect with auth_code
  4. POST .../login { "authCode": "...", "redirectUri": "..." }
```
No platform `brokers.fyers` — credentials live only on the broker account.

### **Upstox** (OAuth) ✅
```
1. PUT .../accounts/{accountId} { "apiKey": "<client_id>", "apiSecret": "<client_secret>" }
2. GET .../oauth-url → open browser popup
3. User logs in on Upstox → redirect with code
4. POST .../login { "authCode": "...", "redirectUri": "..." }
   - Can retry immediately if first attempt fails (no more 2-min lockout)
```
No platform `brokers.upstox` — each user uses their own Upstox developer app.

### **Dhan** (Access Token OR OAuth)
```
Option A: Paste access token from Dhan Web → Profile → DhanHQ Trading APIs
  PUT /api/v1/brokers/accounts/{accountId}/token { "accessToken": "..." }

Option B: OAuth 3-step
  1. POST .../login { "clientId": "..." } → returns consent loginUrl
  2. User opens loginUrl, logs in → redirect with tokenId
  3. POST .../login { "authCode": "tokenId" }
```

### **AngelOne** (per-account SmartAPI app + TOTP) ✅
```
1. Link account (POST) with brokerId ANGELONE — optionally include apiKey, clientId, apiSecret
2. PUT /api/v1/brokers/accounts/{accountId}
   {
     "apiKey": "YOUR_SMARTAPI_APP_KEY",   // from user's Angel SmartAPI app (X-PrivateKey)
     "clientId": "A12345",                // Angel client code
     "apiSecret": "1234"                  // 4-digit MPIN / trading PIN (not 6-digit TOTP)
   }
3. POST .../login { "totpCode": "123456" }  // 6-digit authenticator TOTP
```
No platform `brokers.angelone` / `ANGELONE_API_KEY` — each user registers their own SmartAPI app.

### API hints when something is wrong (Fyers / Upstox)

| Situation | What the API returns |
|-----------|----------------------|
| Skipped PUT (no apiKey+apiSecret) | `GET .../oauth-url` / login-options: `oauthUrl: null`, `needsCredentials: true`, `errorCode: "CREDENTIALS_REQUIRED"`, `action: "PUT_BROKER_CREDENTIALS"`, plus `effectiveRedirectUri` and `brokerRedirectRegistrationHint` (exact URL to register in the broker app). |
| Redirect mismatch or bad secret at token exchange | `POST .../login` → **400** with a message that names redirect_uri / client_secret / fresh auth code; raw broker snippet is trimmed at the end. |
| Wrong app / code | Same **400** path when the error body matches known patterns; otherwise **502** with the broker message. |

---

## Testing Checklist

### AngelOne
- [ ] Link account without apiKey/clientId/apiSecret → Login should fail with clear error about missing SmartAPI apiKey or credentials
- [ ] Set apiKey + clientId + apiSecret via PUT → then login with TOTP → should succeed
- [ ] GET /api/v1/brokers → AngelOne entry should show `credentialFields` including apiKey and `credentialNote` with MPIN vs TOTP

### Fyers
- [ ] GET /api/v1/brokers → Fyers entry should show TWO login options: `accessToken` and `oauth`
- [ ] Paste access token via `PUT .../token` → session should activate immediately
- [ ] OAuth login → should work as before

### Upstox
- [ ] Get fresh auth code from Upstox OAuth
- [ ] POST .../login with auth code → simulate network failure (disconnect internet briefly)
- [ ] Retry with **same auth code** → should work (no "already used" error from backend)
- [ ] Actually use the code twice → Upstox API should return error (not backend rejection)

---

## Frontend Changes Needed

### AngelOne Login UI
**Old (Incorrect):**
```
[ ] Angel One Client Code: _______
[ ] Password: _______
[ ] TOTP Code: _______
[Connect]
```

**New (Correct):**
```
Step 1: Set credentials (user's own SmartAPI app)
[ ] SmartAPI API Key (from Angel app dashboard): _______
[ ] Angel client code: _______
[ ] MPIN (4-digit trading PIN): _______
[Save]

Step 2: Login with TOTP
[ ] 6-digit TOTP: _______
[Connect]
```

### Fyers Login UI
**Old:**
```
[Login with Fyers in Browser]
```

**New:**
```
Option 1: Paste Access Token
[ ] Access Token: _______________________________
[Connect]

OR

Option 2: Browser Login
[Login with Fyers OAuth]
```

---

## Summary
| Broker | Issue | Status |
|--------|-------|--------|
| **AngelOne** | UI asking for all credentials at once | ✅ Fixed — split into 2 steps |
| **Fyers** | Missing access token option | ✅ Fixed — added access token login |
| **Upstox** | "Double login" / auth code reuse rejection | ✅ Fixed — removed backend dedup |

All fixes are **backward compatible** — existing users with active sessions are unaffected. New logins will use the corrected flows.
