# Ascentra Backend Spec v2 — Compliance Matrix

Compared against **Ascentra_Backend_Spec_v2.docx** (May 2026) and current `main` codebase.

Legend: ✅ Implemented | 🟡 Partial | ❌ Missing

## Section 1 — Latency tracking

| Spec endpoint / feature | Status | Notes |
|-------------------------|--------|-------|
| `CopyTradeEvent` + `ChildExecution` tables | 🟡 | Uses `copy_logs` + `copy_group_id`; dedicated tables optional |
| `GET /api/v1/engine/trade-history` | ✅ | Added — groups `copy_logs` by `copy_group_id` |
| `GET /api/v1/engine/trade-history/{eventId}` | ✅ | Added — `eventId` = `copyGroupId` |
| `GET /api/v1/engine/latency-stats` | ✅ | Added — aggregates from copy logs |
| `GET /api/v1/child/trade-timeline` | ✅ | Added |
| Per-child `brokerLatencyMs`, `entryPrice` async | 🟡 | `latency_ms` stored; entry price job not yet |

## Section 2 — P&L & prices

| Spec | Status | Notes |
|------|--------|-------|
| Enriched `GET /child/copied-trades` | 🟡 | Has entry/ltp/pnl from positions; missing `eventId`, `exitPrice`, `investedValue` |
| Async entry price fetch after order | ❌ | Spec §2.2 — future job |
| Position close / EOD P&L job | ❌ | Spec §2.3 |
| `GET /api/v1/master/trade-pnl` | ✅ | Alias added → master PnL summary |
| Positions: `dayHigh`, `dayLow`, etc. | 🟡 | Core PnL fields in `PositionDto` |

## Section 3 — Broker profile

| Spec | Status | Notes |
|------|--------|-------|
| `GET /brokers/accounts/{id}/profile` | ✅ | Normalized profile |
| `POST /brokers/accounts/{id}/refresh-profile` | ✅ | Bypass 60s cache |
| Full spec field parity (PAN mask, segments…) | 🟡 | Normalized core fields + `brokerSpecific` raw |

## Section 4 — User profile page

| Spec | Status | Notes |
|------|--------|-------|
| `GET /api/v1/users/me/profile` | ✅ | User + all broker accounts |
| `PUT /api/v1/users/me/profile` | 🟡 | Use `PUT /api/v1/auth/me` today; spec path aliased |
| `notificationPreferences` object | 🟡 | Telegram prefs on dedicated endpoints |

## Section 5 — Risk

| Spec | Status | Notes |
|------|--------|-------|
| `GET/PUT /api/v1/risk/rules` | ✅ | Basic 4 rules |
| `GET /api/v1/risk/status` | ✅ | Added earlier |
| `GET /api/v1/risk/check` with `checks[]` | 🟡 | Simple allowed/reason; full checklist partial |
| `GET /api/v1/risk/exposure` | ✅ | Added |
| `POST /api/v1/risk/check-trade` | ✅ | Added |
| `POST /api/v1/risk/pause` / `resume` | ✅ | Added (`copy_paused`, `paused_until`) |
| Master child risk overrides | 🟡 | Routes added; rules merge basic |
| Extended rules (maxLossPerDay, segments, …) | ❌ | Schema extension future |

## Section 6 — Telegram

| Spec | Status | Notes |
|------|--------|-------|
| `POST .../telegram/generate-link-token` | ✅ | Added |
| `GET .../telegram/status` | ✅ | Added |
| `PUT .../telegram/preferences` | ✅ | Added |
| `POST .../telegram/test` | ✅ | Added |
| `POST /api/v1/telegram/webhook` | ✅ | `/link`, `/help`, `/status` |
| HTML templates per alert type | 🟡 | `TelegramService` sends HTML; not all templates |
| Daily 4PM summary job | ❌ | Scheduled job future |

## Section 7 — Sub-100ms latency

| Spec | Status | Notes |
|------|--------|-------|
| `GET /api/v1/engine/config` | ✅ | Added |
| `PUT /api/v1/admin/engine/config` | 🟡 | Read-only config for now |
| Postback URL `/api/v1/engine/postback/zerodha` | ✅ | Alias added (spec path) |
| Fyers/Upstox WebSocket detection | ❌ | Still polling |
| Mumbai region / connection pooling | ❌ | Infra — EC2 still eu-north-1 |
| In-memory child cache | 🟡 | Dedup cache only |

## Wrong paths in spec vs code (fixed)

| Spec says | Was | Now |
|-----------|-----|-----|
| `/api/v1/engine/postback/zerodha` | `/api/v1/brokers/postback/zerodha` | **Both work** |
| `/api/v1/users/me/profile` | `/api/v1/auth/me` only | **`/users/me/profile` added** |

## Implementation priority (remaining)

1. Dedicated `copy_trade_events` table + async entry-price worker  
2. Extended risk schema + enforcement chain (§5.4)  
3. Fyers/Upstox order WebSockets  
4. Move EC2 to `ap-south-1`  
5. Unit tests per broker normalizer  
