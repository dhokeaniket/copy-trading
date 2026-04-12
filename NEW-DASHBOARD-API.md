# NEW: Broker Dashboard API

> Single API to fetch all demat account details after broker connection.

## Endpoint

```
GET /api/v1/brokers/accounts/{accountId}/dashboard
```

**Auth:** `Authorization: Bearer <accessToken>`

## When to Call

After broker login succeeds (`SESSION_ACTIVE`):

```
1. POST /brokers/accounts              → Link (get accountId)
2. POST /brokers/accounts/{id}/login   → Login (get SESSION_ACTIVE)
3. GET  /brokers/accounts/{id}/dashboard → Fetch everything ✅
```

## Response

```json
{
  "accountId": "60120a19-23b8-4f6a-8007-81c2054a509e",
  "brokerId": "ZERODHA",
  "brokerName": "Zerodha",
  "clientId": "DRX617",
  "nickname": "My Zerodha",
  "status": "ACTIVE",
  "sessionActive": true,

  "profile": {
    "name": "Aniket Dhoke",
    "email": "aniket@example.com",
    "clientId": "DRX617",
    "broker": "Zerodha",
    "exchanges": ["NSE", "BSE", "NFO"],
    "products": ["CNC", "MIS", "NRML"]
  },

  "margin": {
    "availableMargin": 75000.50,
    "usedMargin": 25000.00,
    "totalFunds": 100000.50,
    "collateral": 0
  },

  "positions": [
    {
      "tradingsymbol": "RELIANCE",
      "quantity": 10,
      "average_price": 2500.0,
      "pnl": 150.0
    }
  ],

  "holdings": [
    {
      "tradingsymbol": "TCS",
      "quantity": 5,
      "average_price": 3500.0,
      "last_price": 3600.0
    }
  ],

  "orders": [
    {
      "order_id": "123456789",
      "tradingsymbol": "RELIANCE",
      "transaction_type": "BUY",
      "quantity": 10,
      "status": "COMPLETE"
    }
  ]
}
```

## Fields

| Field | Type | Description |
|-------|------|-------------|
| profile | object | Broker user info — name, email, clientId, broker name |
| margin | object | availableMargin, usedMargin, totalFunds, collateral |
| positions | array | Open intraday/F&O positions |
| holdings | array | Long-term stock holdings |
| orders | array | Today's orders |

## Profile Fields by Broker

| Broker | Extra Fields |
|--------|-------------|
| Zerodha | exchanges, products |
| Fyers | pan |
| Upstox | exchanges |
| Groww | — |
| Dhan | — |

All brokers return: `name`, `email`, `clientId`, `broker`

## Error Handling

If a section fails, it returns `error` instead of data (rest still works):

```json
{
  "profile": { "name": "Aniket", "broker": "Zerodha" },
  "margin": { "availableMargin": 75000 },
  "positions": [],
  "holdings": { "error": "Zerodha API timeout" },
  "orders": []
}
```

If session expired:
```json
{ "status": 400, "message": "No active broker session. Login first." }
```

## Individual Endpoints (if needed separately)

| Endpoint | Returns |
|----------|---------|
| `GET /brokers/accounts/{id}/margin` | Balance only |
| `GET /brokers/accounts/{id}/positions` | Positions only |
| `GET /brokers/accounts/{id}/holdings` | Holdings only |
| `GET /brokers/accounts/{id}/orders` | Orders only |
| `GET /brokers/accounts/{id}/trades` | Trades only |
| `GET /brokers/accounts/{id}/test` | Connection health check |
