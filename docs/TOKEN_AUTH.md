# Token Authentication — Construct Messenger

> Cross-platform spec for auth token handling on clients. **Android implements PASETO v4.public
> natively — do NOT add JWT support.** iOS carries dual-format parsing transiently during the
> server migration; Android (greenfield) targets PASETO only.

---

## 1. Token Format

### PASETO v4.public (canonical)

```
v4.public.<payload>[.<footer>]
```

| Segment | Encoding | Contents |
|---|---|---|
| `v4.public.` | literal ASCII | Header / version+purpose tag |
| `<payload>`  | base64url (no padding) | `nonce(32 bytes) \|\| message(JSON claims) \|\| signature(64 bytes)` |
| `.<footer>`  | base64url (no padding), optional | Free-form metadata (unused for auth tokens) |

**Cryptographic primitive**: Ed25519 (RFC 8032) signature.

**Pre-auth encoding** (what Ed25519 signs): `"paseto.v4.public." || nonce || message`
— client does NOT verify the signature; the server does. The client only slices out the
message to read claims for last-resort `userId` recovery (see §3).

### Claims (message = JSON object)

Identical to the legacy JWT claims — no schema change:

| Claim | Type | Required | Meaning |
|---|---|---|---|
| `sub` | string (UUID) | yes | User ID (server UUID, 36-char) |
| `jti` | string (UUID) | yes | Token ID — used for revocation/blocklist |
| `exp` | int64 (unix seconds) | yes | Expiration time |
| `iat` | int64 (unix seconds) | yes | Issued at |
| `iss` | string | yes | Issuer (`construct-server`) |
| `device_id` | string | optional | Device identifier (32-char hex) |

---

## 2. Token Lifecycle

```
Onboarding / Device init
        │
        ▼
 AuthService.RegisterDevice / AuthenticateDevice (PoW + device signature, no token yet)
        │
        ▼
 ┌──────────────────────────────────────────┐
 │ AuthTokensResponse                        │
 │   access_token  : "v4.public.…"           │
 │   refresh_token : "v4.public…"            │
 │   expires_at    : int64 (unix seconds)    │
 └──────────────────────────────────────────┘
        │  persist to Keystore / EncryptedSharedPreferences
        ▼
 gRPC calls carry: Authorization: Bearer <access_token>
                   x-user-id: <sub claim>          (cached, NOT re-parsed each call)
                   x-device-id: <device_id>        (from Keystore)
        │
        ▼  access_token near expiry (5-min margin)
 TokenRefreshCoordinator.refreshIfPossible(refresh_token)
        │
        ▼
 AuthTokensResponse (new pair) → re-persist
```

**TTL defaults** (server-side; clients should not hardcode — read `expires_at` from the
auth response):
- access token: **24 hours** (`ACCESS_TOKEN_TTL_HOURS`)
- refresh token: **90 days** (`REFRESH_TOKEN_TTL_DAYS`)

---

## 3. Client Responsibilities

### 3.1 Storage (opaque strings)

Tokens are stored **as opaque strings** — the client does not parse them on the hot path.
Persist via the platform secure store:

- **Android**: `EncryptedSharedPreferences` or Android Keystore-backed store. Never
  `SharedPreferences` (plaintext).
- **iOS**: Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).

The `expires_at` timestamp (Unix seconds) from `AuthTokensResponse` is persisted
alongside the tokens — this drives the refresh schedule, NOT the token's own `exp` claim.

### 3.2 Injection (gRPC metadata)

Every authenticated RPC adds three headers (mirror of iOS `AuthInterceptor.swift`):

```kotlin
Authorization: Bearer <access_token>
x-user-id:     <userId>        // from cached session state, NOT re-parsed per call
x-device-id:   <deviceId>      // from Keystore
```

**Unauthenticated RPCs** (must NOT carry auth headers):
`GetPowChallenge`, `RegisterDevice`, `AuthenticateDevice`, `RefreshToken`,
`CheckUsernameAvailability`.

### 3.3 Token expiry / refresh

- Refresh is scheduled `expires_at - 5 minutes`.
- A single-flight `TokenRefreshCoordinator` serializes concurrent refresh requests so
  multiple UI components hitting `.unauthenticated` simultaneously don't race on the
  same stored refresh token (one side would get "already used / revoked").
- On permanent refresh failure (`revoked` / `already used` / `.unauthenticated`),
  wipe stored tokens and trigger **device re-auth** (PoW challenge + device signature).
  Do NOT require a login/password — the messenger has no login/logout model;
  identity is recovered via the device's Ed25519 signing key in Keystore.

### 3.4 Last-resort userId recovery

If `userId` is missing from the cached session (Keystore entry lost, app reinstall
without full device reset), the client may recover it from the token's `sub` claim.
This is the **only** client-side token parsing operation permitted on the hot path.

Extracting claims from a PASETO v4.public token **without** signature verification:

```kotlin
object TokenUtils {
    fun extractUserId(token: String): String? {
        if (!token.startsWith("v4.public.")) return null
        val stripped = token.removePrefix("v4.public.")
        val payloadB64 = stripped.substringBefore('.')    // footer optional
        val payload = base64UrlDecode(payloadB64) ?: return null
        // nonce(32) + message(variable) + signature(64)
        if (payload.size <= 32 + 64) return null
        val message = payload.copyOfRange(32, payload.size - 64)
        val claims = runCatching {
            JSONObject(String(message, Charsets.UTF_8))
        }.getOrNull() ?: return null
        return claims.optString("sub").takeIf { it.isNotEmpty() }
    }

    private fun base64UrlDecode(input: String): ByteArray? {
        val padded = input
            .replace('-', '+')
            .replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return Base64.decode(padded, Base64.NO_WRAP)
    }
}
```

> **Why not verify the signature?** The server verifies. Client-side verification would
> require shipping the server's Ed25519 public key in the APK and rotating it on every
> key change — a deployment liability with no security benefit, since a malicious client
> can skip verification anyway. The threat model is: the server rejects forged tokens;
> the client trusts tokens it received from `AuthService` over TLS.

### 3.5 Token format guard

On app launch, after loading the cached session token, the client should sanity-check
its format. Accept `v4.public.*` only — anything else indicates corruption or a
non-PASETO token (the server no longer issues JWT after migration completes):

```kotlin
fun loadSessionToken() {
    sessionToken = keyStoreManager.loadAccessToken()
    refreshToken = keyStoreManager.loadRefreshToken()
    userId = keyStoreManager.loadUserId()

    if (sessionToken != null && !sessionToken!!.startsWith("v4.public.")) {
        Log.e(TAG, "Unexpected token format in cache — clearing session")
        clearSession()
        isSessionInvalidated = true
        return
    }
    syncAuthCache()
}
```

---

## 4. gRPC Service Definitions

Auth token responses use these protobuf messages (see
`shared/proto/services/auth_service.proto`):

```protobuf
message AuthTokensResponse {
    string access_token  = 2;
    string refresh_token = 3;
    int64  expires_at    = 4;   // unix seconds — drive refresh schedule from THIS
}

message RefreshTokenRequest       { string refresh_token = 1; }
message RefreshTokenResponse      {
    string access_token  = 1;
    string refresh_token = 2;   // rotated, replaces the consumed one
    int64  expires_at    = 3;
}
```

**Important**: `expires_at` is the authoritative expiry. Do not parse the token's `exp`
claim on the client to compute expiry — use the protobuf field directly. This keeps
clients robust to server-side TTL changes.

---

## 5. Revocation & Blocklist

The server maintains a Redis-backed revocation mechanism keyed on the `jti` claim:

- **Access token blocklist**: `invalidated_token:{jti}` with TTL = remaining token
  lifetime. Written on explicit logout and on gRPC `AuthService.Logout`.
- **Refresh token rotation**: refresh tokens are single-use; each refresh consumes the
  old `jti` and stores a new one atomically via a Lua script.
- **Revoke-all**: `SMEMBERS user_tokens:{user_id}` → delete each refresh token.

The client is **not** aware of the blocklist — if a token is revoked, the next RPC
gets `.unauthenticated` and the refresh coordinator takes over. If refresh itself is
revoked, the client falls through to device re-auth.

---

## 6. Force-Refresh Migration Path (JWT → PASETO)

The server is migrating from RS256 JWT to PASETO v4.public. Since the messenger has no
login/logout flow and users could lose their identity if their session is forcibly
invalidated, the migration uses **force-refresh with token replacement**, not a hard
cutover:

1. **Server issues PASETO** (post-cutover). New logins / device inits get PASETO.
2. **Existing JWT sessions keep working** — the server `verify_token` accepts both
   formats during the transition window.
3. **Force-refresh**: at the next access-token expiry, the client's
   `TokenRefreshCoordinator` calls `RefreshToken` with the old JWT refresh token.
   The server accepts it (dual verify), consumes the JWT refresh `jti`, and returns a
   **PASETO** token pair. The session is now on PASETO.
4. **Window**: up to `refresh_token_ttl_days` (90 days default) for all sessions to
   naturally rotate. After that, stale JWT refresh tokens have expired and any
   remaining clients re-auth via device signature (PoW).
5. **Legacy JWT code is removed** from server and iOS client after the rotation window
   completes and JWT-verify volume is zero for a sustained period.

**Android (this repo) is greenfield — never implements JWT.** If an Android client ever
receives a token that is not `v4.public.*`, treat it as an error and fall through to
device re-auth. The server will not issue JWT to new clients post-cutover.

---

## 7. Implementation Checklist (Android)

- [ ] `TokenUtils.kt` (extractUserId, format guard) — see §3.4
- [ ] `KeyStoreManager.kt` — secure storage for access/refresh tokens + userId + deviceId
- [ ] `AuthInterceptor.kt` — gRPC metadata injection (Bearer + x-user-id + x-device-id)
- [ ] `TokenRefreshCoordinator.kt` — single-flight refresh actor
- [ ] `AuthSessionManager.kt` — observable session state, `saveTokens`/`clearSession`
- [ ] `AuthService.kt` — gRPC client wrapper for RegisterDevice / AuthenticateDevice /
      RefreshToken
- [ ] Device re-auth fallback on permanent refresh failure
- [ ] Unit tests:
  - `TokenUtilsTest`: extract `sub` from PASETO; reject non-PASETO; malformed payload;
    too-short payload; bad JSON; token with footer
  - `AuthSessionManagerTest`: cache token-format guard (PASETO accepted, garbage
    rejected → clearSession)
  - `TokenRefreshCoordinatorTest`: single-flight serialization, permanent-invalid
    classification