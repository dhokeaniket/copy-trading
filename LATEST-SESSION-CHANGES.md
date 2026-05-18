# Latest Backend Changes — Frontend Integration Guide

**Date:** May 15, 2026  
**Commit:** `c0ca8a9` — feat: telegram notifications, latency tracking, copyGroupId, timing trail, SL/SL-M orders, improved SELL blocking

---

## Summary of Changes

| # | Feature | What it does |
|---|---------|-------------|
| 1 | **Latency Tracking** | Every copy trade now records how long the broker API call took (`latencyMs`) |
| 2 | **Copy Group ID** | One UUID (`copyGroupId`) ties master's trade + all child copies together |
| 3 | **Full Timing Trail** | `engineReceivedAt`, `childPlacedAt`, `latencyMs` — complete timeline |
| 4 | **Telegram Notifications** | Users get BUY/SELL/SKIPPED/FAILED alerts on Telegram |
| 5 | **SL/SL-M Order Types** | `triggerPrice` field for Stop Loss orders across all brokers |
| 6 | **Smart SELL Blocking** | If child joined AFTER master's BUY, the SELL won't copy to them |
| 7 | **Master Name in Child Logs** | Child can see which master triggered each trade |

---

## 1. Telegram Chat ID — Profile Update

### Set Telegram Chat ID
```
PUT /api/v1/auth/me
Authorization: Bearer <token>
Content-Type: application/json

{
  "telegramChatId": "123456789"
}
```

### Response (GET /api/v1/auth/me)
```json
{
  "userId": "...",
  "name": "Aniket",
  "email": "aniket@example.com",
  "role": "CHILD",
  "status": "ACTIVE",
  "phone": "+91...",
  "telegramChatId": "123456789",
  "twoFactorEnabled": false,
  "createdAt": "..."
}
```

**Frontend Integration:**
- Add a "Link Telegram" input field in Settings/Profile page
- User gets their chat ID from Telegram bot (e.g. @userinfobot)
- Save via `PUT /api/v1/auth/me` with `telegramChatId`
- Show green badge "Telegram Linked" if `telegramChatId` is not null

---

## 2. Copy Trade Response (Master triggers trade)

### POST /api/v1/engine/copy-trade
```json
{
  "symbol": "RELIANCE",
  "qty": 10,
  "side": "BUY",
  "product": "MIS",
  "orderType": "MARKET",
  "price": 0,
  "triggerPrice": 0,
  "exchange": "NSE"
}
```

### New fields in response:
```json
{
  "message": "Trade copy completed",
  "copyGroupId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "symbol": "RELIANCE",
  "exchange": "NSE",
  "segment": "EQUITY",
  "side": "BUY",
  "product": "MIS",
  "orderType": "MARKET",
  "masterQty": 10,
  "childrenTotal": 3,
  "success": 2,
  "failed": 0,
  "skipped": 1,
  "orderKey": "a3f2b1c4d5e6f7a8",
  "masterTriggeredAt": "2026-05-15T09:30:00.100Z",
  "engineReceivedAt": "2026-05-15T09:30:00.100Z",
  "completedAt": "2026-05-15T09:30:00.580Z",
  "totalExecutionMs": 480,
  "results": [
    {
      "childId": "uuid-1",
      "status": "SUCCESS",
      "message": "Order placed: 2505150012345",
      "copyGroupId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "broker": "GROWW",
      "scaledQty": 10,
      "engineReceivedAt": "2026-05-15T09:30:00.100Z",
      "childPlacedAt": "2026-05-15T09:30:00.350Z",
      "placedAt": "2026-05-15T09:30:00.352Z",
      "latencyMs": 250,
      "orderKey": "a3f2b1c4d5e6f7a8"
    },
    {
      "childId": "uuid-2",
      "status": "SKIPPED",
      "message": "Child has no copied BUY position for RELIANCE since subscription. SELL skipped.",
      "copyGroupId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "scaledQty": 10,
      "engineReceivedAt": "2026-05-15T09:30:00.100Z",
      "childPlacedAt": null,
      "placedAt": "2026-05-15T09:30:00.110Z",
      "skipReason": "NO_POSITION"
    }
  ]
}
```

**Frontend Integration:**
- Show `copyGroupId` as a "Trade ID" the user can reference
- Show timing: `masterTriggeredAt` → `completedAt` with `totalExecutionMs`
- Per-child results: show latency badge (green <200ms, yellow <500ms, red >500ms)
- Show `skipped` count with reason tooltip

---

## 3. Child — Copied Trades

### GET /api/v1/child/copied-trades
```json
{
  "trades": [
    {
      "id": 42,
      "copyGroupId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "masterId": "uuid-of-master",
      "masterName": "Rahul Sharma",
      "instrument": "RELIANCE",
      "type": "BUY",
      "masterQty": 10,
      "myQty": 10,
      "status": "SUCCESS",
      "skipReason": null,
      "latencyMs": 250,
      "engineReceivedAt": "2026-05-15T09:30:00.100Z",
      "childPlacedAt": "2026-05-15T09:30:00.350Z",
      "time": "2026-05-15T09:30:00.352Z",
      "entry": 0,
      "current": 0,
      "ltp": 0,
      "pnl": 0
    }
  ]
}
```

**Frontend Integration:**
- Show "Copied from: **Rahul Sharma**" on each trade card
- Show timing trail: Engine received → Child placed → Latency
- Group trades by `copyGroupId` to show "this was one copy event"
- Color-code status: SUCCESS=green, FAILED=red, SKIPPED=yellow

---

## 4. Child — Copy Logs (detailed)

### GET /api/v1/child/copy/logs
```json
{
  "logs": [
    {
      "id": 42,
      "masterId": "uuid-of-master",
      "masterName": "Rahul Sharma",
      "childId": "uuid-of-child",
      "symbol": "RELIANCE",
      "qty": 10,
      "tradeType": "BUY",
      "masterStatus": "EXECUTED",
      "childStatus": "SUCCESS",
      "errorMessage": null,
      "skipReason": null,
      "latencyMs": 250,
      "copyGroupId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "engineReceivedAt": "2026-05-15T09:30:00.100Z",
      "childPlacedAt": "2026-05-15T09:30:00.350Z",
      "createdAt": "2026-05-15T09:30:00.352Z"
    }
  ]
}
```

---

## 5. Master — Copy Logs

### GET /api/v1/master/copy/logs
```json
{
  "logs": [
    {
      "id": 42,
      "masterId": "uuid-of-master",
      "childId": "uuid-of-child",
      "childName": "Priya Patel",
      "symbol": "RELIANCE",
      "qty": 10,
      "tradeType": "BUY",
      "masterStatus": "EXECUTED",
      "childStatus": "SUCCESS",
      "errorMessage": null,
      "skipReason": null,
      "latencyMs": 250,
      "copyGroupId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "engineReceivedAt": "2026-05-15T09:30:00.100Z",
      "childPlacedAt": "2026-05-15T09:30:00.350Z",
      "createdAt": "2026-05-15T09:30:00.352Z"
    }
  ]
}
```

**Frontend Integration:**
- Master sees `childName` for each log entry
- Child sees `masterName` for each log entry
- Both see full timing trail and `copyGroupId`

---

## 6. SL/SL-M Orders — triggerPrice

### POST /api/v1/engine/copy-trade (Stop Loss example)
```json
{
  "symbol": "NIFTY2560519500CE",
  "qty": 50,
  "side": "SELL",
  "product": "MIS",
  "orderType": "SL-M",
  "price": 0,
  "triggerPrice": 150.50,
  "exchange": "NSE"
}
```

**Supported orderType values:**
- `MARKET` — regular market order (triggerPrice ignored)
- `LIMIT` — limit order (uses `price`)
- `SL` — stop loss limit (uses both `price` and `triggerPrice`)
- `SL-M` — stop loss market (uses `triggerPrice` only, price=0)

**Frontend Integration:**
- When user selects SL or SL-M order type, show a "Trigger Price" input
- Pass `triggerPrice` in the copy-trade request body
- For MARKET/LIMIT orders, send `triggerPrice: 0` or omit it

---

## 7. Smart SELL Blocking Logic

**What happens:**
- Child subscribes to Master on May 10
- Master already has open BUY on RELIANCE from May 8
- Master sells RELIANCE on May 15
- **Result:** SELL is SKIPPED for this child (they never had the position via copy)

**Skip reasons returned:**
| skipReason | Meaning |
|-----------|---------|
| `NO_POSITION` | Child has no copied BUY for this symbol since their subscription date |
| `ZERO_QUANTITY` | Scaled quantity rounds to 0 (e.g. 0.1x scaling on 1 qty) |

**Frontend Integration:**
- Show skipped trades with yellow badge
- Tooltip: "SELL skipped — you didn't have a copied BUY position for this instrument"

---

## 8. Timing Trail — How to Display

```
Timeline for a single copy:

Master Triggered ─────────── engineReceivedAt (engine picks it up)
        │                         │
        │    ~0ms (same server)   │
        ▼                         ▼
   Engine Processing ──────── childPlacedAt (order sent to broker)
        │                         │
        │    latencyMs            │
        ▼                         ▼
   Broker Confirmed ──────── createdAt (log saved)
```

**Frontend suggestion:**
```tsx
// Timeline component
<div className="timeline">
  <Step label="Engine Received" time={trade.engineReceivedAt} />
  <Step label="Order Placed" time={trade.childPlacedAt} />
  <Step label="Latency" value={`${trade.latencyMs}ms`} 
        color={trade.latencyMs < 200 ? 'green' : trade.latencyMs < 500 ? 'yellow' : 'red'} />
</div>
```

---

## 9. copyGroupId — Grouping Trades

The `copyGroupId` is a UUID that links all copies from a single master trade event.

**Use cases:**
- "Show me all children who received this trade" → filter by copyGroupId
- "Was this a copied trade or personal?" → if copyGroupId exists, it's copied
- "How many children got this trade?" → count by copyGroupId

**Frontend suggestion:**
- In trade detail view, show "Copy Group: a1b2c3d4..." (truncated)
- Click to expand and see all children in that group
- Use it as a filter in the logs table

---

## 10. Telegram Setup (for users)

1. Create a Telegram bot via @BotFather → get bot token
2. Set env vars on server:
   ```
   TELEGRAM_BOT_TOKEN=your-bot-token
   TELEGRAM_ENABLED=true
   ```
3. Users get their chat ID by messaging the bot or using @userinfobot
4. Users save chat ID via `PUT /api/v1/auth/me` with `telegramChatId`

**Notifications sent:**
- ✅ Trade SUCCESS — "🟢 BUY RELIANCE ×10"
- ❌ Trade FAILED — "🔴 SELL NIFTY ×50 — Order failed: insufficient margin"
- ⏭️ Trade SKIPPED — "⏭️ SELL RELIANCE ×10 — No position"
- Master gets summary: "✅ Copy Complete — BUY RELIANCE ×10 — 2/3 children | ⏱ 480ms"

---

## Files Changed

| File | Change |
|------|--------|
| `db/schema.sql` | Added `latency_ms`, `copy_group_id`, `master_placed_at`, `engine_received_at`, `child_placed_at` to copy_logs; `telegram_chat_id` to users |
| `SchemaInitializer.java` | Auto-migration ALTER TABLE statements |
| `CopyLog.java` | New fields: latencyMs, copyGroupId, masterPlacedAt, engineReceivedAt, childPlacedAt |
| `UserAccount.java` | New field: telegramChatId |
| `TelegramService.java` | **NEW** — Telegram Bot API integration |
| `CopyEngineService.java` | copyGroupId generation, timing trail, SL/SL-M support, improved SELL blocking, Telegram notifications |
| `CopyTradeRequest.java` | Added triggerPrice field |
| `UpdateProfileRequest.java` | Added telegramChatId |
| `UserDto.java` | Exposes telegramChatId in profile response |
| `AuthService.java` | Saves telegramChatId on profile update |
| `ChildService.java` | Enriched copy logs & copied trades with masterName, timing, copyGroupId |
| `MasterService.java` | Enriched copy logs with childName, timing, copyGroupId |
| `application.yml` | Added telegram config section |


---

## 11. Password Validation Endpoint (Real-time strength check)

### POST /api/v1/auth/validate-password (PUBLIC — no auth needed)
```json
// Request
{"password": "Test@1234"}

// Response
{
  "valid": true,
  "strength": "STRONG",
  "score": 5,
  "checks": {
    "minLength": true,
    "hasNumber": true,
    "hasSpecialChar": true,
    "hasUppercase": true,
    "hasLowercase": true
  }
}
```

**Strength levels:** WEAK (score 0-2), MEDIUM (score 3-4), STRONG (score 5-6)

**Frontend Integration:**
- Call this on every keystroke (debounced 300ms) in the password field
- Show real-time strength meter with color: red=WEAK, yellow=MEDIUM, green=STRONG
- Show checkmarks next to each requirement as they're met
- Disable "Create Account" button until `valid: true`

---

## 12. Risk Rules API

### GET /api/v1/risk/rules (auth required)
```json
{
  "userId": "uuid",
  "maxTradesPerDay": 50,
  "maxOpenPositions": 20,
  "maxCapitalExposure": 80,
  "marginCheckEnabled": true,
  "updatedAt": "2026-05-18T..."
}
```

### PUT /api/v1/risk/rules (auth required)
```json
// Request
{
  "maxTradesPerDay": 30,
  "maxOpenPositions": 10,
  "maxCapitalExposure": 70,
  "marginCheckEnabled": true
}

// Response
{
  "message": "Risk rules updated",
  "maxTradesPerDay": 30,
  "maxOpenPositions": 10,
  "maxCapitalExposure": 70,
  "marginCheckEnabled": true
}
```

### GET /api/v1/risk/check (auth required)
```json
// If allowed
{"allowed": true, "reason": null}

// If blocked
{"allowed": false, "reason": "MAX_TRADES_PER_DAY: Limit 30 reached (30 today)"}
```

**Frontend Integration:**
- Add "Risk Settings" section in child's Settings page
- Show sliders/inputs for max trades, max positions, max exposure %
- Show current risk status badge (green=OK, red=limit reached)
- When copy trade is skipped due to risk, show reason in trade history

---

## 13. Duplicate Signal Detection

The backend now rejects duplicate copy-trade signals within 60 seconds. If the same master sends the same symbol+qty+side within 1 minute, the second request returns:

```json
{
  "message": "Duplicate signal detected — same trade already processed within 60 seconds",
  "duplicate": true,
  "orderKey": "a3f2b1c4d5e6f7a8"
}
```

**Frontend Integration:**
- If response contains `"duplicate": true`, show info toast: "Trade already processed"
- Don't show it as an error — it's a safety feature

---

## 14. Secure Error Messages (Breaking Change)

Error messages no longer leak information:

| Old (insecure) | New (secure) |
|---|---|
| "Email already registered" | "Unable to create account. Please try a different email or login to your existing account." |
| "Phone not registered" | "Invalid credentials. Please check your phone number or register." |
| Send OTP for unknown phone → error | Send OTP for unknown phone → success response (but no SMS sent) |

**Frontend Integration:**
- Update error handling — don't rely on specific error text for logic
- Use HTTP status codes: 401=bad credentials, 409=account conflict, 400=validation error
- Show generic recovery actions: "Forgot Password?" / "Create Account" links

---

## 15. Registration Password Requirements (Updated)

Password now requires **all three**:
- Minimum 8 characters
- At least 1 number
- At least 1 special character (`!@#$%^&*()_+-=[]{}|;:'"<>,.?/`)

Old requirement was only 8 chars + 1 number. Update your frontend validation to match.
