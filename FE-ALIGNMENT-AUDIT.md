# Frontend alignment audit — [Sushil-Jadhav07/copy-trading](https://github.com/Sushil-Jadhav07/copy-trading)

Compared with backend `feature/backend_code` (`dhokeaniket/copy-trading`).

## Summary

| Area | FE status | Backend / action |
|------|-----------|------------------|
| Login + email 2FA | Mostly OK | `Login.jsx` uses `verifyLoginOtp` — good |
| Profile 2FA enable | Wrong verify API | Was calling `2fa/confirm` expecting tokens; use `2fa/verify` only |
| Profile QR UI | Obsolete | Remove QR; backend sends EMAIL/PHONE OTP only |
| Forgot password (email) | Broken | FE expected `resetToken` + link; backend now sends **email OTP** |
| Master P&L Summary | Partial zeros | Dashboard missing `pnl[]`, `winRate` — **fixed in backend** |
| Master P&L Analytics | Wrong summary keys | Expected `totalRealisedPnl` — **aliases added in backend** |

---

## 1. Pages to update (FE)

### Login (`src/pages/Login.jsx`)

- Email login → if `requires2FA` → OTP step → `POST /api/v1/auth/verify-login-otp`
- Remove any QR / Google Authenticator UI
- `twoFactorChannel`: `EMAIL` | `PHONE` for user message

### Security / Profile (`src/components/master/Profile.jsx`, child/admin Profile if present)

- `GET /api/v1/auth/2fa/options`
- `POST /api/v1/auth/2fa/enable` body: `{ "channel": "EMAIL" | "PHONE" }`
- `POST /api/v1/auth/2fa/verify` body: `{ "otp": "123456" }` — **do not** use `2fa/confirm` for enable
- Remove `qrCode` / `qrCodeUri` display
- Disable: `DELETE /api/v1/auth/2fa/disable` `{ "password", "otp" }`

### Forgot password (`src/pages/ForgotPassword.jsx`)

**New email flow:**

1. `POST /api/v1/auth/forgot-password` `{ "email" }` → OTP sent
2. User enters OTP + new password on same page (or `/reset-password`)
3. `POST /api/v1/auth/reset-password` `{ "email", "otp", "newPassword" }`

Phone tab: still `send-otp` + `verify-otp` (login only — change password in Profile after login).

---

## 2. API ↔ FE field map

### Login response

| FE field | Backend |
|----------|---------|
| `requires2FA` | `requires2FA` |
| `twoFactorChannel` | `EMAIL` \| `PHONE` |
| `accessToken` (pending) | JWT with `pending2fa: true` until OTP verified |
| `refreshToken` | `null` until OTP verified |

### Forgot / reset

| Step | API | Body |
|------|-----|------|
| Send OTP | `POST /auth/forgot-password` | `{ "email" }` |
| Reset | `POST /auth/reset-password` | `{ "email", "otp", "newPassword" }` |
| Legacy link | `POST /auth/reset-password` | `{ "token", "newPassword" }` |

### Master P&L Summary (`/master/trade-pnl` + dashboard)

| FE (`PnLSummary.jsx`) | Backend `summary` |
|-----------------------|-------------------|
| `totalRealisedPnl` | `totalRealisedPnl` |
| `totalUnrealisedPnl` | `totalUnrealisedPnl` |
| `totalRealizedPnl` | alias added |
| `totalUnrealizedPnl` | alias added |
| `todayPnl` | `todayPnl` |
| `analytics.pnl[]` (monthly) | `dashboard.pnl` (empty until monthly aggregation built) |

### Master P&L Analytics (`/master/pnl-analytics`)

| FE (`PnLAnalytics.jsx`) | Backend `summary` |
|-------------------------|-------------------|
| `totalRealisedPnl` / `totalRealizedPnl` | aliases → `combinedUnrealizedPnl` (realised still 0 until ledger) |
| `totalUnrealisedPnl` | `combinedUnrealizedPnl` |
| `dailyChart[]` | `dailyChart` (7-day copy activity) |
| `childPerformance[]` | `childPerformance` |

---

## 3. What Sushil’s repo did wrong

1. **2FA enable verify** — `authService.verifyTwoFactor()` posts to `/2fa/confirm` (does not exist) then `/2fa/verify` but expects login tokens back.
2. **Forgot password** — UI says “reset link” but backend only logged token in server logs (no email). Now backend sends Gmail OTP.
3. **QR code on enable** — still renders QR from empty `qrCodeUri`; backend no longer returns QR.
4. **Master dashboard** — `normalizeMasterAnalytics` expects `totalPnl`, `winRate`, `pnl[]`; dashboard returned only `activeChildren`, `successRate`, etc.

---

## 4. Deploy

Backend: pull `feature/backend_code`, rebuild, restart (MAIL_* for Gmail).

FE: apply patches in `copy-trading-fe` (or merge PR to Sushil’s repo).

Docs: `FRONTEND-INTEGRATION-DETAILED.md`, `GMAIL-OTP-SETUP.md`.
