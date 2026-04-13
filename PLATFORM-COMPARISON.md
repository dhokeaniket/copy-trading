# Ascentra vs Industry — Platform Comparison & Gap Analysis

## How Copy Trading Platforms Work (Industry Standard)

Based on research of Tradetron, AlgoTest, Bitget, eToro, and other platforms:

```
Master places trade → Platform detects it → Scales for each child → Places order on child's broker → Logs result → Notifies
```

The core loop is: **Detect → Replicate → Log → Notify**

---

## Feature Comparison

| Feature | Tradetron / AlgoTest | Ascentra (Us) | Status |
|---------|---------------------|---------------|--------|
| **AUTH & USERS** | | | |
| Email/Password login | ✅ | ✅ | Done |
| OTP login (phone) | ❌ (email only) | ✅ | Done |
| 2FA (TOTP) | ✅ | ✅ | Done |
| Role-based (Admin/Master/Child) | ✅ | ✅ | Done |
| **BROKER CONNECTIONS** | | | |
| Multi-broker support | ✅ (15+ brokers) | ✅ (5: Zerodha, Fyers, Upstox, Dhan, Groww) | Done |
| OAuth broker login | ✅ | ✅ | Done |
| Direct token login | ✅ | ✅ (Groww, Dhan) | Done |
| Broker dashboard (balance, positions) | ✅ | ✅ | Done |
| Connection signal/health | ❌ | ✅ | Done (we're ahead!) |
| **SUBSCRIPTION SYSTEM** | | | |
| Child subscribes to master | ✅ | ✅ | Done |
| Approval workflow | ❌ (auto) | ✅ (PENDING_APPROVAL) | Done (we're ahead!) |
| Scaling factor per child | ✅ | ✅ | Done |
| Pause/Resume copying | ✅ | ✅ | Done |
| Bulk subscribe/unsubscribe | ❌ | ✅ | Done (we're ahead!) |
| **TRADE ENGINE (CORE)** | | | |
| Auto-detect master trades | ✅ (polling/webhook) | ❌ | **NOT BUILT** |
| Real-time trade replication | ✅ | ❌ | **NOT BUILT** |
| Order scaling (qty × factor) | ✅ | ✅ (code exists, not wired) | Partial |
| Multi-broker order placement | ✅ | ✅ (placeOrder on all 5 brokers) | Done (API ready) |
| Trade logging | ✅ | ✅ (TradeLogService exists) | Done |
| Copy logs | ✅ | ✅ | Done |
| **RISK MANAGEMENT** | | | |
| Max loss per day | ✅ | ❌ | **MISSING** |
| Max trades per day | ✅ | ❌ | **MISSING** |
| Max position size | ✅ | ❌ | **MISSING** |
| Balance check before trade | ✅ | ✅ (BalanceAlertService) | Done |
| Stop-copy on drawdown | ✅ | ❌ | **MISSING** |
| **STRATEGY MARKETPLACE** | | | |
| Public master profiles | ✅ | ✅ (GET /child/masters) | Partial |
| Master performance stats (win rate, PnL) | ✅ | ❌ (returns 0s) | **MISSING** |
| Master ranking/leaderboard | ✅ | ❌ | **MISSING** |
| Strategy description/tags | ✅ | ❌ | **MISSING** |
| Subscription fees (master earns) | ✅ | ❌ (mock earnings) | **MISSING** |
| **NOTIFICATIONS & ALERTS** | | | |
| Trade executed notification | ✅ | ❌ (notification system exists, not wired to trades) | Partial |
| Low balance alert | ✅ | ✅ | Done |
| Session expired alert | ❌ | ✅ (signal API) | Done |
| **REAL-TIME** | | | |
| WebSocket trade updates | ✅ | ✅ (TradeUpdatesHub exists) | Partial |
| Live PnL streaming | ✅ | ❌ | **MISSING** |
| **ADMIN** | | | |
| User management | ✅ | ✅ | Done |
| System health | ✅ | ✅ | Done |
| Trade logs viewer | ✅ | ✅ | Done |
| Broker status monitoring | ✅ | ✅ | Done |

---

## What's WORKING Well (Our Strengths)

1. ✅ 5 live broker integrations with real API calls
2. ✅ Approval workflow (Tradetron doesn't have this)
3. ✅ Connection signal (mobile bars) — unique feature
4. ✅ Balance alert system
5. ✅ Bulk operations (subscribe/unsubscribe/link/unlink)
6. ✅ Dashboard API (all-in-one)
7. ✅ OTP login via SMS
8. ✅ Comprehensive admin panel

---

## What's MISSING (Critical Gaps)

### 🔴 P0 — Trade Engine (THE core feature, platform is useless without this)

The entire copy trading mechanism is not functional:

**Option A: Polling-Based (Simpler, recommended for MVP)**
```
Every 5-10 seconds:
  1. Fetch master's orders from broker API
  2. Compare with last known orders (detect new ones)
  3. For each new order → replicate to all active children
  4. Scale quantity by child's scalingFactor
  5. Place order on child's broker
  6. Log result + send notification
```

**Option B: Webhook/Event-Based (Better, needs Kafka or similar)**
```
Master places trade → Broker sends webhook → Our server receives it
→ Replicate to children → Log → Notify
```
Problem: Indian brokers (Zerodha, Fyers etc.) don't support webhooks for order events.

**Option C: Manual Trigger (Simplest)**
```
Master clicks "Copy This Trade" on frontend
→ API call with order details
→ Server replicates to all children
→ Log → Notify
```

**Recommendation: Start with Option A (polling) + Option C (manual) together.**

### 🔴 P0 — Risk Management

Before placing any copied trade, the system must check:
- Does child have enough margin?
- Has child hit daily loss limit?
- Has child hit max trades per day?
- Is position size within limits?

### 🟡 P1 — Master Performance Stats

Currently `/child/masters` returns `winRate: 0, totalTrades: 0`. Need to:
- Calculate real PnL from trade logs
- Calculate win rate from executed trades
- Show subscriber count
- Track performance over time (daily/weekly/monthly)

### 🟡 P1 — Trade Notifications

When a trade is copied, both master and child should get notifications:
- Master: "Your trade BUY RELIANCE ×10 was copied to 5 children"
- Child: "Trade copied: BUY RELIANCE ×10 from Master Aniket"
- Child: "Trade copy FAILED: Insufficient margin"

### 🟡 P2 — Master Leaderboard / Marketplace

- Rank masters by performance
- Show stats: total PnL, win rate, max drawdown, subscribers
- Allow filtering/sorting
- Strategy description, tags, risk level

### 🟡 P2 — Subscription Fees

- Master sets a monthly fee
- Child pays to subscribe
- Platform takes a cut
- Payment integration (Razorpay/Stripe)

### 🟢 P3 — Live PnL Streaming

- WebSocket endpoint for real-time PnL updates
- Position-level PnL tracking

---

## Recommended Build Order

```
Phase 1 (NOW — Make it work):
  1. Trade Copy Engine (polling + manual trigger)
  2. Risk checks before each trade
  3. Trade notifications (wired to notification system)

Phase 2 (NEXT — Make it useful):
  4. Real master performance stats
  5. Master leaderboard
  6. Strategy marketplace page

Phase 3 (LATER — Make money):
  7. Subscription fees + payments
  8. Live PnL streaming
  9. Advanced risk management (stop-copy, drawdown limits)
```

---

## Summary

**What we have:** A solid platform shell — auth, broker connections, subscriptions, dashboard, alerts, admin. All the plumbing is there.

**What we're missing:** The actual trade copying engine. Right now a child can subscribe to a master, but when the master trades, nothing happens on the child's account. This is THE feature that makes it a copy trading platform.

**Next step:** Build the trade copy engine (Phase 1). Everything else is secondary.
