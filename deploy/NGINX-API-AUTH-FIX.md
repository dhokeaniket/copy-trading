# Fix browser “Sign in” popup (HTTP Basic Auth) on api.ascentracapital.com

## What your curl shows

| URL | Status | Meaning |
|-----|--------|---------|
| `GET /health` | **200** | Backend is up behind nginx |
| `GET /api/v1/child/pnl-dashboard` (no token) | **401** | Route exists; Spring requires JWT |
| `GET /api/v1/brokers` (no token) | **401** | Same — expected |

**401 without `Authorization: Bearer ...` is correct.**  
The P&L dashboard is **not** broken because of 401 in this test — you did not send a token.

The browser popup appears only when the response includes:

```http
WWW-Authenticate: Basic realm="..."
```

Spring is configured with **httpBasic disabled** and does **not** send that header. If the popup still appears, **nginx** (or another proxy) is adding Basic Auth.

---

## Step 1 — Confirm on EC2

```bash
# No token — expect 401, check for WWW-Authenticate
curl -sv "https://api.ascentracapital.com/api/v1/brokers" 2>&1 | grep -iE 'HTTP/|www-authenticate'

# With token — expect 200
TOKEN="paste_accessToken_from_login"
curl -sv "https://api.ascentracapital.com/api/v1/brokers" \
  -H "Authorization: Bearer $TOKEN" 2>&1 | grep -iE 'HTTP/|www-authenticate'

curl -sv "https://api.ascentracapital.com/api/v1/child/pnl-dashboard" \
  -H "Authorization: Bearer $TOKEN" 2>&1 | grep -iE 'HTTP/|www-authenticate'
```

- If **WWW-Authenticate: Basic** appears → fix nginx (Step 2).
- If **no** WWW-Authenticate but 401 → FE must send Bearer token (Step 3).
- With valid token you should get **200** and JSON body.

---

## Step 2 — Remove nginx `auth_basic` on API

On EC2:

```bash
sudo grep -r "auth_basic" /etc/nginx/
sudo cat /etc/nginx/nginx.conf
sudo cat /etc/nginx/conf.d/*.conf
```

**Remove or comment out** lines like:

```nginx
auth_basic "Restricted";
auth_basic_user_file /etc/nginx/.htpasswd;
```

from any `location` that proxies to Spring (`/api/`, `api.ascentracapital.com`, etc.).

**Correct pattern** — proxy only, no Basic Auth:

```nginx
server {
    listen 443 ssl;
    server_name api.ascentracapital.com;

    # SSL certs ...

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # NO auth_basic here
    }
}
```

Then:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

---

## Step 3 — Frontend must send JWT

Every protected call needs:

```http
Authorization: Bearer <accessToken>
```

Login first:

```bash
curl -s -X POST "https://api.ascentracapital.com/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@gmail.com","password":"admin@123"}'
```

Use `accessToken` from response on all `/api/v1/child/*`, `/api/v1/brokers/*`, etc.

**Common FE mistakes:**
- Calling `https://api.ascentracapital.com/...` without `Authorization` header
- Token expired (~15 min) → refresh or re-login
- Using `http://13.53.246.13:8081` in dev but `https://api.ascentracapital.com` in prod without updating token/CORS

---

## Step 4 — Deploy latest JAR (for `/child/pnl-dashboard`)

If FE still gets **404**, EC2 may be on an old build. On EC2:

```bash
cd /home/ec2-user/copy-trading
git pull origin feature/backend_code
./gradlew clean build -x test
# restart backend (see scripts/start-backend.sh)
```

`/api/v1/child/pnl-dashboard` exists in current code (alias of `/child/analytics`).

---

## Login + 2FA / email OTP (product flow)

| Flow | API |
|------|-----|
| Email + password | `POST /api/v1/auth/login` |
| If `requires2FA: true` (TOTP app) | `POST /api/v1/auth/2fa/verify` with `{ "otp": "123456" }` |
| Phone OTP login | `POST /api/v1/auth/send-otp` + `verify-otp` |
| Enable TOTP 2FA (settings) | `POST /api/v1/auth/2fa/enable` → scan QR → `2fa/verify` |
| Disable 2FA | `DELETE /api/v1/auth/2fa/disable` |

**Email OTP on login (Gmail)** is not in the backend yet — only **SMS OTP** (Twilio Verify) and **TOTP** (authenticator app). To add “password → OTP to email → login”, backend needs `POST /auth/send-email-otp` + login step change (separate task).

---

## Quick checklist

- [ ] `curl -sv /api/v1/brokers` — no `WWW-Authenticate: Basic`
- [ ] nginx has no `auth_basic` on API `location`
- [ ] FE sends `Authorization: Bearer ...` on every protected request
- [ ] Latest JAR deployed on EC2
- [ ] Login works: `POST /api/v1/auth/login`
