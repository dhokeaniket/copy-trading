# Latest Backend Changes — For Frontend Dev

## 1. Error Messages Now User-Friendly

All broker error responses changed from raw errors to clean messages.

**Before:**
```json
{ "error": "GROWW: 401 Unauthorized from GET https://api.groww.in/v1/margins/detail/user" }
```

**After:**
```json
{
  "error": "Session expired. Please re-login to Groww to continue.",
  "errorCode": "SESSION_EXPIRED",
  "action": "RE_LOGIN"
}
```

Frontend should check for `errorCode: "SESSION_EXPIRED"` and show a "Re-connect" button.

---

## 2. `/child/masters` — New Fields Added

```json
{
  "masterId": "uuid",
  "name": "Master Trader",
  "winRate": 0,
  "totalTrades": 0,
  "avgPnl": 0,
  "subscribers": 4,
  "return30d": 0,
  "returnYTD": 0,
  "riskLevel": "Medium",
  "bestTrade": "₹0",
  "worstTrade": "₹0",
  "verified": true,
  "description": "Master Trader — Master trader on Ascentra",
  "markets": ["Equity", "F&O"],
  "equityCurve": [100, 100, 100, 100, 100, 100]
}
```

---

## 3. `/master/earnings` — Updated Shape

```json
{
  "totalEarnings": 0,
  "thisMonth": 0,
  "lastMonth": 0,
  "pendingPayout": 0,
  "currency": "INR",
  "monthlyBreakdown": [
    { "month": "2025-01", "subscribers": 4, "subscriptionFee": 0, "performanceBonus": 0, "total": 0 }
  ],
  "payouts": []
}
```

---

## 4. `POST /child/subscriptions` — brokerAccountId Now REQUIRED

**Before:** `brokerAccountId` was optional.
**Now:** Returns 400 if missing, 403 if account doesn't belong to the child.

```json
// Error if missing:
{ "status": 400, "message": "brokerAccountId is required. Please link a broker account before subscribing." }

// Error if wrong owner:
{ "status": 403, "message": "This broker account does not belong to you" }
```

---

## 5. Session Expiry — WebSocket Alert

When a copy trade fails due to expired broker session, the backend now:
- Pushes `SESSION_EXPIRED` event to `/ws/notifications`
- Saves a notification to the child's notification list

WebSocket event:
```json
{ "event": "SESSION_EXPIRED", "data": { "childId": "uuid", "broker": "DHAN", "accountId": "uuid" } }
```

---

## 6. Copy Logs — New SKIPPED Status

Copy logs now have 4 possible statuses: `EXECUTED`, `FAILED`, `SKIPPED`, `PENDING`

New field `skipReason` added:
```json
{
  "childStatus": "SKIPPED",
  "skipReason": "INSUFFICIENT_MARGIN",
  "errorMessage": "Required Rs.5000, Available Rs.2000"
}
```

---

## 7. Engine Status — Updated Response

`GET /api/v1/engine/status` now shows detection methods per broker:

```json
{
  "engineStatus": "ACTIVE",
  "pollingEnabled": false,
  "pollingIntervalSeconds": 1,
  "modes": ["manual", "polling", "postback", "websocket"],
  "detectionMethod": {
    "ZERODHA": "postback (~100ms)",
    "FYERS": "websocket (~50ms)",
    "UPSTOX": "websocket (~50ms)",
    "DHAN": "polling (1s)",
    "GROWW": "polling (1s)"
  }
}
```

---

## 8. New Endpoint — Polling Status

```
GET /api/v1/engine/polling/status
```

```json
{
  "lastResetAt": "2026-04-19T03:45:00Z",
  "autoResetEnabled": true,
  "pollingEnabled": false
}
```

---

## 9. Auto Scheduled Jobs (No Frontend Action Needed)

- **9:00 AM IST (weekdays):** Sends "Broker login required" notification to children with expired sessions
- **9:15 AM IST (weekdays):** Auto-resets polling cache for fresh market day

---

## Summary — What Frontend Needs to Handle

| Change | Frontend Action |
|--------|----------------|
| `errorCode: "SESSION_EXPIRED"` | Show "Re-connect Broker" button |
| New fields in `/child/masters` | Use `return30d`, `riskLevel`, `verified`, `equityCurve` etc. |
| New fields in `/master/earnings` | Use `subscribers`, `subscriptionFee`, `performanceBonus`, `payouts` |
| `brokerAccountId` required in subscribe | Show broker account selector before subscribe |
| `SKIPPED` status in copy logs | Show as yellow/warning (different from red FAILED) |
| Engine detection methods | Can show per-broker speed indicator |
