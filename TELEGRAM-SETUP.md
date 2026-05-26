# Telegram Notifications — Setup & User Guide

**Bot:** [@Copy_tradingsBot](https://t.me/Copy_tradingsBot)

---

## Security warning

If a bot token was shared in chat, email, or git:

1. Open [@BotFather](https://t.me/BotFather) → `/mybots` → your bot → **Revoke** token  
2. Generate a **new** token  
3. Set only on the server as environment variable — **never** commit to git  

---

## How a user joins Telegram (end user)

1. **Log in** to the Ascentra app (Child or Master).  
2. Go to **Profile** → **Connect Telegram** (or Notifications).  
3. Tap **Generate link code**.  
4. App shows a **6-digit code** and bot name `@Copy_tradingsBot`.  
5. User opens Telegram → search **@Copy_tradingsBot** → **Start**.  
6. User sends:
   ```text
   /link 847291
   ```
   (replace with their code)  
7. Bot replies: **“Linked to Ascentra”**.  
8. In the app, tap **Refresh** or call **GET** `/api/v1/notifications/telegram/status` → `"linked": true`.  
9. Optional: **Send test** in app → `POST /api/v1/notifications/telegram/test`.

### Alternative: deep link (FE)

API returns:

```json
{
  "code": "847291",
  "botUsername": "Copy_tradingsBot",
  "botLink": "https://t.me/Copy_tradingsBot",
  "deepLink": "https://t.me/Copy_tradingsBot?start=link_847291",
  "instruction": "Open @Copy_tradingsBot in Telegram and send: /link 847291"
}
```

FE can open `botLink` or `deepLink`; user still must send `/link CODE` unless you add auto-handler for `start=link_*` (backend supports `/start link_XXXXXX`).

---

## Server configuration (EC2)

On the server, set environment variables (do **not** put tokens in `application.yml` in git):

```bash
export TELEGRAM_BOT_TOKEN='your-new-token-from-botfather'
export TELEGRAM_BOT_USERNAME='Copy_tradingsBot'
export TELEGRAM_ENABLED='true'
export TELEGRAM_WEBHOOK_BASE_URL='https://YOUR_PUBLIC_API_HOST'
```

Restart the app:

```bash
cd /home/ec2-user/copy-trading
./gradlew clean build -x test --no-daemon
killall java; sleep 3
nohup java -Xmx512m -jar build/libs/copy-trading-backend-0.1.0.jar > /home/ec2-user/ascentra.log 2>&1 &
```

### Register Telegram webhook (required for /link)

If `TELEGRAM_WEBHOOK_BASE_URL` is set (e.g. `https://api.ascentracapital.com`), the app **registers the webhook automatically** on startup at `{base}/api/v1/telegram/webhook`.

Manual registration (optional):

```bash
curl -s "https://api.telegram.org/botTOKEN/setWebhook" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://YOUR_API_HOST/api/v1/telegram/webhook","allowed_updates":["message"]}'
```

Telegram **requires HTTPS**. A bare `http://IP:8081` URL will not work — use a domain with TLS.

Verify:

```bash
curl -s "https://api.telegram.org/botTOKEN/getWebhookInfo"
```

---

## API endpoints (for frontend)

| Step | Method | Path |
|------|--------|------|
| Generate code | POST | `/api/v1/notifications/telegram/generate-link-token` |
| Check linked | GET | `/api/v1/notifications/telegram/status` |
| Preferences | PUT | `/api/v1/notifications/telegram/preferences` |
| Test message | POST | `/api/v1/notifications/telegram/test` |
| Unlink | POST | `/api/v1/notifications/telegram/unlink` |

Webhook (Telegram → backend, **public**): `POST /api/v1/telegram/webhook`

---

## What alerts are sent automatically

After linking, the backend sends Telegram messages when:

- A trade is copied successfully  
- A copy fails  
- Session expires / market closed skip (if configured)  

Uses `users.telegram_chat_id` in the database (set by `/link` flow).

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Bot does not reply to `/link` | Webhook not set or not HTTPS; check `getWebhookInfo` |
| `sent: false` on test | `TELEGRAM_ENABLED=false` or invalid token; user must `/link` first |
| Code expired | Codes last **10 minutes** — generate new code in app |
| Wrong bot name in app | Set `TELEGRAM_BOT_USERNAME=Copy_tradingsBot` (no `@`) |

---

## Related docs

- [FE-INTEGRATION-GUIDE.md](./FE-INTEGRATION-GUIDE.md) — frontend API details  
- [GAP-DOCS-CORRECTIONS.md](./GAP-DOCS-CORRECTIONS.md) — fixes to external gap analysis MD files  
