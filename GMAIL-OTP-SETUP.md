# Email + phone OTP (login + 2FA)

Replaces **TOTP / QR** with **6-digit codes** via **EMAIL** (Gmail SMTP) or **PHONE** (Twilio SMS).

## 2FA channels

| Channel | Delivery | Config |
|---------|----------|--------|
| `EMAIL` | Gmail SMTP | `MAIL_USERNAME`, `MAIL_PASSWORD` |
| `PHONE` | Twilio SMS | `TWILIO_*` env vars |

User picks one channel when enabling 2FA. Login OTP uses that channel.

## Login flow (when 2FA enabled)

1. `POST /api/v1/auth/login` — `{ "email", "password" }`
2. If `requires2FA: true` → read `twoFactorChannel` (`EMAIL` or `PHONE`) and show OTP screen
3. `POST /api/v1/auth/verify-login-otp` — `{ "email", "otp" }`
4. Response: `accessToken`, `refreshToken`, `user`

If 2FA **disabled** → login returns tokens immediately (no OTP).

Resend: `POST /api/v1/auth/send-login-otp` — `{ "email" }`

## Settings 2FA (no QR)

| Action | API |
|--------|-----|
| Options | `GET /api/v1/auth/2fa/options` — EMAIL / PHONE availability |
| Enable | `POST /api/v1/auth/2fa/enable` — `{ "channel": "EMAIL" \| "PHONE" }` |
| Confirm | `POST /api/v1/auth/2fa/verify` — `{ "otp" }` |
| Disable | `DELETE /api/v1/auth/2fa/disable` — `{ "password", "otp" }` |

## EC2 environment (Gmail app password)

Use a [Google App Password](https://myaccount.google.com/apppasswords) (2FA on Google account required):

```bash
export MAIL_HOST=smtp.gmail.com
export MAIL_PORT=587
export MAIL_USERNAME=your@gmail.com
export MAIL_PASSWORD=your-16-char-app-password
export MAIL_FROM=your@gmail.com
```

Add to `~/.bashrc`, then restart Java.

## Dev without SMTP

If `MAIL_USERNAME` is empty, OTP is **logged only** (`EMAIL_OTP (dev) to=... otp=123456`).

## Twilio SMS

Phone login (`/send-otp`, `/verify-otp`) is unchanged (Twilio). Email OTP is for **email/password login**.
