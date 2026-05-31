# Broker Field & Status Mapping Reference

Single source of truth for how each supported broker (Zerodha, Upstox, Dhan, Groww, FYERS, Angel One)
names and encodes order fields and statuses. The Java classes
`com.copytrading.engine.BrokerStatusNormalizer` (inbound: raw broker status/side -> canonical) and
`com.copytrading.engine.BrokerFieldTranslator` (outbound: canonical -> broker-specific placement fields)
are built directly from the tables below. When a broker changes its API, update the table here and the
matching map entry in those two classes — nothing else should hardcode these values.

- Inbound (polling / postback): raw broker order -> `BrokerStatusNormalizer` -> `CanonicalOrderMapper` -> engine.
- Outbound (placement): canonical order -> `BrokerFieldTranslator` -> `CopyEngineService.placeOrderOnBroker` -> broker API.

---

## 1. Order Status

Canonical status used by the engine, and the raw value each broker returns. The engine only ever reasons
about the canonical value.

| Canonical | Terminal? | Zerodha | Upstox | Dhan | Groww | FYERS | Angel One |
|-----------|-----------|---------|--------|------|-------|-------|-----------|
| PUT ORDER REQ RECEIVED | No | PUT ORDER REQ RECEIVED | put order req received | TRANSIT (absorbs) | NEW/ACKED (absorbs) | 4 (Transit) | - |
| VALIDATION PENDING | No | VALIDATION PENDING | validation pending | TRANSIT (absorbs) | ACKED (absorbs) | 4 (Transit) | - |
| OPEN PENDING | No | OPEN PENDING | open pending | TRANSIT (absorbs) | APPROVED (absorbs) | 4 (Transit) | open pending |
| OPEN | No | OPEN | open | PENDING | - | 6 (Pending) | open |
| PART_TRADED | No | (OPEN + pending_qty>0) | - | PART_TRADED | - | - | - |
| TRIGGER PENDING | No | TRIGGER PENDING | trigger pending | PENDING (absorbs) | TRIGGER_PENDING | 6 (absorbs) | trigger pending |
| MODIFY VALIDATION PENDING | No | MODIFY VALIDATION PENDING | modify validation pending | - | MODIFICATION_REQUESTED | - | - |
| MODIFY PENDING | No | MODIFY PENDING | modify pending | - | - | - | modify pending |
| MODIFIED | No | MODIFIED | modified | - | - | - | modified |
| NOT MODIFIED | No | - | not modified | - | - | - | - |
| CANCEL PENDING | No | CANCEL PENDING | cancel pending | - | CANCELLATION_REQUESTED | - | - |
| NOT CANCELLED | No | - | not cancelled | - | - | - | - |
| AMO REQ RECEIVED | No | AMO REQ RECEIVED | after market order req received | - | - | - | after market order req received |
| MODIFY AMO REQ RECEIVED | No | - | modify after market order req received | - | - | - | modify after market order req received |
| CANCELLED AMO | Yes | (-> CANCELLED) | cancelled after market order | - | - | - | cancelled after market order |
| DELIVERY AWAITED | No | - | - | - | DELIVERY_AWAITED | - | - |
| COMPLETE | Yes | COMPLETE | complete | TRADED | COMPLETED / EXECUTED* | 2 (Traded) | complete |
| CANCELLED | Yes | CANCELLED | cancelled | CANCELLED | CANCELLED | 1 (Cancelled) | cancelled |
| REJECTED | Yes | REJECTED | rejected | REJECTED | REJECTED | 5 (Rejected) | rejected |
| EXPIRED | Yes | (-> CANCELLED) | (-> CANCELLED) | EXPIRED | - | 7 (Expired) | (-> CANCELLED) |
| FAILED | Yes | - | - | - | FAILED | - | - |
| TRIGGERED (Super Order) | No | - | - | TRIGGERED | - | - | - |
| CLOSED (Super Order) | Yes | - | - | CLOSED | - | - | - |

\* Groww `EXECUTED` can be a partial fill. `COMPLETED` is the fully-settled terminal. See the partial-fill rule below.

### FYERS integer status codes
FYERS is the only broker that returns status as an integer:
`1 -> CANCELLED`, `2 -> COMPLETE`, `3 -> reserved`, `4 -> TRANSIT`, `5 -> REJECTED`, `6 -> OPEN/PENDING`, `7 -> EXPIRED`.

### Partial-fill rule (important)
Some brokers report a terminal-looking status (`EXECUTED`, `COMPLETE`, `TRADED`, `2`) while the order is only
partially filled. An order must NOT be treated as a closeable/terminal fill until `filledQty == tradedQty`.
Until then, keep polling. Implemented in `OrderNormalizer.isFullyFilled(...)` / `shouldProcessForCopy(...)`.

### Sources
- Zerodha: https://kite.trade/docs/connect/v3/orders/#order-statuses
- Upstox: https://upstox.com/developer/api-documentation/appendix/order-status
- Dhan: https://dhanhq.co/docs/v2/annexure/#order-status
- Groww: https://groww.in/trade-api/docs/curl/annexures
- FYERS: https://myapi.fyers.in/docsv3 (Appendix / status codes)
- Angel One: https://smartapi.angelone.in/smartapi/forum/topic/4041

---

## 2. Order Type

| Broker | Field name | Market | Limit | SL (limit) | SL-M (market) |
|--------|------------|--------|-------|------------|---------------|
| Zerodha | `order_type` | MARKET | LIMIT | SL | SL-M |
| Upstox | `order_type` | MARKET | LIMIT | SL | SL-M |
| Dhan | `orderType` | MARKET | LIMIT | STOP_LOSS | STOP_LOSS_MARKET |
| Groww | `order_type` | MARKET | LIMIT | SL | SL_M |
| FYERS | `type` (int) | 2 | 1 | 4 | 3 |
| Angel One | `ordertype` | MARKET | LIMIT | STOPLOSS_LIMIT | STOPLOSS_MARKET |

Notes: Groww SL-M is `SL_M` (underscore). FYERS integers: `1=Limit, 2=Market, 3=Stop/SL-M, 4=Stoplimit/SL-L`.

Sources: Zerodha/Upstox/Dhan/Groww/FYERS/Angel One order docs (see links in section 1 plus
https://myapi.fyers.in/docsv3#tag/Appendix/Order-Types and https://smartapi.angelone.in/docs/Orders).

---

## 3. Product Type

| Broker | Field name | Cash & Carry | Intraday | Carry-fwd (F&O) | MTF |
|--------|------------|--------------|----------|------------------|-----|
| Zerodha | `product` | CNC | MIS | NRML | MTF |
| Upstox | `product` | D | I | D | MTF |
| Dhan | `productType` | CNC | INTRADAY | MARGIN | - |
| Groww | `product` | CNC | MIS | NRML | - |
| FYERS | `productType` | CNC | INTRADAY | MARGIN | MTF |
| Angel One | `producttype` | DELIVERY | INTRADAY | CARRYFORWARD | MARGIN |

Notes: Upstox uses single letters `D`/`I`; F&O carry uses `D`. Angel One uses `DELIVERY`/`CARRYFORWARD`.
Dhan F&O carry-forward is `MARGIN`. Canonical product codes inside the engine are `MIS` / `CNC` / `NRML`.

---

## 4. Exchange Codes

| Broker | Equity NSE | Equity BSE | F&O NSE | F&O BSE | Currency | Commodity |
|--------|-----------|-----------|---------|---------|----------|-----------|
| Zerodha | NSE | BSE | NFO | BFO | CDS, BCD | MCX |
| Upstox | NSE | BSE | NFO | BFO | CDS, BCD | MCX |
| Dhan | NSE_EQ | BSE_EQ | NSE_FNO | BSE_FNO | NSE_CURRENCY, BSE_CURRENCY | MCX_COMM |
| Groww | NSE | BSE | NSE | BSE | - | MCX (rolling) |
| FYERS | NSE | BSE | NSE | BSE | NSE (CDS) | MCX |
| Angel One | NSE | BSE | NFO | BFO | CDS | MCX |

Notes: FYERS encodes the exchange as a symbol prefix (`NSE:`, `BSE:`). Dhan calls these "exchangeSegment".
Angel One BSE equity is `BSE` and BSE F&O is `BFO` (do not hardcode `NSE`/`NFO`).

---

## 5. Validity field name

| Broker | Field name | Value |
|--------|------------|-------|
| Zerodha | `validity` | DAY |
| Upstox | `validity` | DAY |
| Dhan | `validity` | DAY |
| Groww | `validity` | DAY |
| FYERS | `validity` | DAY |
| Angel One | `duration` | DAY |

Note: Angel One uses `duration` (values DAY/IOC). FYERS v3 uses `validity` (DAY/IOC) — verified against the
official `fyers-apiv3` SDK and the `/api/v3/orders/sync` body; the earlier `orderValidity` assumption was incorrect.

---

## 6. Rate Limits — order placement (place / modify / cancel)

| Broker | Per sec | Per min | Per 30 min | Per hour | Per day | Max mods/order | Enforced at |
|--------|---------|---------|-----------|----------|---------|----------------|-------------|
| Zerodha | 10 | 400 | - | - | 5,000 | 25 | API key |
| Upstox | 10 | 500 | 2,000 | - | - | - | per API/user |
| Dhan | 10 | 250 | - | 1,000 | 7,000 | 25 | per user |
| Groww | 10 | 250 | - | - | - | - | per user (Orders pool) |
| FYERS | 10 | 200 | - | - | 100,000 (global) | - | per user |
| Angel One | 10 | 500 | - | 1,000 | - | - | per user |

Notes: Upstox window is per-30-min (not per-hour). Zerodha limits are per API key; others per user.
The child-order rate limiter uses the conservative `Per sec` (and `Per min`) values from this table.

Sources:
- Zerodha: https://kite.trade/docs/connect/v3/exceptions/
- Upstox: https://upstox.com/developer/api-documentation/rate-limiting/
- Dhan: https://dhanhq.co/docs/v2/
- Groww: https://groww.in/trade-api/docs/curl#rate-limits
- FYERS: https://myapi.fyers.in/docsv3
- Angel One: https://smartapi.angelone.in/smartapi/forum/topic/4387
