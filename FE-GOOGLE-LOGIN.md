# Frontend — Google Sign-In

## Google Client ID

```
448062236998-eoidebrs8cchf33dflcb2uqeba641s7d.apps.googleusercontent.com
```

## API Endpoint

```
POST /api/v1/auth/google
Content-Type: application/json
```

## Request

```json
{
  "credential": "<ID token string from Google Sign-In button>",
  "role": "CHILD"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| credential | Yes | The `credential` field from Google's `onSuccess` callback |
| role | No | Default role for new users: `CHILD` or `MASTER`. Ignored if user already exists. |

## Response (200 OK)

```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "user": {
    "id": "a1b2c3d4-...",
    "name": "John Doe",
    "email": "john@gmail.com",
    "role": "CHILD",
    "avatarUrl": "https://lh3.googleusercontent.com/a/photo...",
    "isNewUser": true
  },
  "provider": "GOOGLE"
}
```

| Field | Description |
|-------|-------------|
| accessToken | JWT for Authorization header |
| refreshToken | For `POST /api/v1/auth/refresh-token` |
| user.isNewUser | `true` = account just created, show onboarding |
| user.role | Existing role if account existed, else the `role` from request |
| user.avatarUrl | Google profile picture URL |

## Behavior

- **New email** → creates user, assigns `role` from request, returns `isNewUser: true`
- **Email already registered** → links Google to existing account, keeps existing role, returns `isNewUser: false`
- **Already linked Google user** → just logs in, returns tokens

## Error Responses

| Status | Body | When |
|--------|------|------|
| 400 | `{"error": "idToken is required"}` | Missing credential |
| 400 | `{"error": "Google email not verified"}` | User's Google email not verified |
| 401 | `{"error": "Invalid Google ID token"}` | Token expired or invalid |
| 503 | `{"error": "Google login not configured"}` | Server missing GOOGLE_CLIENT_ID |

## Notes

- Use `@react-oauth/google` package — pass same Client ID to `GoogleOAuthProvider`
- After success, store tokens same as email/password login
- Refresh token flow is identical: `POST /api/v1/auth/refresh-token`
- User can set a password later via forgot-password if they want email+password login too
