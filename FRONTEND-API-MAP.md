# Frontend API quick map (May 2026)

> **Full request/response bodies:** see **[FRONTEND-INTEGRATION-DETAILED.md](./FRONTEND-INTEGRATION-DETAILED.md)** — use this instead of Swagger for JSON examples (Swagger shows `additionalProp` for Map types).

**Backend base URL (prod):**

- `https://api.ascentracapital.com` (preferred)
- `http://13.53.246.13:8081` (direct EC2)

**Auth header:** `Authorization: Bearer <accessToken>`

**Swagger:** `{baseUrl}/swagger-ui.html`

---

## 1. What changed (FE action items)

| Issue (UI) | Root cause | FE fix |
|------------|------------|--------|
| Master **Trade Logs** empty | FE called API backed by empty `trade_logs` table | Use `GET /api/v1/master/trade-logs` or `/trade-history` (now **copy_logs**) |
| **Latency / History** detail missing failures | Detail API lacked skip/fail fields | Use `GET /api/v1/engine/trade-history/{eventId}` — read `children`, `failures`, `childrenSkipped` |
| **Overview / P&L** partial zeros | Wrong endpoint or only margin wired | Master: `/master/dashboard`, `/master/pnl-analytics`; Child: `/child/pnl-dashboard` |
| **Open book / Open options** empty | No FE route wired | `GET /master/open-book`, `/master/open-options` (same for `/child/...`) |
| **Option status** no skip/error | Field names missing | `GET /master/option-status` or `/child/option-status` — use `errorMessage`, `skipReason`, `failureReason` |
| **Remove child** fails | Wrong HTTP path | `DELETE /api/v1/master/children/{childId}` or `.../unlink` |
| **Remove master** (child) fails | Wrong path | `DELETE /api/v1/child/subscriptions/{masterId}` or `/child/remove/{masterId}` |
| **Find masters** empty | `GET /child/masters` was public, no stats | **Must send JWT** — response now has `winRate`, `totalTrades`, `mySubscriptionStatus` |
| **Child timeline** no fail info | Timeline omitted errors | `GET /child/trade-timeline` — map `errorMessage`, `failureReason`, `skipReason` |
| **Profile** only margin | FE not reading full `brokerAccounts` | `GET /users/me` — each account has `positions`, `marginAvailable`, `sessionActive`, `clientId`, etc. |
| **2FA QR** broken | FE expected `qrCode` | `POST /auth/2fa/enable` returns **`qrCodeUri` and `qrCode`** (same string) |
| **P&L analytics** zeros | Used `trade_logs` | Use `/pnl/unrealized`, `/pnl/summary`, role-specific analytics endpoints |
| **Admin login** | No admin in DB | Use `admin@gmail.com` / `admin@123` (seeded in RDS) |

---

## 2. Endpoints by screen

### Auth / OTP / 2FA

| UI | Method | Path |
|----|--------|------|
| Login | POST | `/api/v1/auth/login` |
| Register | POST | `/api/v1/auth/register` — `role`: `MASTER` \| `CHILD` \| `ADMIN` |
| Refresh | POST | `/api/v1/auth/refresh-token` |
| Send OTP | POST | `/api/v1/auth/send-otp` |
| Verify OTP | POST | `/api/v1/auth/verify-otp` |
| Enable 2FA | POST | `/api/v1/auth/2fa/enable` → `{ qrCodeUri, qrCode, secret }` |
| Confirm 2FA | POST | `/api/v1/auth/2fa/confirm` |

### Admin

| UI | Method | Path |
|----|--------|------|
| List users | GET | `/api/v1/admin/users` |
| Create master | POST | `/api/v1/admin/users/master` |
| Create child | POST | `/api/v1/admin/users/child` |
| Analytics | GET | `/api/v1/admin/analytics` |
| System health | GET | `/api/v1/admin/system-health` |

### Master

| UI tab | Method | Path |
|--------|--------|------|
| Dashboard / overview | GET | `/api/v1/master/dashboard` |
| Copy trading page (all-in-one) | GET | `/api/v1/master/copy-trading` |
| P&L analytics | GET | `/api/v1/master/pnl-analytics` |
| Trade logs | GET | `/api/v1/master/trade-logs` or `/trade-history` |
| Copy logs | GET | `/api/v1/master/copy/logs` |
| Engine history (list) | GET | `/api/v1/engine/trade-history?page=0&size=20` |
| Engine history (detail) | GET | `/api/v1/engine/trade-history/{eventId}` |
| Latency stats | GET | `/api/v1/engine/latency-stats?days=7` |
| Positions | GET | `/api/v1/master/positions` |
| Open book | GET | `/api/v1/master/open-book` |
| Open options | GET | `/api/v1/master/open-options` |
| Option status | GET | `/api/v1/master/option-status` |
| Square off | POST | `/api/v1/master/positions/square-off` |
| Children list | GET | `/api/v1/master/children` |
| Remove child | DELETE | `/api/v1/master/children/{childId}` or `.../unlink` |
| Active broker | GET/POST/DELETE | `/api/v1/master/active-account` |

### Child

| UI tab | Method | Path |
|--------|--------|------|
| Find masters | GET | `/api/v1/child/masters` (**auth required**) |
| Subscriptions | GET | `/api/v1/child/subscriptions` |
| Subscribe | POST | `/api/v1/child/subscriptions` |
| Unsubscribe / remove master | DELETE | `/api/v1/child/subscriptions/{masterId}` or `/child/remove/{masterId}` |
| Trade timeline | GET | `/api/v1/child/trade-timeline` |
| Copied trades | GET | `/api/v1/child/copied-trades` |
| Copy logs | GET | `/api/v1/child/copy/logs` |
| Analytics / P&L dashboard | GET | `/api/v1/child/analytics` or `/child/pnl-dashboard` |
| Positions | GET | `/api/v1/child/positions` |
| Open book / options / option status | GET | `/api/v1/child/open-book`, `open-options`, `option-status` |
| Risk | GET/PUT | `/api/v1/risk/...` (child JWT) |

### Profile

| UI | Method | Path |
|----|--------|------|
| Profile + brokers | GET | `/api/v1/users/me` |

### P&L (any role)

| UI | Method | Path |
|----|--------|------|
| Unrealized | GET | `/api/v1/pnl/unrealized` |
| Summary | GET | `/api/v1/pnl/summary` |
| Child vs master | GET | `/api/v1/pnl/child-vs-master?masterId=` |

### Broker (per linked account)

| UI | Method | Path |
|----|--------|------|
| Orders | GET | `/api/v1/brokers/accounts/{accountId}/orders` |
| Positions | GET | `/api/v1/brokers/accounts/{accountId}/positions` |
| Close / square off | POST | `/api/v1/brokers/accounts/{accountId}/orders/close-position` |
| Disconnect | POST | `/api/v1/brokers/accounts/{accountId}/disconnect` |
| Login / reconnect | POST | `/api/v1/brokers/accounts/{accountId}/login` |

**Groww:** after disconnect, API key is **kept** — reconnect with TOTP only (no re-enter API key).

---

## 3. Important response fields

### Copy log row (trade logs / copy logs)

```json
{
  "symbol": "NIFTY ...",
  "tradeType": "BUY",
  "qty": 75,
  "childStatus": "SUCCESS | FAILED | SKIPPED",
  "errorMessage": "...",
  "skipReason": "SUB_LOT_SIZE",
  "latencyMs": 120,
  "copyGroupId": "uuid-group"
}
```

### Engine event detail (`/engine/trade-history/{eventId}`)

```json
{
  "eventId": "...",
  "childrenTotal": 3,
  "childrenSucceeded": 2,
  "childrenFailed": 0,
  "childrenSkipped": 1,
  "failures": [{ "childId", "status", "errorMessage", "skipReason" }],
  "children": [{ "childName", "status", "failureReason", "errorMessage", "skipReason", "symbol", "qty" }]
}
```

### Child trade timeline row

```json
{
  "status": "FAILED",
  "errorMessage": "...",
  "failureReason": "...",
  "skipReason": "...",
  "masterName": "...",
  "symbol": "...",
  "totalChildLatencyMs": 95
}
```

### Find masters card

```json
{
  "masterId": "uuid",
  "name": "...",
  "winRate": 85,
  "totalTrades": 120,
  "subscribers": 5,
  "mySubscriptionStatus": "ACTIVE | PENDING_APPROVAL | NOT_SUBSCRIBED",
  "subscribed": true
}
```

### Profile broker account (`/users/me`)

```json
{
  "brokerAccounts": [{
    "accountId", "broker", "nickname", "clientId",
    "marginAvailable", "marginUsed", "totalMargin",
    "openPositionsCount", "positions": [...],
    "sessionActive", "isTokenExpired", "tokenExpiresAt"
  }]
}
```

---

## 4. Deprecated / duplicate APIs (do not use)

| Avoid | Use instead |
|-------|-------------|
| Raw `trade_logs` for copy activity | `copy_logs` via endpoints above |
| `GET /api/subscriptions` (legacy) | `/api/v1/child/subscriptions` |
| Only `qrCode` for 2FA | `qrCode` or `qrCodeUri` from enable-2FA |
| `GET /child/masters` without `Authorization` | Always send Bearer token |

---

## 5. Known limitations (backend)

- **Realized P&L** is still `0` in several analytics endpoints until a full ledger exists; **unrealized** comes from live broker positions.
- **Broker sessions** must be active (`SESSION_EXPIRED` → user must re-login in Profile).
- **OTP SMS** needs AWS SNS on server; otherwise OTP appears in server logs only.

---

## 6. Other docs in repo

| File | Purpose |
|------|---------|
| **FRONTEND-API-MAP.md** (this file) | Tab → endpoint map for May 2026 deploy |
| `FRONTEND-COMPLETE-API.md` | Older full API list |
| `FRONTEND-INTEGRATION-GUIDE.md` | General integration |
| `NEW-DASHBOARD-API.md` | Dashboard-specific |
| `LATEST-CHANGES-API-DOC.md` | Prior changelog |
| `BROKER-MASTER-CHILD-API.md` | Broker flows |

**Share with FE team:** this file + Swagger at `{baseUrl}/swagger-ui.html`.
