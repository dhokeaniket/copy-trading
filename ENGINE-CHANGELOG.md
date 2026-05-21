# Copy Engine Changelog

## Production fixes (main)

### Copy path
- Poll only after **filled** orders (not while OPEN).
- Upstox orders API: `/v2/order/retrieve-all`.
- Product map: Upstox `D`→CNC, `I`→MIS; skip intraday after 15:20 IST (`MARKET_CLOSED`).

### Engine audit
- **Canonical orders**: `CanonicalOrder` + `CanonicalOrderMapper` for all broker → copy paths.
- **Symbol translation** uses master broker (not hardcoded Groww).
- **SELL guard**: live positions + `copySides` (`BUY_ONLY`, `BUY_AND_SELL`, `MIRROR`).
- **F&O**: futures classification, lot rounding, `SUB_LOT_SIZE` skip.
- **Risk**: `maxCapitalExposure` margin % enforced when enabled.
- **Security**: AES-GCM token encryption + startup migration for existing plaintext tokens.

### API for frontend
- `GET /api/v1/engine/metadata` — skip reasons, copy sides options.
- `GET /api/v1/risk/status?brokerAccountId=` — live utilization dashboard.
- `PATCH /api/v1/child/subscriptions/copy-settings` — update copy sides / short selling.
- Subscriptions list includes `copySides`, `allowShortSelling`.
