# Frontend → Backend API map (May 2026)

Use these endpoints so empty UI tabs get real data. Deprecated duplicates are listed at the bottom.

## Auth / OTP / 2FA

| UI | Method | Path | Notes |
|----|--------|------|-------|
| OTP send | POST | `/api/v1/auth/otp/send` | Requires AWS SNS on server or read OTP from logs |
| OTP verify | POST | `/api/v1/auth/otp/verify` | |
| 2FA enable | POST | `/api/v1/auth/2fa/enable` | Response: `qrCodeUri` **and** `qrCode` (same value) — render QR client-side |
| 2FA confirm | POST | `/api/v1/auth/2fa/confirm` | |

## Master

| UI tab | Method | Path |
|--------|--------|------|
| Trade Logs | GET | `/api/v1/master/trade-logs` or `/trade-history` | **copy_logs** (not empty trade_logs table) |
| Copy logs | GET | `/api/v1/master/copy/logs` | |
| Engine history (latency/detail) | GET | `/api/v1/engine/trade-history` | Detail: `/api/v1/engine/trade-history/{eventId}` |
| Dashboard / overview | GET | `/api/v1/master/dashboard` | |
| P&L analytics | GET | `/api/v1/master/pnl-analytics` | |
| Open book | GET | `/api/v1/master/open-book` | |
| Open options | GET | `/api/v1/master/open-options` | |
| Option status | GET | `/api/v1/master/option-status` | Includes FAILED/SKIPPED + errorMessage |
| Square off | POST | `/api/v1/master/positions/square-off` | Body: symbol, qty, product, exchange (same as broker close-position) |
| Remove child | DELETE | `/api/v1/master/children/{childId}` or `.../unlink` | Sets INACTIVE |

## Child

| UI tab | Method | Path |
|--------|--------|------|
| Find masters | GET | `/api/v1/child/masters` | **Requires auth** — returns stats + subscription state |
| Trade timeline | GET | `/api/v1/child/trade-timeline` | Includes errorMessage / skipReason |
| P&L dashboard | GET | `/api/v1/child/pnl-dashboard` or `/analytics` | |
| Open book / options / option status | GET | `/api/v1/child/open-book`, `open-options`, `option-status` | |
| Remove master | DELETE | `/api/v1/child/subscriptions/{masterId}` or `/remove/{masterId}` | |

## Profile

| UI | Method | Path |
|----|--------|------|
| Me + brokers | GET | `/api/v1/users/me` | `brokerAccounts[]` includes margin, positions, session, clientId |

## P&L (shared)

| UI | Method | Path |
|----|--------|------|
| Unrealized | GET | `/api/v1/pnl/unrealized` | Uses live broker positions |
| Summary | GET | `/api/v1/pnl/summary` | copy_logs + positions |

## Broker (per account)

| UI | Method | Path |
|----|--------|------|
| Orders / open book | GET | `/api/v1/brokers/accounts/{accountId}/orders` | |
| Close position | POST | `/api/v1/brokers/accounts/{accountId}/orders/close-position` | |
| Groww reconnect | POST login after disconnect | API key **kept** on disconnect — TOTP-only reconnect |

## Admin

| UI | Method | Path |
|----|--------|------|
| Create master | POST | `/api/v1/admin/users/master` | |
| Create child | POST | `/api/v1/admin/users/child` | |

## Deprecated / avoid

| Avoid | Use instead |
|-------|-------------|
| `GET /api/v1/master/trade-history` reading `trade_logs` only (old) | Now aliased to copy_logs — still prefer `/engine/trade-history` for grouped events |
| `GET /api/subscriptions` (legacy) | `/api/v1/child/subscriptions` |

## Ops

- **New IP**: `GET http://13.53.246.13:8081/health` — if UP, fix FE base URL / CORS / broker callback URL.
- **Fyers**: Whitelist server IP in [Fyers API dashboard](https://myapi.fyers.in/dashboard); `BROKER_CALLBACK_URL` must match redirect URI.
- **Sessions**: Re-login brokers after deploy (`SESSION_EXPIRED` blocks orders/positions).
