# Security

## Authentication

Two tokens, because they answer different questions.

**Access tokens** are short-lived JWTs (15 minutes), signed HS256. They are self-contained, so every
request is authorised without touching the database — which is the point — but that also means they
**cannot be revoked**. Their lifetime *is* the revocation window, which is why it is measured in
minutes rather than hours.

**Refresh tokens** are long-lived opaque strings (7 days), stored as a SHA-256 hash. They are checked
against the database on every use, so they can be revoked immediately, and they are accepted for
exactly one thing: minting a new access token.

### Why the refresh token is hashed, and with SHA-256 rather than BCrypt

A refresh token is a credential: anyone holding one can mint access tokens until it expires. Storing
it in the clear makes a database read a complete account takeover.

BCrypt is deliberately slow, to make guessing a low-entropy human password expensive. A refresh token
is 384 bits of randomness — there is nothing to guess. What is needed is a fast lookup by hash, which
a salted slow hash cannot provide.

### Rotation, and what reuse means

Every refresh mints a new refresh token and retires the old one. Presenting a **retired** token is
not an ordinary error: the legitimate holder has already moved on to its replacement, so someone else
has a copy. The whole family is revoked, ending every session that user has.

A token revoked for any *other* reason — a logout, an administrator ending a session — is simply
rejected. Treating that as a leak would let a stale browser tab sign an operator out of every device
they are using.

Two bugs found while testing this, both fixed:

- The reuse revocation was in the same transaction as the rejection it triggers, so the exception
  rolled the revocation back: the thief was turned away once and the family stayed valid.
  `RefreshTokenRevoker` now commits in its own transaction.
- A logged-out token was treated as reuse, so logging out one device signed the operator out
  everywhere.

## Authorisation

Five roles, describing jobs rather than permissions:

| Role | May read | May act on the network | Schedule feeds | Administration |
| --- | --- | --- | --- | --- |
| `ADMIN` | yes | yes | stage and activate | yes |
| `CONTROLLER` | yes | yes | no | no |
| `FLEET_SUPERVISOR` | yes | charging only | no | no |
| `PLANNER` | yes | no | stage and review | no |
| `VIEWER` | yes | no | no | no |

The planner's column is the one that needed thinking about. Uploading a feed writes nothing
operational — it produces a proposal and a preview of what activating it would change — so a planner
can do it. Activating changes what every number on the network is measured against, retrospectively
as well as going forward, so that stays with an administrator. Building a review screen and then
putting it behind a role its reviewers do not have would have made it a screen nobody could reach.

Rules are expressed by URL and method in `SecurityConfig`, in one place, so the whole policy reads at
once. Scattering `@PreAuthorize` across controllers makes "who can close an alert" a question you
answer by grepping.

The frontend hides controls a role cannot use. **That is presentation, not security** — a viewer who
forges the request still gets a 403, and the test suite asserts it.

## The request path

```text
HTTP request
  -> SecurityFilterChain
  -> BearerTokenAuthenticationFilter   reads the Authorization header
  -> JwtDecoder                        verifies signature and expiry
  -> JwtAuthenticationConverter        turns the role claim into an authority
  -> SecurityContext
  -> authorization rules
  -> controller
```

## Telemetry ingest is not part of this

`POST /api/v1/telemetry/ingest` is machine-to-machine, authenticated by `X-Ingest-Key`. Giving the
simulator a bearer token would mean giving it an operator's credentials, which is a different and
worse thing than giving it a device key.

## Other measures

- **Passwords**: BCrypt (cost 10). Login failures are indistinguishable — unknown account, wrong
  password and deactivated account all produce the same status and message, because distinguishing
  them tells an attacker which email addresses are real. A missing account still runs a hash, so it
  does not answer faster.
- **CORS**: explicit allowlist, never a wildcard. The API takes credentials, and a wildcard would let
  any site make authenticated calls on a logged-in operator's behalf.
- **CSRF**: disabled, and safely so — there are no cookies and no sessions, so nothing is attached to
  a cross-site request automatically. This would have to change the moment tokens moved into cookies.
- **Sessions**: stateless.
- **Errors**: `@RestControllerAdvice` returns a code, a message and a request id. SQL, stack traces
  and tokens never reach the client.
- **Internal services**: PostgreSQL, Kafka and Redis are not published outside the Compose network in
  the production-style stack.

## Known limitations

- Tokens are held in `sessionStorage`, which is readable by any script on the page. The mitigations
  are a short access-token lifetime and a revocable refresh token. Cookies with `HttpOnly`, `Secure`
  and `SameSite` would be stronger and would bring CSRF protection back into scope.
- Development accounts ship in a Flyway migration with well-known passwords. They are documented as
  synthetic demo credentials; a real deployment would not seed accounts at all.
- No rate limiting on login, so password guessing is only bounded by BCrypt's cost.
- No password change, reset, or account management endpoints.
- The JWT secret is symmetric. A second service verifying these tokens would want asymmetric keys, so
  it can check signatures without being able to mint them.
- WebSocket authentication does not exist yet, because the realtime channel does not exist yet.
