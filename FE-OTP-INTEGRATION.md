# Frontend OTP Integration Guide

---

## API Endpoints

**Base URL**: `https://api.ascentracapital.com` (or `http://13.53.246.13:8081` direct)

### 1. Send OTP (Phone Login)

```
POST /api/v1/auth/send-otp
Content-Type: application/json

{ "phone": "7020278661" }
```

**Response (success)**:
```json
{
  "success": true,
  "message": "OTP sent successfully",
  "expiresIn": 600,
  "retryAfter": 60
}
```

**Response (rate limited)**:
```json
{
  "success": false,
  "error": "RATE_LIMITED",
  "message": "Please wait before requesting another OTP",
  "retryAfter": 45
}
```

**Response (failure)**:
```json
{
  "success": false,
  "error": "SMS_FAILED",
  "message": "Failed to send OTP. Check Twilio config or verify trial phone list."
}
```

---

### 2. Verify OTP (Phone Login)

```
POST /api/v1/auth/verify-otp
Content-Type: application/json

{ "phone": "7020278661", "otp": "123456" }
```

**Response (success — user exists)**:
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "user": {
      "id": "uuid-here",
      "name": "Aniket",
      "email": "aniket@gmail.com",
      "phone": "7020278661",
      "role": "Master",
      "twoFactorEnabled": false
    }
  }
}
```

**Response (success — new user, needs registration)**:
```json
{
  "success": false,
  "error": "USER_NOT_FOUND",
  "message": "No account found with this phone number. Please register first."
}
```

**Response (invalid OTP)**:
```json
{
  "success": false,
  "error": "INVALID_OTP",
  "message": "Invalid OTP code"
}
```

**Response (expired)**:
```json
{
  "success": false,
  "error": "OTP_EXPIRED",
  "message": "OTP has expired. Please request a new one."
}
```

**Response (too many attempts)**:
```json
{
  "success": false,
  "error": "TOO_MANY_ATTEMPTS",
  "message": "Too many failed attempts. Request a new OTP."
}
```

---

### 3. Send Email OTP (2FA Login)

After password login when 2FA is enabled, the backend returns:
```json
{
  "requiresOtp": true,
  "twoFactorChannel": "EMAIL",
  "accessToken": "pending-2fa-token...",
  "message": "OTP sent to your email"
}
```

Frontend then calls verify:
```
POST /api/v1/auth/verify-login-otp
Content-Type: application/json
Authorization: Bearer <pending-2fa-token>

{ "otp": "123456" }
```

---

### 4. Send Login OTP (resend)

```
POST /api/v1/auth/send-login-otp
Content-Type: application/json

{ "email": "user@gmail.com" }
```

---

## Frontend Flow

### Phone Login Flow:
```
1. User enters phone number
2. Call POST /api/v1/auth/send-otp { phone }
3. Show OTP input (6 digits)
4. Start 60s countdown timer (retryAfter)
5. User enters OTP
6. Call POST /api/v1/auth/verify-otp { phone, otp }
7. If success → store tokens, redirect to dashboard
8. If USER_NOT_FOUND → redirect to register page
9. If INVALID_OTP → show error, let user retry
10. If OTP_EXPIRED → show "Request new OTP" button
```

### 2FA Email Login Flow:
```
1. User enters email + password
2. Call POST /api/v1/auth/login { email, password }
3. If response has requiresOtp: true → show OTP screen
4. Store the pending accessToken from response
5. User enters OTP from email
6. Call POST /api/v1/auth/verify-login-otp with Bearer pending-token
7. If success → store final tokens, redirect to dashboard
```

---

## React Example

```jsx
// SendOTP.jsx
const [phone, setPhone] = useState('');
const [otpSent, setOtpSent] = useState(false);
const [otp, setOtp] = useState('');
const [countdown, setCountdown] = useState(0);
const [error, setError] = useState('');

const sendOtp = async () => {
  const res = await fetch('/api/v1/auth/send-otp', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone })
  });
  const data = await res.json();
  if (data.success) {
    setOtpSent(true);
    setCountdown(data.retryAfter || 60);
  } else {
    setError(data.message);
  }
};

const verifyOtp = async () => {
  const res = await fetch('/api/v1/auth/verify-otp', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, otp })
  });
  const data = await res.json();
  if (data.success) {
    localStorage.setItem('accessToken', data.data.accessToken);
    localStorage.setItem('refreshToken', data.data.refreshToken);
    localStorage.setItem('user', JSON.stringify(data.data.user));
    navigate('/dashboard');
  } else {
    setError(data.message);
  }
};
```

---

## Notes

- Phone number format: backend auto-adds `+91` if no country code provided
- OTP is 6 digits, valid for 10 minutes
- Max 5 verify attempts per OTP
- Must wait 60s between resends
- Twilio trial: only works for verified numbers. Upgrade Twilio to paid for production.
- Country code default: 91 (India)
