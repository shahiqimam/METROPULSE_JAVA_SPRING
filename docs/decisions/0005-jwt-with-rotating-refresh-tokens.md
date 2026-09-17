# 0005 - JWT Access Tokens with Rotating Refresh Tokens

## Context

The API needs authentication for operators and the control centre. The options divide on one
question: does authorising a request require a database read?

A server-side session does. A self-contained token does not, but cannot then be revoked, because
nothing is consulted at request time.

## Decision

Both, each doing what it is good at.

Access tokens are short-lived (15 minutes) self-contained JWTs, signed HS256, carrying the user's id
and single role. Refresh tokens are long-lived (7 days) opaque random strings, stored as a SHA-256
hash, checked against the database on every use and revocable immediately.

Every refresh rotates: a new refresh token is issued and the old one retired. Presenting a retired
token revokes the whole family, because the legitimate holder has already moved on to its
replacement — someone else has a copy.

## Alternatives

- **Server-side sessions.** Simple and revocable, but every request reads session state, and the
  sticky-session or shared-store question arrives with the second instance.
- **Long-lived access tokens without refresh.** Fewer moving parts, but the revocation window becomes
  the token lifetime, which is the whole problem.
- **Refresh tokens without rotation.** A leaked token is usable until it expires, and nothing ever
  reveals that it leaked.
- **BCrypt for refresh tokens.** Wrong tool: BCrypt is slow to make guessing a low-entropy password
  expensive, and a 384-bit random token has nothing to guess. What is needed is a fast lookup by
  hash, which a salted slow hash cannot give.

## Consequences

- Authorising a request is signature verification, no database round trip.
- An access token cannot be revoked. Its 15-minute lifetime *is* the revocation window, and that
  trade must be stated rather than glossed.
- Logout revokes the refresh token, so the session ends within one access-token lifetime.
- Reuse detection gives a signal that a token leaked, at the cost of ending every session that user
  has. That is the right trade for a control room.
- Two bugs came out of implementing this, both worth keeping in mind:
  - the reuse revocation shared a transaction with the rejection it triggers, so the exception rolled
    the revocation back;
  - a logged-out token was treated as reuse, so signing out one tab signed the operator out
    everywhere.
- The signing secret is symmetric, which is fine for one service issuing and verifying. A second
  service verifying these tokens would want asymmetric keys, so it can check signatures without being
  able to mint them.
