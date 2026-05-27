# Latest: Broker disconnect, reconnect & notifications (May 2026)

Summary of backend + docs changes for **all brokers** (Groww, Dhan, Zerodha, Fyers, Upstox, Angel One).

---

## What changed

### 1. Disconnect & reconnect (all brokers)

| Before | After |
|--------|--------|
| Only Groww had partial reconnect hints | Every broker returns full `loginOptions` after disconnect |
| Reconnect UI often showed one method only | Same options as first-time link (e.g. Groww: token **and** API key + TOTP) |
| No dedicated disconnect API | `POST .../disconnect` clears session and returns login config |

### 2. New API endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/v1/brokers/accounts/{accountId}/disconnect` | End session, clear stored creds where needed, return `loginOptions` + push notification |
| `GET` | `/api/v1/brokers/accounts/{accountId}/login-options` | Full login UI config for reconnect |

### 3. Enhanced existing endpoints

| Endpoint | Change |
|----------|--------|
| `GET .../status` | Includes `loginOptions` when `sessionActive` is false or margin check fails |
| `GET .../oauth-url` | Returns full `loginOptions` + `oauthUrl` for OAuth brokers (not only Groww message) |
| `PUT .../accounts/{id}` | Encrypts apiKey/apiSecret on update |
| `PUT .../token` | Encrypts access token on save |

### 4. Notifications (bell + live WebSocket)

| Before | After |
|--------|--------|
| Notifications saved to DB only | Every `NotificationService.push()` also broadcasts on `/ws/notifications` |
| No disconnect notification | `BROKER_DISCONNECTED` on manual disconnect |
| — | `BROKER_RECONNECT_REQUIRED` added to engine metadata types |

**WebSocket payload example:**

```json
{
  "event": "NOTIFICATION",
  "data": {
    "id": "uuid",
    "type": "BROKER_DISCONNECTED",
    "title": "Broker disconnected",
    "message": "Your Groww account was disconnected. Tap Reconnect to sign in again.",
    "accountId": "uuid",
    "brokerId": "GROWW",
    "brokerName": "Groww",
    "action": "RECONNECT",
    "loginOptionsUrl": "/api/v1/brokers/accounts/{id}/login-options"
  }
}
```

### 5. Login options per broker

| Broker | `loginOptions` |
|--------|----------------|
| Groww | `accessToken`, `apiKeyWithTotp` |
| Dhan | `accessToken`, `oauth` |
| Zerodha | `oauth` |
| Fyers | `oauth` |
| Upstox | `oauth` |
| Angel One | `totp` |

---

## Files changed

| File | Change |
|------|--------|
| `BrokerAccountService.java` | Disconnect, login-options, shared `loginConfigForBroker`, notifications on disconnect |
| `BrokerController.java` | New routes: `disconnect`, `login-options` |
| `NotificationService.java` | WebSocket broadcast on every push |
| `CopyEngineController.java` | New notification types in `/engine/metadata` |
| `BROKER-CONNECTION-FLOW.md` | Reconnect section for all brokers |
| `BROKER-DISCONNECT-NOTIFICATIONS.md` | **New** — FE guide for bell + WebSocket + UI mapping |

---

## Frontend checklist

- [ ] **Disconnect:** `POST /api/v1/brokers/accounts/{id}/disconnect` (prefer over DELETE when user may reconnect)
- [ ] **Reconnect:** `GET .../login-options` — render **all** `loginOptions` tabs (same as `GET /api/v1/brokers`)
- [ ] **WebSocket:** connect to `wss://api.ascentracapital.com/ws/notifications` (or `ws://host:8081/ws/notifications`)
- [ ] On `event: NOTIFICATION` + `type: BROKER_DISCONNECTED` → toast + Reconnect CTA → open login modal using `loginOptionsUrl`
- [ ] On `event: SESSION_EXPIRED` → separate banner (copy engine / token expired during trading)
- [ ] **Bell:** `GET /api/v1/notifications` for inbox; `PATCH .../{id}/read` when dismissed

---

## Quick test (curl)

```bash
# 1. Disconnect
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  https://api.ascentracapital.com/api/v1/brokers/accounts/$ACCOUNT_ID/disconnect | jq .

# 2. Login options
curl -s -H "Authorization: Bearer $TOKEN" \
  https://api.ascentracapital.com/api/v1/brokers/accounts/$ACCOUNT_ID/login-options | jq .

# 3. Notifications
curl -s -H "Authorization: Bearer $TOKEN" \
  https://api.ascentracapital.com/api/v1/notifications | jq .
```

---

## Related docs

- [BROKER-DISCONNECT-NOTIFICATIONS.md](./BROKER-DISCONNECT-NOTIFICATIONS.md) — notification display (REST + WebSocket)
- [BROKER-CONNECTION-FLOW.md](./BROKER-CONNECTION-FLOW.md) — link & login flows per broker
- [FE-INTEGRATION-GUIDE.md](./FE-INTEGRATION-GUIDE.md) — general FE integration

---

## Deploy (EC2)

```bash
cd /home/ec2-user/copy-trading && git pull origin feature/backend_code
./gradlew clean build -x test --no-daemon
bash scripts/start-backend.sh
```
