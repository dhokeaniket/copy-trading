# Broker disconnect, reconnect & notifications (all brokers)

## Disconnect (any broker)

```http
POST /api/v1/brokers/accounts/{accountId}/disconnect
Authorization: Bearer <jwt>
```

**Response** includes full `loginOptions` (same shape as `GET /api/v1/brokers`) plus:

- `accountId`, `status`, `sessionActive`, `requiresReconnect`
- `loginOptionsUrl` — `/api/v1/brokers/accounts/{id}/login-options`
- `oauthUrl` — when applicable (Zerodha, Fyers, Upstox)
- `notificationType`: `BROKER_DISCONNECTED`

### Login options per broker

| Broker | `loginOptions` methods |
|--------|------------------------|
| **Groww** | `accessToken`, `apiKeyWithTotp` |
| **Dhan** | `accessToken`, `oauth` |
| **Zerodha** | `oauth` (Kite popup) |
| **Fyers** | `oauth` |
| **Upstox** | `oauth` |
| **Angel One** | `totp` (clientId + password + TOTP) |

After disconnect, **always** render every entry in `loginOptions` — do not hide options based on old stored keys.

### Reconnect APIs

```http
GET /api/v1/brokers/accounts/{accountId}/login-options
GET /api/v1/brokers/accounts/{accountId}/oauth-url
GET /api/v1/brokers/accounts/{accountId}/status   # includes loginOptions when session inactive
```

---

## How notifications are displayed

The backend delivers notifications in **two channels**. The frontend should use **both**.

### 1. REST — notification bell / inbox

```http
GET /api/v1/notifications
```

```json
{
  "notifications": [
    {
      "id": "uuid",
      "type": "BROKER_DISCONNECTED",
      "title": "Broker disconnected",
      "message": "Your Groww account was disconnected. Tap Reconnect to sign in again.",
      "read": false,
      "createdAt": "2026-05-27T12:00:00Z"
    }
  ]
}
```

- Show in **bell icon** with unread count (`read: false`).
- On tap → open reconnect flow using `GET .../login-options` (account id from your local state or refetch `GET /brokers/accounts`).

```http
PATCH /api/v1/notifications/{id}/read
POST  /api/v1/notifications/read-all
```

### 2. WebSocket — live toast / banner

Connect once after login:

```javascript
const ws = new WebSocket('wss://api.ascentracapital.com/ws/notifications');
// or ws://host:8081/ws/notifications for direct EC2

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.event === 'NOTIFICATION') {
    const n = msg.data;
    // n.type, n.title, n.message, n.accountId, n.brokerId, n.action, n.loginOptionsUrl
    showToast(n.title, n.message);
    if (n.type === 'BROKER_DISCONNECTED' || n.type === 'BROKER_RECONNECT_REQUIRED') {
      showReconnectBanner(n.brokerName, n.accountId, n.loginOptionsUrl);
    }
  }
  if (msg.event === 'SESSION_EXPIRED') {
    // Copy engine — child session died during trading
    showReconnectBanner(msg.data.broker, msg.data.accountId);
  }
};
```

**Disconnect / manual reconnect** → `event: "NOTIFICATION"`, `data.type: "BROKER_DISCONNECTED"`.

**Extra WebSocket fields** (only on WS, not in REST list):

| Field | Example | Use |
|-------|---------|-----|
| `accountId` | uuid | Open reconnect for this account |
| `brokerId` | `GROWW` | Broker icon / label |
| `brokerName` | `Groww` | Display text |
| `action` | `RECONNECT` | Show "Reconnect" CTA |
| `loginOptionsUrl` | `/api/v1/brokers/accounts/.../login-options` | Fetch login UI config |

### Notification types (engine metadata)

```http
GET /api/v1/engine/metadata
```

Includes: `BROKER_DISCONNECTED`, `BROKER_RECONNECT_REQUIRED`, `SESSION_EXPIRED`, `SESSION_EXPIRING`, `SESSION_REMINDER`, `TRADE_COPIED`, `TRADE_FAILED`, `MARKET_CLOSED`.

### UI mapping (recommended)

| `type` | UI |
|--------|-----|
| `BROKER_DISCONNECTED` | Toast + broker card "Disconnected" + **Reconnect** button → login-options modal |
| `BROKER_RECONNECT_REQUIRED` | Same (auto expiry / 401 from broker) |
| `SESSION_EXPIRED` | Banner on copy-trading / child dashboard |
| `SESSION_EXPIRING` | Yellow warning before market open |
| `SESSION_REMINDER` | Morning reminder to login |
| `TRADE_COPIED` | Success toast |
| `TRADE_FAILED` | Error toast |

### Telegram (optional)

If user linked Telegram (`POST /notifications/telegram/link`), session reminders may also arrive on Telegram. Broker disconnect notifications are **in-app + WebSocket** only unless you add Telegram in `notifyBrokerReconnect` later.

---

## Frontend reconnect flow (all brokers)

```
User taps Disconnect
  → POST .../disconnect
  → Response has loginOptions → cache for modal

User taps Reconnect (later)
  → GET .../login-options  (or use cached loginOptions)
  → Render all loginOptions tabs
  → User picks method → call matching API (token / oauth-url+login / totp)
  → GET .../status until sessionActive === true
```
