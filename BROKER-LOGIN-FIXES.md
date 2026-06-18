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

### **Fyers** (Access Token OR OAuth) ✅ NEW
```
Option A: Paste access token from Fyers dashboard
  PUT /api/v1/brokers/accounts/{accountId}/token { "accessToken": "..." }

Option B: OAuth
  1. GET .../oauth-url → open browser popup
  2. User logs in on Fyers → redirect with auth_code
  3. POST .../login { "authCode": "..." }
```

### **Upstox** (OAuth) ✅ FIXED
```
1. Link account (platform API key used)
2. GET .../oauth-url → open browser popup
3. User logs in on Upstox → redirect with code
4. POST .../login { "authCode": "..." }
   - Can retry immediately if first attempt fails (no more 2-min lockout)
```

### **Dhan** (Access Token OR OAuth)
```
Option A: Paste access token from Dhan Web → Profile → DhanHQ Trading APIs
  PUT /api/v1/brokers/accounts/{accountId}/token { "accessToken": "..." }

Option B: OAuth 3-step
  1. POST .../login { "clientId": "..." } → returns consent loginUrl
  2. User opens loginUrl, logs in → redirect with tokenId
  3. POST .../login { "authCode": "tokenId" }
```

### **AngelOne** (TOTP) ✅ FIXED
```
1. Link account
2. PUT /api/v1/brokers/accounts/{accountId}
   {
     "clientId": "A12345",       // Angel One client code
     "apiSecret": "password"     // Angel One password
   }
3. POST .../login { "totpCode": "123456" }  // From authenticator app
```

---

## Testing Checklist

### AngelOne
- [ ] Link account without clientId/apiSecret → Login should fail with clear error: *"Angel One requires clientId (client code) and apiSecret (password) set on the broker account."*
- [ ] Set clientId + apiSecret via Update Account → then login with TOTP → should succeed
- [ ] GET /api/v1/brokers → AngelOne entry should show `requiresUserCredentials: true` and `credentialNote` with clear instructions

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
Step 1: Set Credentials
[ ] Angel One Client Code: _______
[ ] Password: _______
[Save Credentials]

Step 2: Login with TOTP
[ ] TOTP Code: _______
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
