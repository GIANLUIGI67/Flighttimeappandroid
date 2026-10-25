# Upload AAB to Play Internal testing (unlock IAP products)

Package: **`it.grg.flighttimeapp`** · track: **Internal testing** only (do **not** promote to production from this flow).

Play Console refuses to create in-app products until a billing-capable build is on a testing track. This PR’s release AAB includes:

- `com.android.vending.BILLING` (merged manifest)
- Play Billing library `billing-ktx` **7.1.1**
- Aletheia product IDs wired in code (`aletheia_*`)

## SDK (for target-API mail follow-up)

| Field | Value |
|-------|-------|
| `compileSdk` | **36** |
| `targetSdk` | **36** |
| `minSdk` | **24** |
| `versionCode` / `versionName` | **26** / **1.12.4** |

## Artifact from this PR tip

| Item | Value |
|------|--------|
| **AAB (unsigned)** | `/workspace/artifacts/FlightTimeApp-it.grg.flighttimeapp-v1.12.4-vc26-release-unsigned.aab` |
| Gradle output | `app/build/outputs/bundle/release/app-release.aab` |
| SHA256 sidecar | `/workspace/artifacts/FlightTimeApp-it.grg.flighttimeapp-v1.12.4-vc26-release-unsigned.aab.sha256` |
| Size | ~154 MiB |
| Signing | **Unsigned** (`jarsigner`: *jar is unsigned*) — no upload keystore in repo/CI |
| BILLING in merged release manifest | **Yes** — `com.android.vending.BILLING` |
| Play Billing dep | `billing-ktx` 7.1.1 |

Rebuild anytime: `./gradlew :app:bundleRelease` (optional `keystore.properties` / `ALETHEIA_*` env for upload-key signing).

**Signing status:** Play App Signing requires your existing **upload key** before Console accepts the bundle. Do not upload the unsigned file as-is.

## Parent: sign with your Play upload key

1. Locate the upload keystore already registered for *FlightTimeApp: Crew & Layovers* (the `.jks` / `.keystore` used for prior Play uploads — **not** the Google Play App Signing key).
2. From a machine with JDK:

```bash
# Optional: confirm unsigned (expects "jar is unsigned" or missing META-INF/*.RSA)
jarsigner -verify -verbose FlightTimeApp-it.grg.flighttimeapp-v1.12.4-vc26-release.aab | tail -5

# Sign (replace paths/alias/passwords)
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore /path/to/upload.jks \
  -storepass '***' -keypass '***' \
  FlightTimeApp-it.grg.flighttimeapp-v1.12.4-vc26-release.aab \
  YOUR_UPLOAD_KEY_ALIAS

jarsigner -verify -verbose -certs \
  FlightTimeApp-it.grg.flighttimeapp-v1.12.4-vc26-release.aab | tail -20
```

3. Or rebuild already signed on your machine:

```bash
# keystore.properties at repo root (gitignored):
# storeFile=/absolute/path/upload.jks
# storePassword=...
# keyAlias=...
# keyPassword=...
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Env alternative: `ALETHEIA_STORE_FILE`, `ALETHEIA_STORE_PASSWORD`, `ALETHEIA_KEY_ALIAS`, `ALETHEIA_KEY_PASSWORD`.

If `versionCode` **26** was already uploaded, bump `versionCode` in `app/build.gradle.kts` before rebuilding.

## Parent: upload to Internal testing

1. [Play Console](https://play.google.com/console) → app **FlightTimeApp: Crew & Layovers** (`it.grg.flighttimeapp`).
2. **Testing → Internal testing → Create new release** (or edit draft).
3. Upload the **signed** AAB → save → review → **Start rollout to Internal testing**.
4. After the release is available (processing can take minutes), go to **Monetize → Products** and create:

| Type | Product ID |
|------|------------|
| Sub monthly | `aletheia_plus_monthly` |
| Sub monthly | `aletheia_pro_monthly` |
| Sub monthly | `aletheia_max_monthly` |
| One-time | `aletheia_credits_s` / `_m` / `_l` |

5. Do **not** publish this PR’s build to Production from the agent workflow.

## Verify BILLING in the bundle (optional)

```bash
# After unzipping AAB base manifest, or from a local release merge:
rg 'com.android.vending.BILLING' app/build/intermediates/merged_manifest/release/
```
