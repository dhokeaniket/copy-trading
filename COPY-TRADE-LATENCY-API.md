# Copy Trade API — Latency & Timing Reference

## Base URL
```
https://api.ascentracapital.com (or http://13.53.246.13:8081)
```

All endpoints require `Authorization: Bearer <accessToken>` header.

---

## 1. Copy Trade (Manual Trigger)

**POST** `/api/v1/engine/copy-trade`

Master triggers a trade copy to all active children.

### Request
```json
{
  "symbol": "NIFTY2651225000CE",
  "qty": 65,
  "side": "BUY",
  "product": "NRML",
  "orderType": "MARKET",
  "exchange": "NSE",
  "price": 0
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| symbol | string | yes | Trading symbol (Groww/Zerodha format) |
| qty | int | yes | Quantity (lot size for F&O) |
| side | string | yes | BUY or SELL |
| product | string | yes | CNC (delivery), MIS (intraday), NRML (F&O carry) |
| orderType | string | yes | MARKET or LIMIT |
| exchange | string | no | NSE (default) or BSE |
| price | double | no | Required for LIMIT orders |

### Response (Enhanced with Timing)
```json
{
  "message": "Trade copy completed",
  "symbol": "NIFTY2651225000CE",
  "exchange": "NSE",
  "segment": "FNO",
  "side": "BUY",
  "product": "NRML",
  "orderType": "MARKET",
  "masterQty": 65,
  "childrenTotal": 5,
  "success": 3,
  "failed": 2,
  "masterTriggeredAt": "2026-05-13T09:15:01.234Z",
  "completedAt": "2026-05-13T09:15:01.678Z",
  "totalExecutionMs": 444,
  "results": [
    {
      "childId": "ff59c179-ff49-4be8-bc23-a00119e0d3a4",
      "status": "SUCCESS",
      "message": "Order placed: 321260513123456",
      "broker": "DHAN",
      "scaledQty": 65,
      "placedAt": "2026-05-13T09:15:01.567Z",
      "latencyMs": 245
    },
    {
      "childId": "abc12345-...",
      "status": "SUCCESS",
      "message": "Order placed: 260513170566073",
      "broker": "ZERODHA",
      "scaledQty": 65,
      "placedAt": "2026-05-13T09:15:01.612Z",
      "latencyMs": 312
    },
    {
      "childId": "def67890-...",
      "status": "FAILED",
      "message": "Broker session inactive. Child needs to re-login.",
      "broker": "GROWW",
      "scaledQty": 65,
      "placedAt": "2026-05-13T09:15:01.678Z",
      "latencyMs": 0
    }
  ]
}
```

### Response Fields

#### Top-Level
| Field | Type | Description |
|-------|------|-------------|
| message | string | "Trade copy completed" |
| symbol | string | Original symbol from master |
| exchange | string | NSE or BSE |
| segment | string | "EQUITY" or "FNO" |
| side | string | BUY or SELL |
| product | string | CNC/MIS/NRML |
| orderType | string | MARKET/LIMIT |
| masterQty | int | Master's original quantity |
| childrenTotal | int | Total active children |
| success | int | How many children succeeded |
| failed | int | How many children failed |
| masterTriggeredAt | ISO string | When master triggered the copy |
| completedAt | ISO string | When all children finished |
| totalExecutionMs | long | Total time from trigger to completion (ms) |
| results | array | Per-child results |

#### Per-Child Result
| Field | Type | Description |
|-------|------|-------------|
| childId | UUID | Child user ID |
| status | string | SUCCESS, FAILED, or SKIPPED |
| message | string | Order ID on success, error message on failure |
| broker | string | DHAN, ZERODHA, UPSTOX, FYERS, GROWW, ANGELONE |
| scaledQty | int | Quantity after scaling factor applied |
| placedAt | ISO string | When this child's order was placed/attempted |
| latencyMs | long | Broker API response time in milliseconds |

---

## 2. Engine Status

**GET** `/api/v1/engine/status`

```json
{
  "engineStatus": "ACTIVE",
  "pollingEnabled": true,
  "pollingIntervalSeconds": 1,
  "supportedBrokers": ["GROWW", "ZERODHA", "FYERS", "UPSTOX", "DHAN", "ANGELONE"],
  "modes": ["manual", "polling", "postback", "websocket"],
  "detectionMethod": {
    "ZERODHA": "postback (~100ms)",
    "FYERS": "websocket (~50ms)",
    "UPSTOX": "websocket (~50ms)",
    "DHAN": "polling (1s)",
    "GROWW": "polling (1s)",
    "ANGELONE": "polling (1s)"
  }
}
```

---

## 3. Child's Copied Trades

**GET** `/api/v1/child/copied-trades`

```json
{
  "trades": [
    {
      "id": 880,
      "master": "master account test",
      "instrument": "NIFTY2651225000CE",
      "type": "BUY",
      "masterQty": 65,
      "myQty": 65,
      "entry": 0,
      "current": 0,
      "ltp": 0,
      "pnl": 0,
      "time": "2026-05-11T04:37:09.201247Z",
      "status": "SUCCESS"
    }
  ]
}
```

---

## 4. Broker Login (with clientId for Dhan)

**POST** `/api/v1/brokers/accounts/{accountId}/login`

### Dhan Login
```json
{
  "authCode": "tokenId_from_redirect",
  "clientId": "1110569575"
}
```
The `clientId` field is now saved automatically during login.

### Angel One Login
```json
{
  "totpCode": "738596"
}
```
Requires `clientId` and `apiSecret` (password) set on account first.

### Zerodha Login
```json
{
  "requestToken": "abc123..."
}
```

### Fyers Login
```json
{
  "authCode": "auth_code_from_redirect"
}
```

---

## 5. Switch Broker for Copy Trading

**PUT** `/api/v1/child/subscriptions/broker`

```json
{
  "masterId": "3cc742bd-6c9d-405a-95e8-49691e4f26d2",
  "brokerAccountId": "440534f6-ec9a-4405-ae44-4f12f33912f8"
}
```

Response:
```json
{
  "message": "Broker account switched",
  "brokerAccountId": "440534f6-...",
  "brokerId": "DHAN",
  "brokerName": "DHAN"
}
```

---

## 6. Polling Control

### Enable/Disable
**POST** `/api/v1/engine/polling`
```json
{ "enabled": true }
```

### Reset Known Orders (start of day)
**POST** `/api/v1/engine/polling/reset`

### Polling Status
**GET** `/api/v1/engine/polling/status`
```json
{
  "lastResetAt": "2026-05-13T03:45:00Z",
  "autoResetEnabled": true,
  "pollingEnabled": true
}
```

---

## Frontend Dashboard Suggestions

### Latency Card
Show per-broker average latency:
```
┌─────────────────────────────────────┐
│  Copy Latency (avg)                 │
│  ─────────────────                  │
│  Dhan:     245ms  ████████░░  ✓     │
│  Zerodha:  312ms  ██████████░ ✓     │
│  Upstox:   180ms  ██████░░░░  ✓     │
│  Fyers:    ---    blocked           │
│  Groww:    ---    IP issue           │
│  ─────────────────                  │
│  Total:    444ms (5 children)       │
└─────────────────────────────────────┘
```

### Trade Timeline
```
09:15:01.234  Master triggered BUY NIFTY 25000CE ×65
09:15:01.479  → Dhan: SUCCESS (245ms)
09:15:01.546  → Zerodha: SUCCESS (312ms)
09:15:01.567  → Upstox: SUCCESS (180ms)
09:15:01.600  → Groww: FAILED - session inactive
09:15:01.678  ✓ Complete (444ms total)
```

### Segment Badges
- `EQUITY` — stocks (IOB, SBIN, RELIANCE)
- `FNO` — options/futures (NIFTY2651225000CE)

---

## Symbol Format by Broker

| Broker | Equity | F&O Weekly | F&O Monthly |
|--------|--------|------------|-------------|
| Zerodha | IOB | NIFTY2651225000CE | NIFTY26MAY25000CE |
| Groww | IOB | NIFTY2651225000CE | NIFTY2651225000CE |
| Dhan | IOB (securityId: 9348) | NIFTY-May2026-25000-CE (securityId: 41832) | same |
| Fyers | NSE:IOB-EQ | NSE:NIFTY2651225000CE | NSE:NIFTY26MAY25000CE |
| Upstox | NSE_EQ\|IOB | NSE_FO\|NIFTY2651225000CE | NSE_FO\|NIFTY26MAY25000CE |
| AngelOne | IOB-EQ | NIFTY2651225000CE | NIFTY26MAY25000CE |

The backend handles all symbol translation automatically.


---

## Changes to Existing APIs (May 13, 2026)

### `POST /api/v1/engine/copy-trade` — Response Enhanced (Non-Breaking)

**New fields added to top-level response:**

| Field | Type | Before | After |
|-------|------|--------|-------|
| `exchange` | string | ❌ absent | ✅ "NSE" or "BSE" |
| `segment` | string | ❌ absent | ✅ "EQUITY" or "FNO" |
| `product` | string | ❌ absent | ✅ "CNC"/"MIS"/"NRML" |
| `orderType` | string | ❌ absent | ✅ "MARKET"/"LIMIT" |
| `masterTriggeredAt` | ISO string | ❌ absent | ✅ when master triggered |
| `completedAt` | ISO string | ❌ absent | ✅ when all children done |
| `totalExecutionMs` | long | ❌ absent | ✅ total time in ms |

**New fields added to each `results[]` item:**

| Field | Type | Before | After |
|-------|------|--------|-------|
| `placedAt` | ISO string | ❌ absent | ✅ when this child's order was placed |
| `latencyMs` | long | ❌ absent | ✅ broker API response time (ms) |

**Existing fields unchanged:**
- `message`, `symbol`, `side`, `masterQty`, `childrenTotal`, `success`, `failed`
- `results[].childId`, `results[].status`, `results[].message`, `results[].broker`, `results[].scaledQty`

All changes are **additive** — no breaking changes for existing frontend code.

---

### `POST /api/v1/brokers/accounts/{id}/login` — Dhan Login Enhanced

**New optional field in request body:**

```json
{
  "authCode": "tokenId_from_redirect",
  "clientId": "1110569575"          ← NEW (optional)
}
```

If `clientId` is provided during Dhan login, it's saved to the account automatically. This prevents the "dhanClientId is required" error on order placement.

---

### Before vs After Example

**Before (old response):**
```json
{
  "message": "Trade copy completed",
  "symbol": "IOB",
  "side": "BUY",
  "masterQty": 1,
  "childrenTotal": 5,
  "success": 1,
  "failed": 4,
  "results": [
    {
      "childId": "ff59c179-...",
      "status": "SUCCESS",
      "message": "Order placed: 321260511222108",
      "broker": "DHAN",
      "scaledQty": 1
    }
  ]
}
```

**After (new response):**
```json
{
  "message": "Trade copy completed",
  "symbol": "IOB",
  "exchange": "NSE",
  "segment": "EQUITY",
  "side": "BUY",
  "product": "CNC",
  "orderType": "MARKET",
  "masterQty": 1,
  "childrenTotal": 5,
  "success": 1,
  "failed": 4,
  "masterTriggeredAt": "2026-05-13T09:15:01.234Z",
  "completedAt": "2026-05-13T09:15:01.567Z",
  "totalExecutionMs": 333,
  "results": [
    {
      "childId": "ff59c179-...",
      "status": "SUCCESS",
      "message": "Order placed: 321260511222108",
      "broker": "DHAN",
      "scaledQty": 1,
      "placedAt": "2026-05-13T09:15:01.567Z",
      "latencyMs": 245
    }
  ]
}
```
