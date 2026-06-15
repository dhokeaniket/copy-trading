# Backend Changes — FE Integration Doc (Jun 3, 2026)

## 1. Price Tolerance (NEW FEATURE)

**What:** Child can set a price tolerance % per subscription. When copying LIMIT/SL orders, the child's order price is adjusted by ±tolerance% to handle slippage from latency.

**Why:** Between master's order execution and child's copy placement (100-500ms), price can move. Tolerance gives the child's limit order room to fill instead of getting rejected.

**How it works:**
- Master LIMIT BUY at ₹100, child tolerance = 2% → child order placed at ₹102
- Master LIMIT SELL at ₹100, child tolerance = 2% → child order placed at ₹98
- MARKET orders → no adjustment (fills at market price regardless)
- Default: 2%, Range: 0–10%, Per subscription (different per master)

**API — Read:**
```
GET /api/v1/child/subscriptions
→ each subscription includes: "priceTolerancePct": 2.0
```

**API — Update:**
```
PATCH /api/v1/child/subscriptions/copy-settings
Body: { "masterId": "uuid", "priceTolerancePct": 3.0 }
Response: { "masterId": "...", "copySides": "BUY_ONLY", "allowShortSelling": false, "priceTolerancePct": 3.0, "message": "Copy settings updated" }
```

**FE UI needed:** Slider or number input (0–10, step 0.5) in child subscription settings panel alongside copySides and scalingFactor. Label: "Price Tolerance %". Helper: "Adjusts limit order price by ±X% to handle slippage. Default 2%. Set 0 for exact master price."

---

## 2. Order Book (FIX)

**Before:** Only showed open/pending orders (filtered out completed/cancelled).
**Now:** Shows ALL today's orders — completed, cancelled, rejected, open, everything.

**FE impact:** None. Response shape unchanged, just more data returned.

---

## 3. Followers List (FIX)

**Before:** Master could see itself as a follower. Unlinked (INACTIVE) followers still appeared.
**Now:** Master's own ID excluded. Only ACTIVE + PAUSED + PENDING_APPROVAL shown.

**FE impact:** None. Same response shape, correct data.

---

## 4. Option Status (FIX)

**Before:** Showed SKIPPED/FAILED non-option trades mixed in.
**Now:** Strict filter — only actual option symbols (CE/PE/FUT/OPT) shown.

**FE impact:** None.

---

## 5. Copy Logs — New Fields

Timeline, copied-trades, and master copy-logs responses now include 4 new fields:

```json
{
  "product": "MIS",
  "orderType": "LIMIT",
  "price": 245.50,
  "triggerPrice": null,
  ... (existing fields unchanged)
}
```

**FE impact:** Optional — display these in trade detail views if desired. Won't break existing parsing (additive fields).

---

## 6. Admin Master-Child Map (NEW ENDPOINT)

```
GET /api/v1/admin/master-child-map
```

**Response:**
```json
{
  "masters": [
    {
      "masterId": "uuid",
      "masterName": "John",
      "masterEmail": "john@email.com",
      "children": [
        { "childId": "uuid", "name": "Alice", "email": "alice@email.com", "status": "ACTIVE", "scalingFactor": 1.0 }
      ]
    }
  ],
  "total": 2
}
```

**FE impact:** New admin page/table showing all master-child relationships.

---

## 7. Dashboard Shows 0 — Expected When No Broker Connected

P&L, trades copied, positions all come from:
- **DB** (trades + copy_logs tables) → persists after broker disconnects
- **Live broker API** → only when broker session is active

If broker is not connected OR no trades have been copied yet, values are correctly 0. Not a bug. Once broker is connected and copy trading starts, numbers populate and persist.

---

## Summary of FE Work Needed

| Item | FE Change Required |
|------|-------------------|
| Price Tolerance | YES — add slider/input in child copy settings |
| Order Book fix | No |
| Followers fix | No |
| Option Status fix | No |
| Copy Logs new fields | Optional — show product/orderType/price if desired |
| Admin master-child-map | YES — new admin page/table |
| Dashboard 0 values | No — expected behavior |
