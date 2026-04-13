# NEW: Broker Dashboard API

> Single API to fetch all demat account details after broker connection.

## Endpoint

```
GET /api/v1/brokers/accounts/{accountId}/dashboard
```

**Auth:** `Authorization: Bearer <accessToken>`

## When to Call

After broker login succeeds (`SESSION_ACTIVE`):

```
1. POST /brokers/accounts              → Link (get accountId)
2. POST /brokers/accounts/{id}/login   → Login (get SESSION_ACTIVE)
3. GET  /brokers/accounts/{id}/dashboard → Fetch everything ✅
```

## Response

```json
{
  "accountId": "60120a19-23b8-4f6a-8007-81c2054a509e",
  "brokerId": "ZERODHA",
  "brokerName": "Zerodha",
  "clientId": "DRX617",
  "nickname": "My Zerodha",
  "status": "ACTIVE",
  "sessionActive": true,

  "profile": {
    "name": "Aniket Dhoke",
    "email": "aniket@example.com",
    "clientId": "DRX617",
    "broker": "Zerodha",
    "exchanges": ["NSE", "BSE", "NFO"],
    "products": ["CNC", "MIS", "NRML"]
  },

  "margin": {
    "availableMargin": 75000.50,
    "usedMargin": 25000.00,
    "totalFunds": 100000.50,
    "collateral": 0
  },

  "positions": [
    {
      "tradingsymbol": "RELIANCE",
      "quantity": 10,
      "average_price": 2500.0,
      "pnl": 150.0
    }
  ],

  "holdings": [
    {
      "tradingsymbol": "TCS",
      "quantity": 5,
      "average_price": 3500.0,
      "last_price": 3600.0
    }
  ],

  "orders": [
    {
      "order_id": "123456789",
      "tradingsymbol": "RELIANCE",
      "transaction_type": "BUY",
      "quantity": 10,
      "status": "COMPLETE"
    }
  ]
}
```

## Fields

| Field | Type | Description |
|-------|------|-------------|
| profile | object | Broker user info — name, email, clientId, broker name |
| margin | object | availableMargin, usedMargin, totalFunds, collateral |
| positions | array | Open intraday/F&O positions |
| holdings | array | Long-term stock holdings |
| orders | array | Today's orders |

## Profile Fields by Broker

| Broker | Extra Fields |
|--------|-------------|
| Zerodha | exchanges, products |
| Fyers | pan |
| Upstox | exchanges |
| Groww | — |
| Dhan | — |

All brokers return: `name`, `email`, `clientId`, `broker`

## Error Handling

If a section fails, it returns `error` instead of data (rest still works):

```json
{
  "profile": { "name": "Aniket", "broker": "Zerodha" },
  "margin": { "availableMargin": 75000 },
  "positions": [],
  "holdings": { "error": "Zerodha API timeout" },
  "orders": []
}
```

If session expired:
```json
{ "status": 400, "message": "No active broker session. Login first." }
```

## Individual Endpoints (if needed separately)

| Endpoint | Returns |
|----------|---------|
| `GET /brokers/accounts/{id}/margin` | Balance only |
| `GET /brokers/accounts/{id}/positions` | Positions only |
| `GET /brokers/accounts/{id}/holdings` | Holdings only |
| `GET /brokers/accounts/{id}/orders` | Orders only |
| `GET /brokers/accounts/{id}/trades` | Trades only |
| `GET /brokers/accounts/{id}/test` | Connection health check |


---

## NEW: Balance Alert API

Checks if child's broker balance is low. Pushes notifications automatically when trades are copied.

### Endpoint

```
GET /api/v1/brokers/accounts/{accountId}/balance-alert
```

**Auth:** Bearer token

### Response

```json
{
  "alertLevel": "WARNING",
  "message": "Balance kam hai. Add funds to avoid trade copy failures.",
  "availableMargin": 3500.00,
  "usedMargin": 1500.00,
  "totalFunds": 5000.00,
  "thresholds": {
    "critical": 1000,
    "warning": 5000,
    "low": 10000
  }
}
```

### Alert Levels

| Level | Condition | Color | Meaning |
|-------|-----------|-------|---------|
| `CRITICAL` | Balance < ₹1,000 | 🔴 Red | Trade copy will fail! Add funds now |
| `WARNING` | Balance < ₹5,000 | 🟡 Yellow | Balance kam hai, add funds |
| `LOW` | Balance < ₹10,000 | 🟠 Orange | Balance low, keep an eye |
| `OK` | Balance ≥ ₹10,000 | 🟢 Green | All good |

### Auto Notifications

When a trade is copied to a child account, the system automatically checks their balance and pushes a notification if low. Child sees it in `GET /notifications`.

---

## NEW: Connection Signal API

Like mobile network bars — shows broker connection strength (0-4 bars).

### Endpoint

```
GET /api/v1/brokers/accounts/{accountId}/signal
```

**Auth:** Bearer token

### Response

```json
{
  "accountId": "uuid",
  "brokerId": "ZERODHA",
  "brokerName": "Zerodha",
  "signal": 4,
  "maxSignal": 4,
  "quality": "excellent",
  "color": "green",
  "message": "Connection excellent (120ms)",
  "sessionActive": true,
  "latencyMs": 120,
  "marginAvailable": 75000.50
}
```

### Signal Bars

| Bars | Quality | Color | Meaning |
|------|---------|-------|---------|
| 4 | `excellent` | 🟢 green | < 500ms response |
| 3 | `good` | 🟢 green | 500-1500ms response |
| 2 | `fair` | 🟡 yellow | 1500-3000ms response |
| 1 | `poor` / `expired` / `error` | 🔴 red | > 3000ms or API error or session expired |
| 0 | `disconnected` | 🔴 red | No session, login required |

### Frontend Display

```
Signal 4: ████  (all green)
Signal 3: ███░  (green)
Signal 2: ██░░  (yellow)
Signal 1: █░░░  (red)
Signal 0: ░░░░  (red/grey — disconnected)
```

---

## Dashboard Now Includes Signal + Balance Alert

The `/dashboard` endpoint now also returns `signal` and `balanceAlert` automatically:

```json
{
  "accountId": "uuid",
  "brokerId": "ZERODHA",
  "signal": {
    "bars": 4,
    "maxBars": 4,
    "quality": "excellent",
    "color": "green"
  },
  "balanceAlert": {
    "level": "OK",
    "availableMargin": 75000.50
  },
  "profile": { ... },
  "margin": { ... },
  "positions": [ ... ],
  "holdings": [ ... ],
  "orders": [ ... ]
}
```


---

## NEW: Trade Copy Engine

The core copy trading engine. Two modes: manual trigger + auto-polling.

### Manual Copy Trade

Master triggers a trade to be copied to all active children.

```
POST /api/v1/engine/copy-trade
```

**Auth:** Bearer token (Master)

**Request:**
```json
{
  "symbol": "RELIANCE",
  "qty": 10,
  "side": "BUY",
  "product": "MIS",
  "orderType": "MARKET",
  "price": 0
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| symbol | string | yes | Trading symbol (e.g. RELIANCE, TCS) |
| qty | int | yes | Quantity (master's qty, will be scaled per child) |
| side | string | yes | BUY or SELL |
| product | string | no | MIS (default), CNC, NRML |
| orderType | string | no | MARKET (default), LIMIT |
| price | double | no | 0 for MARKET orders |

**Response:**
```json
{
  "message": "Trade copy completed",
  "symbol": "RELIANCE",
  "side": "BUY",
  "masterQty": 10,
  "childrenTotal": 3,
  "success": 2,
  "failed": 1,
  "results": [
    { "childId": "uuid-1", "status": "SUCCESS", "message": "Order placed: 123456", "broker": "ZERODHA", "scaledQty": 10 },
    { "childId": "uuid-2", "status": "SUCCESS", "message": "Order placed: 789012", "broker": "FYERS", "scaledQty": 15 },
    { "childId": "uuid-3", "status": "FAILED", "message": "Insufficient balance (₹200)", "broker": "DHAN", "scaledQty": 10 }
  ]
}
```

### Engine Status

```
GET /api/v1/engine/status
```

### Enable/Disable Auto-Polling

```
POST /api/v1/engine/polling
{ "enabled": true }
```

When enabled, the engine polls master's broker orders every 10 seconds and auto-copies new COMPLETE orders to children.

### Reset Polling Cache

```
POST /api/v1/engine/polling/reset
```

Clears known orders cache. Use at start of trading day.

### How It Works

```
Manual Mode:
  Master clicks "Copy Trade" → POST /engine/copy-trade → Engine places order on each child's broker

Auto-Polling Mode:
  Every 10 sec → Fetch master's orders → Detect new COMPLETE orders → Copy to children

For each child:
  1. Check if broker session is active
  2. Check if balance is sufficient (>₹500)
  3. Scale quantity by child's scalingFactor
  4. Place order on child's broker
  5. Log result to copy_logs
  6. Send notification to child (success or failure)
  7. Send summary notification to master
```
