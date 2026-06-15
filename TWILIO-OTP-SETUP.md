# Twilio OTP setup (replaces AWS SNS)

OTP uses **Twilio Verify** — Twilio generates and validates the 6-digit code (no AWS SNS).

## EC2 environment variables

Add to `/home/ec2-user/.bashrc` or systemd env (values from your Twilio guide — **do not commit to git**):

```bash
export TWILIO_ACCOUNT_SID="ACxxxxxxxx"
export TWILIO_API_KEY="SKxxxxxxxx"
export TWILIO_API_SECRET="your-api-key-secret"
export TWILIO_VERIFY_SERVICE_SID="VAxxxxxxxx"
export TWILIO_DEFAULT_COUNTRY_CODE="91"
```

Then restart the backend:

```bash
source ~/.bashrc
# restart java process
```

On startup you should see: `TWILIO_OTP_ENABLED` in `ascentra.log`.

## API (unchanged for frontend)

| Action | Method | Path | Body |
|--------|--------|------|------|
| Send OTP | POST | `/api/v1/auth/send-otp` | `{ "phone": "9876543210" }` |
| Verify OTP | POST | `/api/v1/auth/verify-otp` | `{ "phone": "9876543210", "otp": "123456" }` |

Phone may be `9876543210` or `+919876543210` — backend adds `+91` when missing.

## Trial account

Twilio trial only sends to **verified** phone numbers in the Twilio console. Upgrade account for all users.

## Dev without Twilio

If env vars are empty, OTP is logged in server logs only (local fallback) — not for production.
