# Frontend — Proxy Settings Integration

## Overview

Users can optionally configure a proxy on their broker account. This routes all their broker API calls (order placement, margin check, etc.) through a specific IP address. Required for brokers that enforce IP whitelisting (Dhan, Groww, Angel One).

---

## API Endpoints

### 1. Update Proxy on Existing Account

```
PUT /api/v1/broker/accounts/{accountId}
Authorization: Bearer <token>
Content-Type: application/json

{
  "proxyHost": "127.0.0.1",
  "proxyPort": 8889,
  "proxyUser": "",       // optional — only for external proxy providers
  "proxyPass": ""        // optional — only for external proxy providers
}
```

**Response:** `{"message": "Account updated"}`

### 2. Set Proxy When Linking a New Account

```
POST /api/v1/broker/accounts
Authorization: Bearer <token>
Content-Type: application/json

{
  "brokerId": "DHAN",
  "clientId": "1110569575",
  "apiKey": "xxx",
  "apiSecret": "yyy",
  "proxyHost": "127.0.0.1",
  "proxyPort": 8889,
  "proxyUser": "",
  "proxyPass": ""
}
```

### 3. Remove Proxy (Back to Direct Connection)

```
PUT /api/v1/broker/accounts/{accountId}
Authorization: Bearer <token>
Content-Type: application/json

{
  "proxyHost": "",
  "proxyPort": 0
}
```

### 4. Get Account Info (Shows Proxy Status)

```
GET /api/v1/broker/accounts
Authorization: Bearer <token>
```

**Response includes:**
```json
{
  "accounts": [
    {
      "accountId": "efb07109-fac6-46ff-a7d3-bc3889c7dad2",
      "brokerId": "DHAN",
      "brokerName": "Dhan",
      "clientId": "1110569575",
      "status": "ACTIVE",
      "sessionActive": true,
      "proxyHost": "127.0.0.1",
      "proxyPort": 8889,
      "proxyConfigured": true
    }
  ]
}
```

---

## Frontend UI Changes

### Option A: Admin-Only (Recommended for now)

Add a "Proxy Settings" section in the **Admin Panel** → User Management → Broker Accounts:

```
┌─────────────────────────────────────────┐
│ User: Harsh Account Testing             │
│ Broker: Dhan (1110569575)               │
│ Status: ACTIVE ✓                        │
│                                         │
│ ── Proxy Settings ──                    │
│ Proxy Host: [127.0.0.1    ]             │
│ Proxy Port: [8889         ]             │
│ Exit IP:    13.61.58.89 (display only)  │
│ [Save Proxy]  [Remove Proxy]           │
└─────────────────────────────────────────┘
```

### Option B: User Self-Service

Add an expandable "Advanced: IP Routing" section on the broker account settings page:

```
┌─────────────────────────────────────────┐
│ Dhan Account Settings                   │
│                                         │
│ Client ID: 1110569575                   │
│ Status: Connected ✓                     │
│                                         │
│ ▼ Advanced: IP Routing                  │
│ ┌─────────────────────────────────────┐ │
│ │ Proxy Host: [                     ] │ │
│ │ Proxy Port: [                     ] │ │
│ │ Username:   [                     ] │ │
│ │ Password:   [                     ] │ │
│ │                                     │ │
│ │ Status: ● Proxy Active (13.61.58.89)│ │
│ │ [Update]  [Remove Proxy]           │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ ℹ️ Whitelist the exit IP in your broker │
│    dashboard for API access.            │
└─────────────────────────────────────────┘
```

### Option C: Dropdown of Available IPs (Best UX)

Pre-configure a list of available IPs on the backend. Frontend shows a dropdown:

```
┌─────────────────────────────────────────┐
│ Select IP for broker whitelist:         │
│                                         │
│ [▼ Choose IP                          ] │
│   • 13.53.246.13 (Default - Stockholm)  │
│   • 13.61.58.89 (Stockholm Secondary)   │
│   • 15.207.175.205 (Mumbai 1)           │
│   • 3.108.243.110 (Mumbai 2)            │
│   • 15.207.205.137 (Mumbai 3)           │
│                                         │
│ Whitelist this IP in your Dhan/Groww    │
│ API dashboard.                          │
│                                         │
│ [Assign IP]                             │
└─────────────────────────────────────────┘
```

For this option, you'd maintain a mapping of:
```
IP → proxyHost + proxyPort
```
Frontend shows the IP dropdown, backend maps the selected IP to the correct proxy config.

---

## Frontend Code Example (React/Next.js)

### Update Proxy

```javascript
const updateProxy = async (accountId, proxyHost, proxyPort) => {
  const res = await fetch(`/api/v1/broker/accounts/${accountId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify({ proxyHost, proxyPort })
  });
  return res.json();
};
```

### Display Proxy Status

```javascript
// From GET /api/v1/broker/accounts response
{accounts.map(account => (
  <div key={account.accountId}>
    <p>{account.brokerName} - {account.clientId}</p>
    {account.proxyConfigured ? (
      <span className="badge badge-green">
        Proxy: {account.proxyHost}:{account.proxyPort}
      </span>
    ) : (
      <span className="badge badge-gray">Direct (no proxy)</span>
    )}
  </div>
))}
```

---

## IP Pool Mapping (for dropdown approach)

If you want a dropdown, maintain this mapping in your admin config or a simple API:

| Display Label | Exit IP | proxyHost | proxyPort |
|---|---|---|---|
| Default (Stockholm) | 13.53.246.13 | _(no proxy)_ | — |
| Stockholm 2 | 13.61.58.89 | 127.0.0.1 | 8889 |
| Mumbai 1 | 15.207.175.205 | mumbai-ec2-ip | 3128 |
| Mumbai 2 | 3.108.243.110 | mumbai-ec2-ip | 3129 |
| Mumbai 3 | 15.207.205.137 | mumbai-ec2-ip | 3130 |

---

## Notes

- `proxyPass` is never returned in GET responses (security)
- `proxyConfigured: true/false` tells frontend if proxy is active
- Changing proxy evicts the cached HTTP client — takes effect on next trade
- No app restart needed when proxy is changed
