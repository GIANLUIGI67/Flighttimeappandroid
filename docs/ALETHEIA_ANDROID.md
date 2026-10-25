# Aletheia Beta on FlightTime Android

Package: `it.grg.flighttimeapp` (Play app **FlightTimeApp: Crew & Layovers**, Innovative Aviation).  
Brand: **Aletheia Beta** · client version **1.1** (`BuildConfig.ALETHEIA_VERSION`).  
Entry: Home → **Crew Tools** → Aletheia hub (feature flag `BuildConfig.ALETHEIA_ENABLED`).

This port talks to the existing Firebase project **flighttimeapp-6f816** (same backend as iOS). Do **not** publish to Play from this PR; create Play products only.

## Build in Android Studio

1. Clone `GIANLUIGI67/Flighttimeappandroid` and open the project root in Android Studio (Ladybug+ / AGP 9).
2. Place `app/google-services.json` for `it.grg.flighttimeapp` / project `flighttimeapp-6f816` (**gitignored**, not tracked). Download from Firebase Console → Project settings → your Android app.
3. Install **NDK 27.0.12077973** and **CMake 3.22.1** (SDK Manager). Native Offline GGUF needs them.
4. Sync Gradle. Min SDK 24, compile/target 36. First native build compiles vendored llama.cpp (`app/src/main/cpp/third_party/llama.cpp`, tag `b5215`).
5. Run configuration: module `app`, debug build.
6. Sideload: `./gradlew :app:assembleDebug` then  
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
7. Debug App Check: debug builds use the Firebase App Check debug provider (see `FlightTimeApplication`). Register the debug token in Firebase Console if callables reject App Check.
8. Rollback: product ships with `ALETHEIA_ENABLED=true` in **release**. Changing only `defaultConfig` does not disable release — set `false` in **defaultConfig and every buildType** (`debug`/`release`). Home Crew Tools badge follows the flag (Aletheia vs Coming soon).

### google-services.json / Google API key

- File is listed in `.gitignore` (`google-services.json`, `app/google-services.json`) and is **not** in the current tree as a tracked file.
- Client API keys inside `google-services.json` are expected for mobile apps but **must** be restricted in Google Cloud Console: Android apps restriction (package `it.grg.flighttimeapp` + SHA-1/256 certs) and API restriction to only needed Firebase/Google APIs. Do not commit unrestricted keys; do not rotate keys from agents (console-only).

## Firebase Functions used (us-central1)

| Callable | Role |
|----------|------|
| `aletheiaSetConsent` | `{ granted: boolean }` → trainingConsent |
| `aletheiaChat` | `{ message, messageId?, platform, clientVersion }` → answer, source/route, daily quota, credits, haiku |
| `aletheiaSubmitFeedback` | `{ messageId, rating: good\|partial\|bad, note? }` |
| `aletheiaLogTurn` | Offline/security outbox `{ prompt, answer, messageId, source, … }` |
| `aletheiaVerifyPlayPurchase` | **Not deployed yet** — see contract below |

Auth: anonymous Firebase Auth (same pattern as Crew). Online stack: Groq free + Haiku for Pro (server-side).

### `aletheiaVerifyPlayPurchase` callable contract

Must be deployed on **`flighttimeapp-6f816`** / **`us-central1`** (do **not** create a second Firebase project).

**Request (HTTPS callable, Auth required):**

```json
{
  "productId": "aletheia_plus_monthly",
  "purchaseToken": "<Play purchase token>",
  "packageName": "it.grg.flighttimeapp",
  "platform": "android"
}
```

**Success:** `{ "ok": true }` (optional `creditsGranted` number).  
**Reject / missing function:** client keeps purchase **pending** and does **not** grant entitlements.

**Server:** verify via Play Developer API (service account linked in Play Console) for subscriptions (`purchases.subscriptionsv2.get`) and one-time products (`purchases.products.get`); idempotent ledger on `uid` + `purchaseToken`.

Android client hook is complete in `AletheiaApi.verifyPlayPurchase` + `AletheiaBillingManager` (verify-before-grant / ack subs / consume packs).

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

## Offline GGUF (native llama.cpp)

Wired end-to-end:

| Layer | Location |
|-------|----------|
| JNI + CMake | `app/src/main/cpp/` → `libaletheia_llama.so` |
| Vendored llama.cpp | `app/src/main/cpp/third_party/llama.cpp` (tag `b5215`) |
| Kotlin runtime | `AletheiaLlamaRuntime` / `AletheiaLlamaNative` |
| Engine | `AletheiaOfflineEngine` — manuals first, then GGUF |
| Installer | Settings → Download Offline model (Wi‑Fi) → `filesDir/aletheia/offline.gguf` |

**Default public weights (until official Aletheia Offline GGUF is shared):**  
`ANSHG/merged_llama-3.2-3b-AviationQA-ansh-Q4_K_M-GGUF` (~2 GB Q4_K_M).  
**Not embedded in the APK** (Play size). Human decision: keep on-demand download, Play Asset Delivery, or sideload.

adb sideload example:

```bash
adb push merged_llama-3.2-3b-aviationqa-ansh-q4_k_m.gguf /sdcard/Download/offline.gguf
# then copy into the app's filesDir/aletheia/offline.gguf (or use in-app download)
```

System prompt includes aviation / FTL study-aid guards (no invented minima; AFM/FCOM/SOP). Offline turns still enqueue to `AletheiaOutbox` → `aletheiaLogTurn`.

## Key source files

- `app/src/main/java/it/grg/flighttimeapp/aletheia/` — feature module
- `app/src/main/cpp/` — llama.cpp JNI
- Layouts: `activity_aletheia_*.xml`, `item_aletheia_message.xml`
- Strings: `values/strings.xml`, `values-en`, `values-it`
- Flag: `BuildConfig.ALETHEIA_ENABLED` / `ALETHEIA_VERSION`

## Gaps vs iOS / human Console work

1. Private iOS / Aletheia Offline repos were not readable from this agent — behaviour from product spec + live Functions probing.
2. **Deploy** `aletheiaVerifyPlayPurchase` on `flighttimeapp-6f816` + Play Developer API **service account**.
3. **Create** the six Play product IDs above (do not publish this PR).
4. **GGUF packaging:** ~2 GB weights stay out of APK; approve official URL / PAD / sideload policy.
5. Manuali badge uses Italian label by product request on both EN/IT.
