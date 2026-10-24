# Aletheia Beta on FlightTime Android

Package: `it.grg.flighttimeapp` (Play app **FlightTimeApp: Crew & Layovers**, Innovative Aviation).  
Brand: **Aletheia Beta** · client version **1.1** (`BuildConfig.ALETHEIA_VERSION`).  
Entry: Home → **Crew Tools** → Aletheia hub (feature flag `BuildConfig.ALETHEIA_ENABLED`).

This port talks to the existing Firebase project **flighttimeapp-6f816** (same backend as iOS). Do **not** publish to Play from this PR; create Play products only.

## Build in Android Studio

1. Clone `GIANLUIGI67/Flighttimeappandroid` and open the project root in Android Studio (Ladybug+ / AGP 9).
2. Place `app/google-services.json` for `it.grg.flighttimeapp` / project `flighttimeapp-6f816` (gitignored). Download from Firebase Console → Project settings → your Android app.
3. Sync Gradle. Min SDK 24, compile/target 36.
4. Run configuration: module `app`, debug build.
5. Sideload: `./gradlew :app:assembleDebug` then  
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
6. Debug App Check: debug builds use the Firebase App Check debug provider (see `FlightTimeApplication`). Register the debug token in Firebase Console if callables reject App Check.
7. Rollback: set `ALETHEIA_ENABLED` to `false` in `app/build.gradle.kts` `defaultConfig` / buildTypes — Crew Tools falls back to coming-soon.

## Firebase Functions used (us-central1)

| Callable | Role |
|----------|------|
| `aletheiaSetConsent` | `{ granted: boolean }` → trainingConsent |
| `aletheiaChat` | `{ message, messageId?, platform, clientVersion }` → answer, source/route, daily quota, credits, haiku |
| `aletheiaSubmitFeedback` | `{ messageId, rating: good\|partial\|bad, note? }` |
| `aletheiaLogTurn` | Offline/security outbox `{ prompt, answer, messageId, source, … }` |

Auth: anonymous Firebase Auth (same pattern as Crew). Online stack: Groq free + Haiku for Pro (server-side).

**Missing on backend (gap):** `aletheiaVerifyPlayPurchase` — Android Billing acknowledges locally and will call this when deployed. Until then server entitlements (Haiku/credits) may not unlock from Play purchases.

## Play Console products to create

App: **FlightTimeApp: Crew & Layovers** · application id `it.grg.flighttimeapp`  
Path: Monetize → Products / Subscriptions (do not publish this PR).

### Subscriptions (auto-renewing, monthly)

| Product ID | Suggested name |
|------------|----------------|
| `aletheia_plus_monthly` | Aletheia Plus |
| `aletheia_pro_monthly` | Aletheia Pro |
| `aletheia_max_monthly` | Aletheia Max |

### One-time credit packs (managed products / in-app)

| Product ID | Suggested credits (client hint) |
|------------|----------------------------------|
| `aletheia_credits_s` | 50 |
| `aletheia_credits_m` | 150 |
| `aletheia_credits_l` | 400 |

Mirror iOS Plus/Pro/Max + S/M/L naming; if iOS StoreKit IDs differ, keep these Play IDs stable and map on the server.

## Product behaviour (parity checklist)

- Free: **20 Online/day** hard stop (server `dailyLimit`) + **unlimited Offline**
- Mandatory Yes/No RL consent; No = full AI; RL only if Yes; Settings toggle; no dark patterns
- Feedback thumbs / neutral / down; note compulsory for down/neutral only if consent Yes
- Chat logs via Functions (`aletheiaChat` / `aletheiaLogTurn`); RL gated by consent
- Source badges: Online / Offline / Manuali
- Paywall on limit; after purchase continue until next limit; reappear on each limit; upgrade or wait ~24h
- Thinking indicator after ~1–4s wait
- Offline selection: Low Power / battery-saver warning (dismissible, not per-message)
- EN + IT strings for UI including paywall/plans

## Offline GGUF packaging

iOS can ship or download domain GGUF in the app sandbox. Android APK size limits make embedding large weights undesirable.

- Expected path: `context.filesDir/aletheia/offline.gguf`
- Reuse the same aviation-domain GGUF as Aletheia Offline on iOS when available
- This build: manuals-first index + domain heuristic; native llama.cpp / MediaPipe link is documented as follow-up when the iOS model artifact is shared
- Offline turns enqueue to `AletheiaOutbox` and flush with `aletheiaLogTurn` when online

## Key source files

- `app/src/main/java/it/grg/flighttimeapp/aletheia/` — feature module
- Layouts: `activity_aletheia_*.xml`, `item_aletheia_message.xml`
- Strings: `values/strings.xml`, `values-en`, `values-it`
- Flag: `BuildConfig.ALETHEIA_ENABLED` / `ALETHEIA_VERSION`

## Gaps vs iOS (honest)

1. Private iOS repo `GIANLUIGI67/FlightTimeApp` PR #1 was not readable from this agent token — behaviour taken from product spec + live Functions probing (`aletheiaChat` etc.).
2. Native on-device GGUF inference not linked; manuals + heuristic Offline until model AAR/JNI + weights are provided.
3. Play purchase → server entitlement verification function not deployed yet.
4. Exact iOS StoreKit product ID strings unknown; Play IDs listed above for the coordinator.
5. Manuali badge uses Italian label by product request on both EN/IT (matches iOS “Manuali” branding).
