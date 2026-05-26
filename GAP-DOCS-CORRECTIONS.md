# Corrections to External Gap Analysis Documents

Reviewed:

- `api-gap-analysis.md`
- `BACKEND_Required_Changes.md`
- `Full_CrossCheck_Analysis.md`

This file records what was **wrong in those docs**, what the **backend already has**, and what we **fixed in code** (May 2026).

---

## Telegram

| Claim | Verdict | Reality |
|-------|---------|---------|
| Webhook returns `"ok": "true"` string | ✅ Fixed | Now `"ok": true` (boolean) |
| Bot name `AscentraAlertBot` | ❌ Wrong in our old docs | Use **`@Copy_tradingsBot`** (`TELEGRAM_BOT_USERNAME=Copy_tradingsBot`) |
| User joins via manual `telegramChatId` only | ⚠️ Partial | Prefer **`POST .../generate-link-token`** + `/link CODE` in bot |

**User flow:** See [TELEGRAM-SETUP.md](./TELEGRAM-SETUP.md).

---

## Auth

| Claim | Verdict | Reality |
|-------|---------|---------|
| Login missing `telegramChatId`, `twoFactorEnabled` | ❌ Often wrong | Documented in API; verify runtime — `UserDto` includes fields |
| Logout `"success": "true"` string | ✅ Fixed | Now `"success": true` boolean |
| `PUT /users/me/profile` only `displayName` | ⚠️ Doc error | Backend accepts **`name`** and **`displayName`** |

---

## Copy logs security

| Claim | Verdict | Reality |
|-------|---------|---------|
| `GET /copy/logs` public, all users | ⚠️ Was risky | Endpoint required JWT but returned **all** logs → **fixed**: scoped to master/child/admin |

Prefer:

- `GET /api/v1/master/copy/logs`
- `GET /api/v1/child/copy/logs`

---

## Profile / broker

| Claim | Verdict | Reality |
|-------|---------|---------|
| Missing `fundsUtilizationStatus`, `marginUsedPercent`, etc. | ✅ Fixed | `GET /brokers/accounts/{id}/profile` and `/users/me/profile` include these via `BrokerProfileService` |
| 2FA field `qrCodeUri` | ⚠️ Naming | API should return **`qrCode`** (not `qrCodeUri`) — FE `Profile.jsx` reads `qrCode` first |

---

## Endpoints the gap docs asked for — now in backend

| Endpoint | Status |
|----------|--------|
| `GET /engine/trade-history` | ✅ |
| `GET /child/trade-timeline` | ✅ |
| `GET /users/me/profile` | ✅ |
| `GET /brokers/accounts/{id}/profile` | ✅ |
| `POST /notifications/telegram/*` | ✅ |
| `GET /risk/status`, `/risk/exposure`, pause/resume | ✅ |
| `PATCH /child/subscriptions/copy-settings` | ✅ |

Still **not** implemented (future):

- Dedicated `CopyTradeEvent` / `ChildExecution` tables  
- Async entry/exit price jobs  
- Full extended risk schema (maxLossPerDay, trading windows, …)  
- Fyers/Upstox order WebSockets  
- `PUT /admin/engine/config`  

---

## FE should use

| Topic | Correct source |
|-------|----------------|
| All new APIs | [FE-INTEGRATION-GUIDE.md](./FE-INTEGRATION-GUIDE.md) |
| Telegram UX | [TELEGRAM-SETUP.md](./TELEGRAM-SETUP.md) |
| Full backend | [PLATFORM-GUIDE.md](./PLATFORM-GUIDE.md) |
| Spec compliance | [ASCENTRA-SPEC-GAP.md](./ASCENTRA-SPEC-GAP.md) |

Do **not** treat `api-gap-analysis.md` as authoritative without cross-checking this file.
