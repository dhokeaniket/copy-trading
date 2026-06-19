# Ascentra Capital — Copy Trading Platform (Complete Documentation)

---

## 1. Project Overview

**Product**: Ascentra Capital — A real-time copy trading platform that replicates trades from master traders to child/follower accounts across multiple Indian stock brokers.

**Tech Stack**:
- **Language**: Java 21
- **Framework**: Spring Boot 3.2.5 (WebFlux — fully reactive/non-blocking)
- **Database**: PostgreSQL (via R2DBC reactive driver)
- **Cache**: Redis (session state, rate limiting, polling dedup)
- **Messaging**: Kafka (optional, for event-based replication)
- **Build**: Gradle 8.14
- **Container**: Docker (multi-stage Alpine build)
- **CI/CD**: GitHub Actions → SSH deploy to EC2
- **SMS OTP**: Twilio Verify
- **Email OTP**: Spring Mail (Gmail SMTP)
- **Auth**: JWT (HS256) + Google OAuth2 + 2FA (email/phone)

**Supported Brokers** (6 total):
| Broker | Login Method | Order API | API Docs |
|--------|-------------|-----------|----------|
| Groww | API key + TOTP/checksum | REST JSON | https://groww.in/api-docs |
| Zerodha | OAuth (request_token) | REST form-urlencoded | https://kite.trade/docs/connect/v3/ |
| Fyers | OAuth (auth_code) | REST JSON | https://api-t1.fyers.in/api/v3 |
| Upstox | OAuth (auth_code) | REST JSON | https://upstox.com/developer/api-documentation/ |
| Dhan | OAuth (consent + tokenId) | REST JSON | https://dhanhq.co/docs/v2/ |
| Angel One | Client code + password + TOTP | REST JSON | https://smartapi.angelone.in/ |

---

## 2. Architecture & Package Structure

```
src/main/java/com/copytrading/
├── engine/           # Core copy engine, polling, symbol mapping, instrument cache
├── broker/           # Broker integrations (6 brokers + proxy + adapters)
│   ├── groww/        # Groww API client + proxy router
│   ├── zerodha/      # Zerodha Kite Connect client
│   ├── upstox/       # Upstox v2 client
│   ├── dhan/         # Dhan v2 client
│   ├── fyers/        # Fyers v3 client
│   ├── angelone/     # Angel One SmartAPI client
│   ├── proxy/        # Generic per-user proxy HTTP client
│   └── dto/          # Request/response DTOs
├── auth/             # Authentication (JWT, Google, OTP, 2FA)
├── master/           # Master trader APIs (followers, P&L, active account)
├── child/            # Child/follower APIs (subscribe, copy settings)
├── subscription/     # Master-child copy links
├── trade/            # Trade execution, basket orders
├── replication/      # Kafka-based trade replication (alternative)
├── risk/             # Risk control (per-child limits)
├── pnl/              # P&L calculation
├── positions/        # Live position tracking
├── notification/     # In-app + Telegram notifications
├── security/         # Security config, JWT filter, credential encryption
├── cache/            # Redis config, polling state
├── admin/            # Admin user management
├── logs/             # Copy logs, trade logs
├── ws/               # WebSocket for live updates
├── config/           # Schema initializer, polling properties
├── alert/            # Balance alert service
└── trading/          # Trading data aggregation
```

---

## 3. Database Schema (PostgreSQL)

### Tables:

| Table | Purpose |
|-------|---------|
| `users` | All user accounts (ADMIN, MASTER, CHILD) |
| `refresh_tokens` | JWT refresh token rotation |
| `password_reset_tokens` | Password reset OTP tokens |
| `broker_accounts` | Linked broker demat accounts per user |
| `subscriptions` | Master-child copy trading links |
| `trades` | Internal trade records |
| `trade_logs` | Historical trade log entries |
| `copy_logs` | Detailed per-copy execution logs (latency, status, errors) |
| `notifications` | In-app notification store |
| `master_active_accounts` | Currently active broker account per master |
| `risk_rules` | Per-user risk limits |
| `broker_error_logs` | Broker API error tracking |

### Key Relationships:
- `users` → `broker_accounts` (1:many, each user can link multiple brokers)
- `users` → `subscriptions` (master_id + child_id, unique pair)
- `subscriptions` → `broker_accounts` (which broker account to use for child's orders)
- `master_active_accounts` → `broker_accounts` (which account the engine polls)

---

## 4. Copy Trading Engine — How It Works

### Detection → Replication Flow:

```
[Master trades on broker] → [OrderPollingService polls every 500ms]
    → [New filled order detected]
    → [CanonicalOrderMapper normalizes to unified format]
    → [Dedup check (3 layers: memory + lock + Redis)]
    → [CopyEngineService.copyTrade()]
        → For each ACTIVE child subscription:
            1. RiskService.checkRiskLimits() — max trades/day, positions, margin
            2. SellGuardService — verify child holds position before sell
            3. LotSizeScaler — round F&O qty to exchange lot multiples
            4. SymbolMapper.translate() — convert symbol format between brokers
            5. BrokerRateLimiter.acquire() — respect per-broker API rate caps
            6. placeOrderOnBroker() — call child's broker API
            7. Log result → CopyLog table
            8. Notify child (in-app + Telegram)
            9. WebSocket broadcast
```

### Key Engine Services:

| Service | File | Purpose |
|---------|------|---------|
| `OrderPollingService` | `engine/OrderPollingService.java` | Polls master orders every 500ms, detects new fills |
| `CopyEngineService` | `engine/CopyEngineService.java` | Core copy logic: scale, translate, place child orders |
| `CanonicalOrderMapper` | `engine/CanonicalOrderMapper.java` | Normalizes raw broker orders to canonical format |
| `SymbolMapper` | `engine/SymbolMapper.java` | Translates F&O symbols between broker formats |
| `InstrumentCache` | `engine/InstrumentCache.java` | Downloads/caches 200K+ instrument masters |
| `LotSizeScaler` | `engine/LotSizeScaler.java` | Rounds F&O qty to lot multiples |
| `BrokerFieldTranslator` | `engine/BrokerFieldTranslator.java` | Maps field names/values between brokers |
| `SellGuardService` | `engine/SellGuardService.java` | Prevents selling positions child doesn't hold |
| `BrokerRateLimiter` | `engine/BrokerRateLimiter.java` | Per-broker order rate limits |
| `SubscriptionCache` | `engine/SubscriptionCache.java` | Cached active subscriptions for fast lookup |

### Duplicate Detection:
- **In-memory set**: `knownOrders` per master (ConcurrentHashMap)
- **Processing lock**: `processingOrders` set prevents concurrent processing
- **Redis persistence**: `PollingStateCache` persists known orders across restarts
- **DB reload**: On startup, loads today's trades from DB as known orders

### Auto-Reset:
- 9:15 AM IST (market open) — clears polling cache for fresh day
- 9:00 AM IST — sends session expiry reminders to children

---

## 5. Symbol Mapping Logic

The `SymbolMapper` handles F&O symbol translation between all 6 brokers. Each broker uses a different format for derivatives:

| Broker | Example (NIFTY 24850 CE, 9 Jun 2026 weekly) |
|--------|---------------------------------------------|
| Zerodha | `NIFTY2660924850CE` |
| Groww | `NIFTY26624850CE` |
| Fyers | `NSE:NIFTY2660924850CE` |
| Upstox | `NIFTY 24850 CE 09 JUN 26` |
| Dhan | `NIFTY-Jun2026-24850-CE` (+ securityId lookup) |
| Angel One | `NIFTY2660924850CE` (+ symboltoken lookup) |

### How translation works:
1. **Parse** source symbol into `ParsedSymbol(underlying, year, month, date, strike, type)`
2. **Build** target symbol using the target broker's format rules
3. For Upstox/Dhan/AngelOne, additional **instrument token lookup** is required from their instrument master files

### Instrument Cache Loading:
- **Dhan**: CSV from `https://images.dhan.co/api-data/api-scrip-master.csv` (symbol → securityId)
- **Upstox**: JSON.gz from `https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz` (symbol → instrument_key)
- **Angel One**: JSON from `https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json` (symbol → symboltoken)
- Refreshes daily at 2:30 AM UTC (8:00 AM IST)

---

## 6. Broker Integrations — Detailed

### 6.1 Groww
- **API Base**: `https://api.groww.in`
- **Auth**: Bearer API key (long-lived JWT from Groww dashboard)
- **Login**: TOTP (`POST /v1/token/api/access` with `key_type: "totp"`) or API secret checksum (`key_type: "approval"`, `checksum: sha256(apiSecret + timestamp)`)
- **Order**: `POST /v1/order/create` — JSON body with `trading_symbol`, `quantity`, `exchange`, `segment`, `product`, `order_type`, `transaction_type`
- **IP Whitelist**: Each Groww user must whitelist a specific public IP in their Groww API dashboard
- **Proxy**: Per-user IP routing via Squid/tinyproxy (Mumbai multi-IP setup)

### 6.2 Zerodha (Kite Connect v3)
- **API Base**: `https://api.kite.trade`
- **Auth**: `token api_key:access_token` header
- **Login**: OAuth → `request_token` → `POST /session/token` with `checksum = sha256(api_key + request_token + api_secret)`
- **Order**: `POST /orders/regular` — form-urlencoded body
- **Per-user**: Each user needs their own Kite Connect app (personal plan = 1 user/app). `api_key` + `api_secret` stored per account.
- **Docs**: https://kite.trade/docs/connect/v3/

### 6.3 Fyers (v3)
- **API Base**: `https://api-t1.fyers.in/api/v3`
- **Auth**: `Authorization: appId:accessToken`
- **Login**: OAuth → `auth_code` → `POST /validate-authcode` with `appIdHash = sha256(app_id + ":" + secret_key)`
- **Order**: `POST /orders/sync` — JSON with numeric `type` (1=LIMIT, 2=MARKET, 3=SL-M, 4=SL) and `side` (1=BUY, -1=SELL)
- **Symbol**: Prefixed with exchange, e.g., `NSE:RELIANCE-EQ`, `NSE:NIFTY2660924850CE`
- **Docs**: https://myapi.fyers.in/docsv3

### 6.4 Upstox (v2)
- **API Base**: `https://api.upstox.com`
- **Auth**: `Authorization: Bearer access_token`
- **Login**: OAuth → `auth_code` → `POST /v2/login/authorization/token` (form-urlencoded)
- **Order**: `POST /v2/order/place` — JSON with `instrument_token` (e.g., `NSE_FO|46303`), NOT trading symbol
- **Instrument lookup**: Required — symbol must be resolved to `instrument_key` from Upstox instrument master
- **Docs**: https://upstox.com/developer/api-documentation/

### 6.5 Dhan (v2)
- **API Base**: `https://api.dhan.co`
- **Auth**: `access-token` header
- **Login**: 3-step: `POST /app/generate-consent` → browser login → `GET /app/consumeApp-consent?tokenId=...`
- **Order**: `POST /v2/orders` — JSON with numeric `securityId` (resolved from Dhan CSV master), `dhanClientId`, `exchangeSegment`
- **Instrument lookup**: Required — CSV gives tradingSymbol → securityId mapping
- **Docs**: https://dhanhq.co/docs/v2/

### 6.6 Angel One (SmartAPI v2)
- **API Base**: `https://apiconnect.angelone.in`
- **Auth**: `Authorization: Bearer jwtToken` + `X-PrivateKey: apiKey`
- **Login**: `POST /rest/auth/angelbroking/user/v1/loginByPassword` with `clientcode`, `password`, `totp`
- **Order**: `POST /rest/secure/angelbroking/order/v1/placeOrder` — JSON with `symboltoken` (numeric, from scrip master), `tradingsymbol`
- **Instrument lookup**: Required — JSON scrip master gives symbol → symboltoken mapping
- **Docs**: https://smartapi.angelone.in/

---

## 7. Proxy System — Multi-IP Routing

### Problem:
Groww requires each API user to whitelist a specific IP address. With multiple users on the same server, we need each user's API calls to exit from a different public IP.

### Solution: Multi-IP Proxy Architecture

**Stockholm EC2 (app server):**
- Primary ENI (eth0): `13.53.246.13` — default egress
- Secondary ENI (eth1): `13.61.58.89` — via tinyproxy on `127.0.0.1:8889`
- Additional proxies on ports 8890-8892 for more IPs

**Mumbai EC2 (proxy server):**
- Squid proxy with 4 ports, each bound to a different private IP → EIP:
  - Port 3128 → `172.31.41.5` → EIP `15.207.205.137`
  - Port 3129 → `172.31.36.30` → EIP `15.207.175.205`
  - Port 3130 → `172.31.45.247` → EIP `3.108.243.110`
  - Port 3131 → `172.31.45.67` → EIP `13.207.20.220`

### How routing works:
1. Each `broker_account` has an `ip_slot` (auto-assigned for Groww)
2. `GrowwProxyRouter.getProxiedClient(slot)` returns an `HttpClient` configured with the proxy for that slot
3. `ProxyHttpClient` supports generic per-account proxy (host/port/user/pass stored in `broker_accounts` table)
4. Order placement calls use the proxied client so requests exit from the correct IP

### Frontend IP table for users:
| # | IP to Whitelist | Proxy Host | Proxy Port | Location |
|---|----------------|------------|------------|----------|
| 1 | 13.53.246.13 | — | — | Stockholm (direct) |
| 2 | 13.61.58.89 | 127.0.0.1 | 8889 | Stockholm |
| 3 | 13.60.103.120 | 127.0.0.1 | 8890 | Stockholm |
| 4 | 56.228.67.106 | 127.0.0.1 | 8891 | Stockholm |
| 5 | 13.48.122.204 | 127.0.0.1 | 8892 | Stockholm |
| 6 | 15.207.205.137 | 15.207.205.137 | 3128 | Mumbai |
| 7 | 15.207.175.205 | 15.207.205.137 | 3129 | Mumbai |
| 8 | 3.108.243.110 | 15.207.205.137 | 3130 | Mumbai |
| 9 | 13.207.20.220 | 15.207.205.137 | 3131 | Mumbai |

**Note**: Mumbai IPs added via `ip addr add` are temporary (lost on reboot). Need netplan config for persistence.

---

## 8. Authentication System

### 8.1 JWT Authentication
- **Access Token**: 15 minutes, HS256, claims: `sub` (userId), `email`, `role`
- **Refresh Token**: 7 days, rotated on use (old token revoked, new issued)
- **Secret**: `JWT_SECRET` env var, SHA-256 hashed before use as HMAC key

### 8.2 Google OAuth
- Frontend gets Google ID token → Backend verifies via Google public keys
- Auto-creates user on first login (role: CHILD by default)
- Links Google account to existing email-registered user
- Env: `GOOGLE_CLIENT_ID`

### 8.3 Two-Factor Authentication (2FA)
- Channels: EMAIL or PHONE (user chooses)
- Email: 6-digit OTP via Gmail SMTP
- Phone: OTP via Twilio Verify service
- Enable flow: send OTP → verify OTP → 2FA enabled
- Login with 2FA: password → OTP sent → verify OTP → tokens issued

### 8.4 SMS OTP (Twilio)
- Uses Twilio Verify service (not raw SMS)
- Env vars: `TWILIO_ACCOUNT_SID`, `TWILIO_API_KEY`, `TWILIO_API_SECRET`, `TWILIO_VERIFY_SERVICE_SID`
- Rate limited: 60s between sends, 5 max attempts, 10 min expiry
- Redis-backed state with in-memory fallback

### 8.5 Security Config
- Public endpoints: register, login, send-otp, verify-otp, forgot-password, Google auth, broker callbacks, WebSocket
- Admin endpoints: require `ROLE_ADMIN`
- Everything else: requires valid JWT
- CORS: all origins allowed (for mobile + web frontend)

---

## 9. Risk Management

### Per-Child Risk Rules (`risk_rules` table):
| Rule | Default | Description |
|------|---------|-------------|
| `max_trades_per_day` | 50 | Max successful copies per day |
| `max_open_positions` | 20 | Max concurrent open positions |
| `max_capital_exposure` | 80% | Max margin utilization % |
| `margin_check_enabled` | true | Whether to check margin before copy |
| `copy_paused` | false | Manual pause (or auto by risk trigger) |
| `paused_until` | null | Time-bound pause |

### Risk Check Flow:
```
Before each child copy:
1. Is copy_paused? → SKIP
2. Today's trades >= max_trades_per_day? → SKIP
3. Open positions >= max_open_positions? → SKIP
4. Margin utilization > max_capital_exposure? → SKIP
5. All checks pass → proceed with order
```

### Additional Safeguards:
- **SellGuardService**: Prevents selling positions the child doesn't hold
- **LotSizeScaler**: Ensures F&O orders are in valid lot multiples
- **BrokerRateLimiter**: Per-broker API rate limits to avoid being blocked
- **BalanceAlertService**: Post-trade balance check with notifications

---

## 10. P&L Calculation

### How P&L is computed:

1. **Live Unrealized P&L**: Fetched from broker positions API for each account
   - Each broker's position response contains current P&L per position
   - Aggregated across all positions for total unrealized P&L

2. **Realized P&L**: Computed from trades table using FIFO matching
   - BUY trades are queued
   - SELL trades matched against oldest BUY (FIFO)
   - Realized = (sell_price - buy_price) × matched_qty

3. **Master P&L Analytics** (`MasterService.getPnlAnalytics()`):
   - `masterUnrealizedPnl`: From broker positions API
   - `followersUnrealizedPnl`: Sum of all children's broker P&L
   - `combinedUnrealizedPnl`: Master + followers
   - `instrumentPnl`: Per-instrument realized P&L from FIFO
   - `dailyChart`: 7-day daily P&L breakdown
   - `monthlyPnl`: Monthly aggregation

4. **Child P&L** (`ChildService.getCopiedTrades()`):
   - Enriched with live position data (entry price, current LTP, P&L per copy)

---

## 11. Order Placement — Per-Broker Field Mapping

### `BrokerFieldTranslator` maps these fields:

| Canonical | Groww | Zerodha | Fyers | Upstox | Dhan | Angel One |
|-----------|-------|---------|-------|--------|------|-----------|
| BUY | BUY | BUY | 1 | Buy | BUY | BUY |
| SELL | SELL | SELL | -1 | Sell | SELL | SELL |
| MARKET | MARKET | MARKET | 2 | MARKET | MARKET | MARKET |
| LIMIT | LIMIT | LIMIT | 1 | LIMIT | LIMIT | LIMIT |
| SL | SL | SL | 4 | SL | SL | STOPLOSS_LIMIT |
| MIS (intraday) | INTRADAY | MIS | INTRADAY | I | INTRADAY | INTRADAY |
| CNC (delivery) | DELIVERY | CNC | CNC | D | CNC | DELIVERY |
| NSE equity | NSE_CASH | NSE | NSE | — | NSE_EQ | NSE |
| NSE F&O | NSE_FO | NFO | NSE | — | NSE_FNO | NFO |
| BSE F&O | BSE_FO | BFO | BSE | — | BSE_FNO | BFO |

---

## 12. Infrastructure & Deployment

### EC2 Instances:

**Stockholm (App Server)**:
- Host: `ip-172-31-26-85` (private), user `ec2-user`
- Port: 8081
- Java 21, Gradle, PostgreSQL (RDS), Redis
- App start: `nohup java -Xmx512m -jar build/libs/copy-trading-backend-0.1.0.jar >> /home/ec2-user/ascentra.log 2>&1 &`
- Startup time: ~40 seconds (instrument cache loads 200K+ symbols)

**Mumbai (Proxy Server)**:
- Host: `15.207.205.137` (EIP), user `ubuntu`
- Key: `~/proxy-mumbai.pem`
- Squid proxy on ports 3128-3131
- 4 Elastic IPs attached to secondary private IPs on ENI

### Database:
- **Host**: `database-1.clysagkw8b8t.eu-north-1.rds.amazonaws.com`
- **User**: `postgres`
- **Database**: `copytrading`
- **Driver**: R2DBC PostgreSQL (reactive)

### Deployment (GitHub Actions):
```yaml
# .github/workflows/deploy.yml
- SSH to EC2
- git pull origin main
- ./gradlew clean build -x test
- Kill old process, start new jar
- Wait 45s, health check
```

### Docker:
```dockerfile
FROM gradle:8.14-jdk21-alpine AS build
# Build bootJar
FROM eclipse-temurin:21-jre-alpine
# Run with PORT env
```

---

## 13. API Endpoints Summary

### Auth (`/api/v1/auth/`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /register | Register new user |
| POST | /login | Email + password login |
| POST | /google | Google OAuth login |
| POST | /send-otp | Send SMS OTP |
| POST | /verify-otp | Verify SMS OTP + login |
| POST | /send-email-otp | Send email OTP for 2FA |
| POST | /verify-email-otp | Verify email OTP |
| POST | /refresh-token | Refresh access token |
| POST | /forgot-password | Send reset OTP |
| POST | /reset-password | Reset with OTP |
| POST | /logout | Revoke refresh token |
| GET | /profile | Get user profile |
| PUT | /profile | Update profile |
| POST | /enable-2fa | Enable 2FA |
| POST | /verify-2fa | Confirm 2FA enable |
| POST | /disable-2fa | Disable 2FA |

### Brokers (`/api/v1/brokers/`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /brokers | List supported brokers |
| POST | /accounts | Link new broker account |
| GET | /accounts | List user's broker accounts |
| GET | /accounts/{id} | Get account detail |
| PUT | /accounts/{id} | Update account |
| DELETE | /accounts/{id} | Unlink account |
| POST | /accounts/{id}/login | Login to broker |
| GET | /accounts/{id}/status | Session status + login options |
| GET | /accounts/{id}/oauth-url | Get OAuth URL |
| PUT | /accounts/{id}/token | Set access token directly |
| POST | /accounts/{id}/disconnect | End session |
| GET | /accounts/{id}/margin | Get margin/funds |
| GET | /accounts/{id}/positions | Get positions |
| GET | /accounts/{id}/orders | Get orders |
| GET | /accounts/{id}/trades | Get trade book |
| GET | /accounts/{id}/holdings | Get holdings |
| GET | /accounts/{id}/profile | Get broker profile |

### Master (`/api/v1/master/`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /children | List followers |
| POST | /link-child | Link child |
| POST | /unlink-child | Unlink child |
| PUT | /scaling | Update child scaling |
| POST | /approve | Approve child subscription |
| POST | /reject | Reject child subscription |
| GET | /active-account | Get active broker account |
| POST | /active-account | Set active broker account |
| DELETE | /active-account | Clear active account |
| GET | /copy-trading | Full copy trading page data |
| GET | /pnl-analytics | P&L analytics |
| GET | /copy-logs | Copy execution logs |
| GET | /earnings | Earnings breakdown |
| GET | /dashboard | Dashboard stats |

### Child (`/api/v1/child/`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /masters | List available masters |
| POST | /subscribe | Subscribe to master |
| POST | /unsubscribe | Unsubscribe |
| GET | /subscriptions | List my subscriptions |
| GET | /scaling | Get scaling factor |
| PUT | /scaling | Update scaling |
| POST | /pause | Pause copying |
| POST | /resume | Resume copying |
| GET | /copied-trades | Get copied trades |
| PUT | /copy-settings | Update copy settings (sides, tolerance) |
| POST | /switch-broker | Switch broker account |

### Engine (`/api/v1/engine/`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /copy-trade | Manual copy trigger |
| GET | /status | Engine status |
| POST | /polling/start | Start polling |
| POST | /polling/stop | Stop polling |
| POST | /polling/reset | Reset known orders |
| GET | /polling/state | Polling state |

### Trades (`/api/v1/trades/`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /execute | Place order + auto-replicate |
| GET | / | List trades |
| GET | /{id} | Get trade detail |
| DELETE | /{id}/cancel | Cancel order |
| GET | /{id}/replications | Get copy results |
| GET | /open-positions | Open positions |
| POST | /basket | Basket order |

### Risk (`/api/v1/risk/`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /status | Risk dashboard |
| GET | /rules | Get risk rules |
| PUT | /rules | Update risk rules |
| POST | /pause | Pause copy trading |
| POST | /resume | Resume copy trading |
| POST | /check-trade | Pre-check a trade |
| GET | /exposure | Capital exposure |

### Admin (`/api/v1/admin/`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /users | List all users |
| POST | /users/master | Create master |
| POST | /users/child | Create child |
| POST | /users/admin | Create admin |
| GET | /users/{id} | Get user |
| PUT | /users/{id} | Update user |
| POST | /users/{id}/activate | Activate |
| POST | /users/{id}/deactivate | Deactivate |
| DELETE | /users/{id} | Delete (cascade) |
| GET | /analytics | Platform analytics |
| GET | /health | System health |
| GET | /subscriptions | All subscriptions |
| GET | /master-child-map | Master-child relationships |

---

## 14. Credential Security

### `BrokerCredentials` / `CredentialCrypto`:
- All sensitive fields (`accessToken`, `apiSecret`, `proxyPass`) are AES-encrypted at rest in PostgreSQL
- `credentials.encryptSensitiveFields(account)` — encrypts all fields (used on first save / update)
- `credentials.encryptAccessTokenOnly(account)` — encrypts only accessToken (used after login to avoid double-encrypting apiSecret)
- `credentials.accessToken(account)` — decrypts access token for API calls
- `credentials.apiSecret(account)` — decrypts API secret for login flows

---

## 15. Notifications

### Channels:
1. **In-app**: Stored in `notifications` table, broadcast via WebSocket
2. **Telegram**: Bot sends messages to linked users (`telegram_chat_id` on user profile)
3. **WebSocket**: Real-time events at `/ws/**` (trade executed, session expired, etc.)

### Notification Types:
- `TRADE_COPIED` — Master's trade copied to children
- `TRADE_EXECUTED` — Order placed successfully
- `TRADE_FAILED` — Order placement failed
- `SESSION_EXPIRED` — Broker session needs re-login
- `SESSION_REMINDER` — Daily pre-market session check
- `BROKER_DISCONNECTED` — User disconnected broker
- `ORDER_CANCELLED` — Cancel propagation from master
- `MARKET_CLOSED` — Intraday trade skipped outside hours
- `LOW_BALANCE` — Balance alert after trade

---

## 16. Key Design Decisions

1. **Fully Reactive**: WebFlux + R2DBC for high-throughput, non-blocking I/O (polling 500ms across many masters)
2. **Per-user proxy**: Groww's IP whitelist requires unique IPs per user — solved with multi-ENI + Squid
3. **Per-user Zerodha credentials**: Kite Connect personal plan limits 1 user per app
4. **3-layer dedup**: Prevents same order being copied twice (memory → lock → Redis)
5. **Instrument cache**: Pre-loaded 200K+ symbols for fast token/securityId lookup (no API call at order time)
6. **Lot size rounding**: F&O orders must be in lot multiples — automatic rounding with sub-lot rejection
7. **Sell guard**: Prevents selling positions child doesn't hold (fetches live positions before SELL copy)
8. **Cancel propagation**: When master cancels an order, all child copies are automatically cancelled
9. **Auto-reset at market open**: Clears dedup cache at 9:15 AM IST for fresh trading day
10. **Subscription approval**: New children start as PENDING_APPROVAL; previously approved can re-subscribe instantly

---

## 17. Configuration (application.yml)

Key environment variables:
```yaml
PORT: 8081
SPRING_R2DBC_URL: r2dbc:postgresql://...
SPRING_R2DBC_USERNAME: postgres
SPRING_R2DBC_PASSWORD: ...
JWT_SECRET: ...
GOOGLE_CLIENT_ID: 448062236998-...
TWILIO_ACCOUNT_SID: ACedd1dd...
TWILIO_API_KEY: SK8b4160e0f7...
TWILIO_API_SECRET: ...
TWILIO_VERIFY_SERVICE_SID: VA72f42b41ff97...
TELEGRAM_BOT_TOKEN: ...
ENGINE_POLLING_INTERVAL_MS: 500
GROWW_API_KEY: ...
ZERODHA_API_KEY: ...
# Fyers / Upstox / Angel: app credentials are per user — PUT apiKey + apiSecret on each broker account (no platform FYERS_/UPSTOX_/ANGELONE_* env)
DHAN_API_KEY: ...
BROKER_CALLBACK_URL: https://api.ascentracapital.com/api/v1/brokers/callback
SERVER_EGRESS_IP: 13.53.246.13
```

---

## 18. Broker API Documentation Links

| Broker | Official API Docs | Key Endpoints Used |
|--------|------------------|--------------------|
| **Zerodha** | https://kite.trade/docs/connect/v3/ | /session/token, /orders/regular, /user/margins, /portfolio/positions |
| **Groww** | https://groww.in/api-docs (partner access) | /v1/token/api/access, /v1/order/create, /v1/margins/detail/user, /v1/positions/user |
| **Fyers** | https://myapi.fyers.in/docsv3 | /validate-authcode, /orders/sync, /funds, /positions |
| **Upstox** | https://upstox.com/developer/api-documentation/ | /v2/login/authorization/token, /v2/order/place, /v2/user/get-funds-and-margin |
| **Dhan** | https://dhanhq.co/docs/v2/ | /app/generate-consent, /v2/orders, /v2/fundlimit, /v2/positions |
| **Angel One** | https://smartapi.angelone.in/ | /rest/auth/.../loginByPassword, /rest/secure/.../placeOrder, /rest/secure/.../getPosition |

### Instrument Master Sources:
| Broker | URL | Format | Size |
|--------|-----|--------|------|
| Dhan | https://images.dhan.co/api-data/api-scrip-master.csv | CSV | ~50MB |
| Upstox | https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz | JSON.gz | ~80MB |
| Angel One | https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json | JSON | ~50MB |

---

## 19. Build & Run

### Local Development:
```bash
# Build
./gradlew clean build -x test

# Run
java -Xmx512m -jar build/libs/copy-trading-backend-0.1.0.jar
```

### EC2 Production:
```bash
kill $(pgrep -f copy-trading-backend) 2>/dev/null
sleep 2
nohup java -Xmx512m \
  -DGOOGLE_CLIENT_ID=... \
  -DTWILIO_ACCOUNT_SID=... \
  -DTWILIO_API_KEY=... \
  -DTWILIO_API_SECRET=... \
  -DTWILIO_VERIFY_SERVICE_SID=... \
  -jar /home/ec2-user/copy-trading/build/libs/copy-trading-backend-0.1.0.jar \
  >> /home/ec2-user/ascentra.log 2>&1 &
```

### Docker:
```bash
docker build -t ascentra-backend .
docker run -p 8081:8081 -e PORT=8081 -e SPRING_R2DBC_URL=... ascentra-backend
```

---

## 20. Git Workflow

- **Branch**: `feature/backend_code`
- **Remotes**: `origin` (Aniket-jha) + `upstream` (dhokeaniket)
- **Deploy trigger**: Push to `main` → GitHub Actions SSH deploy
- **Pre-commit**: `.githooks/prepare-commit-msg` configured
- **Important**: Always `git checkout -- .gradle` before `git pull` on EC2

---

## 21. Known Issues & Operational Notes

1. **Groww 7-day IP change policy**: Cannot change whitelisted IP more than once per week
2. **Mumbai proxy IPs are temporary**: Added via `ip addr add`, lost on reboot — need netplan config
3. **Twilio free trial**: Only sends to verified numbers. Upgrade to paid for production.
4. **Upstox auth codes are single-use**: Frontend must never retry with same code
5. **App takes ~40s to start**: Instrument cache downloads 200K+ symbols on boot
6. **Zerodha per-user**: Each user needs their own Kite Connect app ($2000/year)
7. **Google Login origin**: Frontend domain must be added to Google Cloud Console OAuth origins
