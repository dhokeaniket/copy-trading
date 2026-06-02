# Frontend Fix: Groww Broker Reconnect — Show All Login Options

## Problem
When a user clicks "Reconnect" on a disconnected Groww account, the UI only shows the TOTP login form. Users who originally connected via Access Token can't reconnect because that option isn't visible.

## Root Cause
The reconnect modal was hardcoded to show only the `apiKeyWithTotp` form for Groww. The backend now returns `recommendedLoginMethod` dynamically based on how the user originally connected.

## Backend API (already deployed)

### Step 1: Get Login Options

```
GET /api/v1/brokers/accounts/{accountId}/login-options
Authorization: Bearer <token>
```

**Response:**
```json
{
  "brokerId": "GROWW",
  "brokerName": "Groww",
  "accountId": "abc-123-uuid",
  "status": "LINKED",
  "sessionActive": false,
  "requiresReconnect": true,
  "hasStoredApiKey": false,
  "recommendedLoginMethod": "accessToken",
  "platformServerIp": "13.53.246.13",
  "requiresIpWhitelist": true,
  "loginOptions": [
    {
      "method": "accessToken",
      "description": "Paste access token from Groww settings (no API key needed)",
      "requiredFields": ["accessToken"],
      "endpoint": "PUT /api/v1/brokers/accounts/{accountId}/token"
    },
    {
      "method": "apiKeyWithTotp",
      "description": "API key + TOTP code from authenticator app (recommended after disconnect)",
      "requiredFields": ["apiKey", "totpCode"],
      "endpoint": "POST /api/v1/brokers/accounts/{accountId}/login { totpCode }"
    }
  ],
  "loginOptionMethods": ["accessToken", "apiKeyWithTotp"],
  "note": "Groww requires whitelisting platformServerIp in your Groww API dashboard."
}
```

### Step 2a: Login via Access Token

```
PUT /api/v1/brokers/accounts/{accountId}/token
Authorization: Bearer <token>
Content-Type: application/json

{
  "accessToken": "eyJraWQiOiJaTUtjVXci..."
}
```

**Success Response:**
```json
{
  "status": "SESSION_ACTIVE",
  "message": "Access token saved. Session active until 2026-06-03T06:00:00Z",
  "broker": "GROWW",
  "accountId": "abc-123-uuid",
  "loginMethod": "accessToken"
}
```

### Step 2b: Login via API Key + TOTP

```
POST /api/v1/brokers/accounts/{accountId}/login
Authorization: Bearer <token>
Content-Type: application/json

{
  "totpCode": "482910"
}
```

**Success Response:**
```json
{
  "status": "SESSION_ACTIVE",
  "message": "Groww session active",
  "broker": "GROWW",
  "accountId": "abc-123-uuid",
  "loginMethod": "apiKeyWithTotp"
}
```

**Error (401 from Groww):**
```json
{
  "status": 502,
  "message": "Login failed: 401 Unauthorized"
}
```

---

## Frontend Implementation Guide

### UI Layout

```
┌─────────────────────────────────────────────────┐
│  Reconnect Groww Account                         │
│                                                  │
│  ⚠️ Whitelist IP: 13.53.246.13 in Groww dashboard │
│                                                  │
│  ┌──────────────┐  ┌──────────────────┐         │
│  │ Access Token │  │ API Key + TOTP   │         │
│  └──────────────┘  └──────────────────┘         │
│                                                  │
│  ── Tab 1: Access Token (pre-selected) ──        │
│                                                  │
│  [ Paste your Groww access token here    ]       │
│                                                  │
│  How to get: Groww App → Profile → Settings →    │
│  DhanHQ Trading APIs → Copy Access Token         │
│                                                  │
│              [ Connect ]                         │
└─────────────────────────────────────────────────┘
```

### Logic (pseudocode)

```typescript
// 1. Fetch login options when modal opens
const res = await api.get(`/brokers/accounts/${accountId}/login-options`);
const { loginOptions, recommendedLoginMethod, platformServerIp } = res.data;

// 2. Set active tab to recommended method
const [activeTab, setActiveTab] = useState(recommendedLoginMethod);

// 3. Render tabs from loginOptions array
loginOptions.map(option => (
  <Tab key={option.method} active={activeTab === option.method}>
    {option.method === 'accessToken' && <AccessTokenForm accountId={accountId} />}
    {option.method === 'apiKeyWithTotp' && <TotpForm accountId={accountId} />}
  </Tab>
));

// 4. Submit handlers
async function submitAccessToken(token: string) {
  await api.put(`/brokers/accounts/${accountId}/token`, { accessToken: token });
  // Refresh account list
}

async function submitTotp(totpCode: string) {
  await api.post(`/brokers/accounts/${accountId}/login`, { totpCode });
  // Refresh account list
}
```

### Key Fields to Use

| Field | Purpose |
|-------|---------|
| `recommendedLoginMethod` | Which tab to pre-select (`"accessToken"` or `"apiKeyWithTotp"`) |
| `loginOptions` | Array of available login methods — render ALL of them |
| `loginOptions[].method` | Unique ID for the method |
| `loginOptions[].description` | Helper text to show user |
| `loginOptions[].requiredFields` | What form fields to render |
| `platformServerIp` | IP user must whitelist in Groww dashboard |
| `requiresIpWhitelist` | Show IP whitelist warning banner |
| `hasStoredApiKey` | If `false`, don't show "Use stored key" shortcut |

### When `recommendedLoginMethod` values appear

| Scenario | Value | Reason |
|----------|-------|--------|
| User originally connected with access token, no API key saved | `"accessToken"` | Simpler reconnect path |
| User has API key stored (linked with key+secret) | `"apiKeyWithTotp"` | Can use TOTP without re-pasting token |

### Error Handling

| Error | Show to user |
|-------|-------------|
| 401 from Groww | "Invalid token or IP not whitelisted. Whitelist `13.53.246.13` in your Groww API dashboard." |
| 502 Bad Gateway | "Groww API unreachable. Try again in a moment." |
| Token expired | "Token expired. Generate a new one from Groww app." |

---

## Also Applies To: Dhan

Dhan has the same dual-option pattern:
- `accessToken` → `PUT /api/v1/brokers/accounts/{accountId}/token`
- `oauth` → Browser flow via `GET /api/v1/brokers/accounts/{accountId}/oauth-url`

Use the same `loginOptions` array approach for Dhan too.

---

## Summary of Changes

| Layer | Change | Status |
|-------|--------|--------|
| Backend | `recommendedLoginMethod` now dynamic based on `hasStoredApiKey` | ✅ Deployed |
| Frontend | Reconnect modal reads `loginOptions` array and renders all options | ❌ Needs FE update |
| Frontend | Pre-selects tab based on `recommendedLoginMethod` | ❌ Needs FE update |
| Frontend | Shows IP whitelist warning from `platformServerIp` | ❌ Nice to have |
