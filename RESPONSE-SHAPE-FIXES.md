# Backend Response Shape Fixes — April 20, 2026

All fixes from the Frontend Requirements Doc have been applied. No API URL changes — only response shapes updated.

---

## 1. GET /api/v1/child/copied-trades — FIXED

Was returning trade-log format. Now returns proper copied-trade format.

```json
{
  "trades": [
    {
      "id": 1,
      "master": "sushil master account",
      "instrument": "RELIANCE",
      "type": "BUY",
      "masterQty": 10,
      "myQty": 10,
      "entry": 0,
      "current": 0,
      "ltp": 0,
      "pnl": 0,
      "time": "2026-04-20T09:32:00Z",
      "status": "EXECUTED"
    }
  ]
}
```

---

## 2. GET /api/v1/child/subscriptions — FIXED

Added missing fields for stat cards.

```json
{
  "subscriptions": [
    {
      "masterId": "uuid",
      "masterName": "sushil master account",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "subscribedAt": "2026-04-20T10:00:00Z",
      "brokerAccountId": "uuid",
      "pnl": 0,
      "totalPnL": 0,
      "tradesCopiedToday": 0,
      "allocation": 0,
      "allocationAmount": 0
    }
  ]
}
```

---

## 3. GET /api/v1/child/analytics — FIXED

Added all fields for P&L Dashboard.

```json
{
  "totalPnl": 0,
  "totalPnL": 0,
  "personalPnL": 0,
  "copiedPnL": 0,
  "masterPnL": 0,
  "personalTrades": 0,
  "copiedTrades": 14,
  "failedReplications": 1,
  "portfolioValue": 0,
  "winRate": 0,
  "activeMasters": 0,
  "pnlHistory": [
    { "time": "2026-04-16", "personal": 0, "copied": 0 },
    { "time": "2026-04-17", "personal": 0, "copied": 0 },
    { "time": "2026-04-18", "personal": 0, "copied": 0 },
    { "time": "2026-04-19", "personal": 0, "copied": 0 },
    { "time": "2026-04-20", "personal": 0, "copied": 0 }
  ],
  "personalTradesList": [],
  "masterPnlComparison": {
    "masterPnl": 0,
    "childPnl": 0,
    "replicationAccuracy": 0
  }
}
```

---

## 4. GET /api/v1/master/analytics — FIXED

Added all fields for Master Overview dashboard.

```json
{
  "totalPnl": 0,
  "winRate": 0,
  "totalTrades": 312,
  "totalReplications": 280,
  "totalChildren": 14,
  "totalFollowers": 14,
  "revenue": 0,
  "totalEarnings": 0,
  "subscriptionRevenue": 0,
  "performanceBonus": 0,
  "portfolioValue": 0,
  "earningsBreakdown": [
    { "name": "Subscription Fees", "value": 0 },
    { "name": "Performance Bonus", "value": 0 }
  ],
  "performanceChart": [
    { "date": "2026-03-30", "value": 100 },
    { "date": "2026-04-06", "value": 100 },
    { "date": "2026-04-13", "value": 100 },
    { "date": "2026-04-20", "value": 100 }
  ],
  "pnl": [],
  "childPerformance": [
    {
      "childId": "uuid",
      "scalingFactor": 1.0,
      "copyingStatus": "ACTIVE",
      "pnl": 0,
      "tradesCopied": 0
    }
  ]
}
```

---

## 5. GET /api/v1/pnl/summary — NO CHANGE NEEDED

Already returns correct shape:
```json
{
  "summary": [
    { "period": "today", "realizedPnl": 0, "unrealizedPnl": 0, "totalTrades": 0, "winRate": 0 }
  ]
}
```

---

## 6. Session Error Format — ALREADY FIXED

All broker endpoints return structured errors:
```json
{
  "error": "Session expired. Please re-login to Groww to continue.",
  "errorCode": "SESSION_EXPIRED",
  "action": "RE_LOGIN"
}
```

---

## 7. GET /api/v1/master/earnings — ALREADY FIXED

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

## 8. GET /api/v1/child/masters — ALREADY FIXED

```json
{
  "masters": [
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
  ]
}
```

---

## Note

All numeric values (pnl, winRate, revenue, etc.) return 0 because there's no real P&L calculation engine yet. The response shapes are correct — once real trade data flows through the system, these values will populate with actual numbers.
