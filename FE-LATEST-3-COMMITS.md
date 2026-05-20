# Frontend Integration — Last 3 Commits

**Branch:** `feature/backend_code`  
**Date:** May 19, 2026  
**Base URL:** `https://api.ascentracapital.com` (or your EC2 host + port `8081`)

This document covers **only** the last 3 commits on this branch. For older features (Telegram, copyGroupId, risk rules, etc.) see `LATEST-SESSION-CHANGES.md`.

---

## Commits Summary

| # | Commit | Type | What it does |
|---|--------|------|----------------|
| 1 | `4f0b628` | feat | **Real SMS OTP** via AWS SNS — users receive OTP on phone during login |
| 2 | `3bb17cb` | feat | **Live positions API** — master/child dashboards get real open positions + P&L from broker |
| 3 | `efa0afc` | fix | **Orphan SELL prevention** — child linked after master BUY will not receive a naked SELL copy |

---

## Commit 1 — AWS SNS OTP SMS (`4f0b628`)

### Backend behavior

- On `POST /api/v1/auth/send-otp`, if the phone is **registered**, backend generates a 6-digit OTP and sends SMS via **AWS SNS** (Transactional).
- SMS text: `Your Ascentra verification code is: XXXXXX. Valid for 5 minutes. Do not share this code.`
- OTP stored in Redis (if available) or in-memory; expires in **5 minutes** (`expiresIn: 300`).
- Resend cooldown: **60 seconds** (`retryAfter: 60`).
- If AWS credentials are **not** configured on server, OTP is **logged only** (dev mode) — SMS will not arrive.
- If phone is **not registered**, API still returns `success: true` (security — no user enumeration) but **no SMS is sent**.

### Server env vars (DevOps — not FE)

```bash
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
AWS_REGION=ap-south-1          # default
AWS_SNS_SMS_TYPE=Transactional # default
```

Phone must be **E.164** format: `+919876543210`.

### APIs (unchanged paths — behavior updated)

#### POST `/api/v1/auth/send-otp` — No auth

**Request:**
```json
{ "phone": "+919876543210" }
```

**Success (registered phone):**
```json
{
  "success": true,
  "message": "OTP sent successfully",
  "data": {
    "expiresIn": 300,
    "retryAfter": 60
  }
}
```

**Success (unregistered phone — looks the same to user):**
```json
{
  "success": true,
  "message": "If this phone is registered, an OTP has been sent.",
  "data": {
    "expiresIn": 300,
    "retryAfter": 60
  }
}
```

**Rate limited:**
```json
{
  "success": false,
  "error": "RATE_LIMITED",
  "message": "Please wait before requesting another OTP",
  "data": { "retryAfter": 45 }
}
```

#### POST `/api/v1/auth/verify-otp` — No auth

**Request:**
```json
{ "phone": "+919876543210", "otp": "123456" }
```

**Success:** Same shape as email login — `accessToken`, `refreshToken`, `user`.

**Failure:**
```json
{ "success": false, "error": "INVALID_OTP", "message": "Invalid OTP code" }
```
Other errors: `OTP_EXPIRED`, `TOO_MANY_ATTEMPTS`

### Frontend tasks

| Task | Details |
|------|---------|
| Phone input | Force `+91` prefix or full E.164; validate before send |
| Send OTP button | Disable for `retryAfter` seconds after send; show countdown |
| OTP input | 6 digits; auto-submit on 6th digit optional |
| Timer | Show "Expires in 5:00" using `expiresIn` |
| Errors | Map `error` field — do not parse `message` for branching |
| Dev note | If SMS not received, check server has AWS keys; OTP may be in server logs only |

### Example flow (React)

```tsx
// 1. Send OTP
const res = await fetch(`${API}/api/v1/auth/send-otp`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ phone: '+91' + phoneDigits }),
});
const data = await res.json();
if (!data.success && data.error === 'RATE_LIMITED') {
  startResendCooldown(data.data.retryAfter);
  return;
}
if (data.success) {
  startOtpExpiry(data.data.expiresIn);
  startResendCooldown(data.data.retryAfter);
}

// 2. Verify OTP
const verify = await fetch(`${API}/api/v1/auth/verify-otp`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ phone: '+91' + phoneDigits, otp }),
});
const v = await verify.json();
if (v.success && v.data?.accessToken) {
  saveTokens(v.data.accessToken, v.data.refreshToken);
  navigate('/dashboard');
} else {
  showError(v.error); // INVALID_OTP | OTP_EXPIRED | TOO_MANY_ATTEMPTS
}
```

---

## Commit 2 — Live Positions API (`3bb17cb`)

### Backend behavior

- New service normalizes positions from **all 5 brokers** (Groww, Zerodha, Fyers, Upstox, Dhan, AngelOne) into one shape.
- Uses the user's **first active broker account** (session active + access token).
- P&L formula: `(ltp - avgPrice) * qty`
- Only **open** positions (`qty != 0`) are returned.
- `GET /api/v1/child/copied-trades` now enriches each trade with **live** `entry`, `current`, `ltp`, `pnl` when symbol matches an open position.

### New endpoints

#### GET `/api/v1/master/positions` — Master auth

#### GET `/api/v1/child/positions` — Child auth

**Headers:** `Authorization: Bearer <accessToken>`

**Success response:**
```json
{
  "positions": [
    {
      "symbol": "RELIANCE",
      "qty": 10,
      "avgPrice": 2450.5,
      "ltp": 2475.0,
      "pnl": 245.0,
      "side": "BUY",
      "exchange": "NSE",
      "product": "CNC"
    }
  ],
  "totalPnl": 245.0,
  "count": 1,
  "brokerAccountId": "uuid-of-account",
  "brokerId": "GROWW"
}
```

**No broker connected:**
```json
{
  "positions": [],
  "totalPnl": 0,
  "count": 0,
  "error": "No active broker session found. Please login to your broker.",
  "errorCode": "NO_ACTIVE_SESSION",
  "action": "LOGIN_BROKER"
}
```

**Session expired / broker API failed:**
```json
{
  "positions": [],
  "totalPnl": 0,
  "count": 0,
  "error": "Failed to fetch positions: ...",
  "errorCode": "SESSION_EXPIRED",
  "action": "RE_LOGIN"
}
```

### Updated endpoint — Copied trades with live P&L

#### GET `/api/v1/child/copied-trades` — Child auth

**Response (enriched fields):**
```json
{
  "trades": [
    {
      "id": 42,
      "copyGroupId": "uuid",
      "masterId": "uuid",
      "masterName": "Rahul Sharma",
      "instrument": "RELIANCE",
      "type": "BUY",
      "masterQty": 10,
      "myQty": 10,
      "status": "SUCCESS",
      "skipReason": null,
      "latencyMs": 250,
      "engineReceivedAt": "2026-05-18T09:30:00.100Z",
      "childPlacedAt": "2026-05-18T09:30:00.350Z",
      "time": "2026-05-18T09:30:00.352Z",
      "entry": 2450.5,
      "current": 2475.0,
      "ltp": 2475.0,
      "pnl": 245.0
    }
  ]
}
```

If symbol is not in open positions: `entry`, `current`, `ltp`, `pnl` are all `0`.

### Frontend tasks

| Screen | API | UI |
|--------|-----|-----|
| Master dashboard | `GET /master/positions` | Positions table + header `totalPnl` (green/red) |
| Child dashboard | `GET /child/positions` | Same |
| Child copied trades | `GET /child/copied-trades` | Show `pnl`, `ltp`, `entry` per row; refresh every 10–30s |
| No broker | `errorCode: NO_ACTIVE_SESSION` | CTA: "Connect Broker" → broker link flow |
| Expired session | `errorCode: SESSION_EXPIRED` | CTA: "Re-login to broker" |

### Example — Positions card

```tsx
const { data } = await api.get('/api/v1/child/positions');

if (data.errorCode === 'NO_ACTIVE_SESSION') {
  return <ConnectBrokerBanner action={data.action} />;
}
if (data.errorCode === 'SESSION_EXPIRED') {
  return <ReLoginBrokerBanner message={data.error} />;
}

return (
  <>
    <PnlSummary value={data.totalPnl} label={`${data.count} open positions`} />
    <PositionsTable rows={data.positions} />
  </>
);
```

### Polling suggestion

- Positions: refresh every **15–30 seconds** on dashboard (not on every page).
- Copied trades: refresh every **30 seconds** or pull-to-refresh on mobile.

---

## Commit 3 — Orphan SELL Fix (`efa0afc`)

### Backend behavior

**Problem fixed:** Master buys RELIANCE → Child subscribes later → Master sells RELIANCE. Previously the child might get a **SELL copy without ever having the BUY** (naked short risk).

**New rule:** For SELL copies, child must have a **SUCCESS** BUY copy log for the same symbol where:
- `BUY.createdAt` is **on or after** child's `subscribedAt` timestamp.

If not → copy is **SKIPPED** with `skipReason: "NO_POSITION"`.

### API impact

#### POST `/api/v1/engine/copy-trade` (Master)

Per-child result when skipped:
```json
{
  "childId": "uuid",
  "status": "SKIPPED",
  "message": "Child has no copied BUY position for RELIANCE since subscription. SELL skipped to prevent orphan sell.",
  "skipReason": "NO_POSITION",
  "scaledQty": 10,
  "copyGroupId": "uuid",
  "engineReceivedAt": "...",
  "childPlacedAt": null
}
```

#### GET `/api/v1/child/copied-trades` / copy logs

Skipped SELLs appear with `status: "SKIPPED"` and `skipReason: "NO_POSITION"`.

### Frontend tasks

| Task | Details |
|------|---------|
| Skip badge | Yellow/warning — not an error |
| Tooltip | "SELL skipped — you joined after this position was opened. No copied BUY exists." |
| Master copy results | Show `skipped` count; expand to see `NO_POSITION` children |
| Do not retry | User cannot "force" the SELL — backend will skip again |

### UI copy suggestions

- **Child:** "Skipped — no copied position"  
- **Master:** "1 child skipped (joined after BUY)"

---

## Quick Reference — New/Changed Endpoints

| Method | Endpoint | Auth | Change |
|--------|----------|------|--------|
| POST | `/api/v1/auth/send-otp` | No | Now sends real SMS (if AWS configured) |
| POST | `/api/v1/auth/verify-otp` | No | Unchanged contract |
| GET | `/api/v1/master/positions` | Master | **NEW** — live positions + totalPnl |
| GET | `/api/v1/child/positions` | Child | **NEW** — live positions + totalPnl |
| GET | `/api/v1/child/copied-trades` | Child | **UPDATED** — `entry`, `current`, `ltp`, `pnl` from live positions |
| POST | `/api/v1/engine/copy-trade` | Master | **UPDATED** — stricter SELL skip message + logic |

---

## Implementation Checklist for FE

### Phone login (Commit 1)
- [ ] E.164 phone validation (`+91...`)
- [ ] Send OTP with resend cooldown (`retryAfter`)
- [ ] OTP expiry countdown (`expiresIn`)
- [ ] Verify OTP → store tokens → redirect by role
- [ ] Handle `RATE_LIMITED`, `INVALID_OTP`, `OTP_EXPIRED`, `TOO_MANY_ATTEMPTS`

### Positions & P&L (Commit 2)
- [ ] Master: positions section on dashboard
- [ ] Child: positions section on dashboard
- [ ] Show `totalPnl` with color (positive/negative)
- [ ] Handle `NO_ACTIVE_SESSION` → broker connect
- [ ] Handle `SESSION_EXPIRED` → broker re-login
- [ ] Copied trades: display `pnl`, `entry`, `ltp`
- [ ] Optional: auto-refresh positions

### Copy engine UX (Commit 3)
- [ ] Show SKIPPED + `NO_POSITION` as info, not error
- [ ] Master trade result: show skipped count with reason
- [ ] Child trade history: explain orphan SELL skip in tooltip

---

## Files Changed (Backend)

| File | Commit | Change |
|------|--------|--------|
| `OtpService.java` | 4f0b628 | AWS SNS SMS send |
| `application.yml` | 4f0b628 | `aws.sns.smsType` config |
| `PositionsService.java` | 3bb17cb | **NEW** — broker position fetch + normalize |
| `PositionDto.java` | 3bb17cb | **NEW** — unified position shape |
| `MasterController.java` | 3bb17cb | `GET /positions` |
| `ChildController.java` | 3bb17cb | `GET /positions` |
| `ChildService.java` | 3bb17cb | Live P&L enrichment on copied-trades |
| `CopyEngineService.java` | efa0afc | Stricter SELL-after-subscribe check |

---

## Testing on FE

1. **OTP:** Register user with phone → Send OTP → receive SMS → Verify → login.
2. **Positions:** Connect broker → `GET /child/positions` → see open positions and `totalPnl`.
3. **Copied trades P&L:** Place/have open position → `GET /child/copied-trades` → non-zero `pnl` for matching symbol.
4. **Orphan SELL:** Master BUY before child subscribes → Master SELL → child sees SKIPPED with `NO_POSITION`.

---

## Related docs

- Full API reference: `FRONTEND-API-DOCS.md`
- Broker connect flow: `BROKER-CONNECTION-FLOW.md`
- Older session features: `LATEST-SESSION-CHANGES.md`
