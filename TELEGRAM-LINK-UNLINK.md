# Telegram — Link & Unlink Guide

**Bot:** [@Copy_tradingsBot](https://t.me/Copy_tradingsBot)  
**API base:** `https://api.ascentracapital.com/api/v1`

Each user links **their own** Telegram. Alerts go only to the account that sent `/link CODE` while logged in as that user.

---

## Link (connect)

1. Log in to the Ascentra app (**Master** or **Child**).
2. Go to **Profile** → **Connect Telegram** → **Generate code**.
3. Open Telegram → search **@Copy_tradingsBot** → tap **Start**.
4. Send (replace with your code):
   ```text
   /link 123456
   ```
   Or: `/start link_123456`
5. Bot replies **Linked** → in the app tap **Refresh** (or wait ~3s).
6. Optional: **Send test** in the app.

**Code expires in 10 minutes.** Generate a new code if it expires.

---

## Unlink (disconnect)

### In the app

**Profile** → **Telegram** → **Disconnect** / **Unlink**

### API

```http
POST /api/v1/notifications/telegram/unlink
Authorization: Bearer <accessToken>
```

---

## API reference

| Action | Method | Path | Auth |
|--------|--------|------|------|
| Bot info | GET | `/notifications/telegram/bot` | No |
| Generate code | POST | `/notifications/telegram/generate-link-token` | Yes |
| Check status | GET | `/notifications/telegram/status` | Yes |
| Update preferences | PUT | `/notifications/telegram/preferences` | Yes |
| Test message | POST | `/notifications/telegram/test` | Yes |
| Unlink | POST | `/notifications/telegram/unlink` | Yes |

---

## Example responses

**Generate code (`POST .../generate-link-token`):**

```json
{
  "code": "847291",
  "expiresAt": "2026-05-26T07:31:05Z",
  "botUsername": "Copy_tradingsBot",
  "botLink": "https://t.me/Copy_tradingsBot",
  "deepLink": "https://t.me/Copy_tradingsBot?start=link_847291",
  "instruction": "Open @Copy_tradingsBot in Telegram and send: /link 847291"
}
```

**Status (`GET .../status`):**

```json
{
  "linked": true,
  "chatId": "123456789",
  "botUsername": "Copy_tradingsBot",
  "botLink": "https://t.me/Copy_tradingsBot",
  "preferences": {
    "tradeAlerts": true,
    "riskAlerts": true,
    "dailySummary": true,
    "systemAlerts": false,
    "alertOnSuccess": true,
    "alertOnFailure": true,
    "alertOnSkipped": true
  }
}
```

---

## curl (EC2 / testing)

```bash
RESP=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"YOUR_EMAIL","password":"YOUR_PASSWORD"}')
TOKEN=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Generate code → then /link CODE in Telegram
curl -s -X POST http://localhost:8081/api/v1/notifications/telegram/generate-link-token \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Status
curl -s http://localhost:8081/api/v1/notifications/telegram/status \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Test
curl -s -X POST http://localhost:8081/api/v1/notifications/telegram/test \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Unlink
curl -s -X POST http://localhost:8081/api/v1/notifications/telegram/unlink \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Bot does not reply | Server needs HTTPS webhook; `TELEGRAM_ENABLED=true` |
| Invalid / expired code | Generate new code in app (10 min TTL) |
| Test `sent: false` | Link first with `/link CODE` |
| Wrong bot in UI | Use `GET /notifications/telegram/bot` — do not hardcode |

---

## Related docs

- [TELEGRAM-SETUP.md](./TELEGRAM-SETUP.md) — EC2 env, webhook, ops
- [FE-CURRENT-INTEGRATION.md](./FE-CURRENT-INTEGRATION.md) — section 8 (FE integration)
