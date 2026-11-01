# Aletheia Play Billing safety review — 2026-11-01

## Evidence and scope

PR #5 is open and unmerged. Reviewed head: `e4b9c1a89a46a32a924dd1181e6b63883baea902`.
This patch is based on that exact head and does not modify the Cursor branch or main.

| Layer | Observed evidence | Conclusion |
|---|---|---|
| Android | Billing 8.3.0; 3 subscription IDs and 3 consumable IDs; verify-before-grant hook | Client integration exists |
| Original release | `ALETHEIA_ENABLED=true` in defaultConfig, debug and release | Feature exposed before deployment evidence |
| Original verification | Boolean `ok`; optional product ID ignored; local pack amounts | Weak response contract; not authoritative balance sync |
| Original replay | Raw token set in SharedPreferences skips verification | No expiry/revocation recheck on that path; not a server ledger |
| Original persistence | Token commit result ignored; token recorded separately from balance | Crash/disk-failure window; local duplicate suppression is not durable atomic fulfillment |
| Android backend source | No Functions directory or implementation in reviewed tree | Backend implementation unavailable here |
| Accessible iOS repository | `GIANLUIGI67/FlightTimeApp`, main: inspected `functions/index.js`, `functions/package.json`, `firebase.json`; no Aletheia callables or Play verifier in those files | Does not establish absence in a separate Functions repository |
| Live Firebase / Play | No Firebase/Play administrative connection, deployment inventory, service-account authorization, ledger or licensed test purchase available | Deployment and actual server correctness remain UNVERIFIED |

The PR description and Android guide say the verifier still needs deployment. These are source claims, not live deployment evidence. No live purchase callable was invoked: it can mutate billing state, and fake tokens would not establish valid-purchase correctness anyway.

## Patch behavior

- defaultConfig and release disabled; debug enabled. Billing start and purchase launch also honor the feature flag.
- Only PURCHASED purchases, allowlisted products and nonblank tokens reach verification.
- Every purchase is verified again, including previously seen tokens. Legacy raw token preferences are removed.
- No locally computed credit increments, including restores and repeated callbacks. The client applies an absolute server balance.
- `ok` must be Boolean true, `productId` must match, `grantCommitted` must be Boolean true, and `creditsRemaining` must be an integer in 0..2147483647. Missing fields and legacy `{ok:true}` fail closed.
- Rejection, missing function, network failure, timeout and malformed response produce no new grant, acknowledgment or consumption. A verification error does not mean Google Play's payment state is PENDING: it is an unfulfilled purchase awaiting verification.
- Acknowledgment/consumption occur only after committed grant confirmation. Their failures are logged and retried through inventory refresh. This client retry alone is not a reliable lifecycle worker.
- Existing cached plan on failed Play inventory remains a UI hint; chat authorization and balances MUST be enforced server-side. The patch does not establish server enforcement, refund handling or cross-account cache isolation.

## Required backend contract (intentional compatibility change)

Callable `aletheiaVerifyPlayPurchase`, project `flighttimeapp-6f816`, region `us-central1`.
Request unchanged: `{productId,purchaseToken,packageName:"it.grg.flighttimeapp",platform:"android"}`.
Auth required. Response only after verification and durable fulfillment:

```json
{"ok":true,"productId":"aletheia_credits_s","grantCommitted":true,"creditsRemaining":50}
```

This is a required backend change, NOT an implemented or deployed server in this patch.
For subscriptions success also asserts that the requested product has a currently valid entitlement. Refund/revocation/expiry must never be accepted merely because its token was seen previously.

1. Ignore client-supplied price/credit amounts. Allowlist package and product/type; query Play Developer API using server credentials. Validate actual product line items and purchase state; for subscriptions check expiry and lifecycle state (a canceled renewal can remain entitled until expiry). Handle linked/replaced tokens.
2. Bind the globally unique purchase token to one authenticated uid. Prefer a deterministic token hash as the database key; do not log raw tokens. A key of `uid + token` alone permits cross-uid replay. Use obfuscated account identity when launching Play purchases and verify its association on the server; define a deliberate recovery policy for existing/anonymous accounts. This identity flow remains future work.
3. In one database transaction: claim global token ownership; insert a grant ledger record if absent; update the authoritative balance/entitlement. Same uid retry returns current state without adding credits; different uid retry rejects. A subscription renewal must not repeatedly grant an allowance just because the same token reappears; define a separate validated renewal-period key if allowances are added.
4. Implement durable server-side acknowledgment/consumption with retry and reconciliation after crashes. If consumption succeeded but response was lost, ledger retry must return the fulfilled result without a new grant. Never consume before durable fulfillment.
5. Handle RTDN and voided purchases/refunds/revocations idempotently; re-query Play, do not trust notification payload as entitlement authority. Enforce balances and subscription rights in chat, independently of client preferences. Define quantity/multi-quantity policy (reject unsupported quantities or grant verified quantity atomically).
6. Match any existing iOS/shared credit ledger and Firestore rules; clients must not write grants/balances. Do not create a parallel Firebase project or a speculative ledger without that schema.

References: https://developer.android.com/google/play/billing/security and https://developer.android.com/google/play/billing/backend

## Validation and activation gate

Available here: Python source-level regression checks (`python3 tools/check_aletheia_billing_safety.py`) and `git diff --check`. These checks inspect source invariants; they do NOT execute Kotlin, BillingClient, Firebase, or a database transaction.

Added JUnit tests for strict response parsing, malformed/legacy responses, mismatched SKU, negative/fractional/overflow balances and absolute replay semantics. Not executed here: Android SDK, Gradle distribution, Kotlin compiler and Firebase config are unavailable. In a configured full checkout run:

```sh
./gradlew -PaletheiaSkipNative=true :app:testDebugUnitTest
./gradlew -PaletheiaSkipNative=true :app:assembleDebug :app:assembleRelease
```

Before enabling release, record results for ALL of these with the deployed backend revision and licensed Play test account:

| Test | Required outcome | Status here |
|---|---|---|
| Missing callable, offline, timeout, invalid auth, ok:false, malformed response | No new benefit; no ack/consume | Source checks only; runtime blocked |
| Fake token, wrong package/SKU, PENDING payment | No ledger/balance change, ack or consume | Backend/device blocked |
| Valid purchase for each of six SKUs | One durable grant; correct absolute balance; ack/consume completed | Backend/device blocked |
| Same token sequential/concurrent, multiple devices, reinstall, crash retry | Exactly one server credit grant | Backend/device blocked |
| Same token under second uid; account switch | Reject replay; no transferred entitlement/cache benefit | Backend/device blocked |
| Crash after ledger commit, lost response, failed ack/consume | Retry completes lifecycle without credit duplication/loss | Backend/device blocked |
| Subscription expiry, renewal, refund, revoke, linked replacement | Correct server access and allowance; no cached-token bypass | Backend/device blocked |
| Release artifact with flag false; debug with flag true | Release entry and checkout inaccessible; debug testable | Build/device blocked |

Release remains OFF until these are demonstrated and an explicit activation decision is made. No merge, Firebase deployment, Play publication, purchases, or vendor messages were performed by this review.
