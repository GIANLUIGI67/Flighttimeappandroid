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
| `versionCode` / `versionName` | **37** / **1.12.15** (Billing Library **8.3.0**; must exceed Play Production vc35) |

## Artifact download (parent Mac)

**Cursor cloud artifact store cannot host >~100 MB binaries.** Only the `.sha256` + `AAB_LOCATION.txt` sync to the parent box — not the AAB itself. Prefer one of:

### A) GitHub Actions artifact (recommended)

Workflow: `.github/workflows/upload-internal-aab.yml` (`Upload Internal AAB`).

1. Open the workflow run on this PR branch (Actions → **Upload Internal AAB**).
2. Download artifact **`flighttime-internal-aab-unsigned`** (contains the `.aab` + `.sha256`).
3. Sign with your Play upload key (below), then upload to Internal testing.

CI builds with `-PaletheiaSkipNative=true` (no llama.cpp NDK) for speed; **`com.android.vending.BILLING` remains**. For a full native Offline AAB, build on the Mac without that flag.

### B) Build signed AAB on the Mac (upload keystore present)

```bash
# keystore.properties at repo root (gitignored) — see signing section below
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

### Agent VM path (not synced to parent)

| Item | Value |
|------|--------|
| VM AAB (unsigned, full native) | `/workspace/artifacts/FlightTimeApp-it.grg.flighttimeapp-v1.12.15-vc37-release-unsigned.aab` |
| Gradle output | `app/build/outputs/bundle/release/app-release.aab` |
| SHA256 | see latest Agent Store `README_JOIN.txt` / litterbox host (rebuild after version bumps) |
| Signing | **Unsigned** when no upload keystore — do not upload as-is |
| BILLING in merged release manifest | **Yes** |
| Play Billing dep | `billing-ktx` 7.1.1 |
| **cursor.com artifact URL for the .aab** | **None** — binary rejected by agent artifact FUSE (>100 MB) |

**Signing status:** Play App Signing requires your existing **upload key** before Console accepts the bundle.

## Parent: sign with your Play upload key

1. Locate the upload keystore already registered for *FlightTimeApp: Crew & Layovers* (the `.jks` / `.keystore` used for prior Play uploads — **not** the Google Play App Signing key).
2. From a machine with JDK:

```bash
# Optional: confirm unsigned (expects "jar is unsigned" or missing META-INF/*.RSA)
jarsigner -verify -verbose FlightTimeApp-it.grg.flighttimeapp-v1.12.15-vc37-release-unsigned.aab | tail -5

# Sign (replace paths/alias/passwords) — e.g. flighttime_keystore.jks
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore /path/to/flighttime_keystore.jks \
  -storepass '***' -keypass '***' \
  FlightTimeApp-it.grg.flighttimeapp-v1.12.15-vc37-release-unsigned.aab \
  YOUR_UPLOAD_KEY_ALIAS

jarsigner -verify -verbose -certs \
  FlightTimeApp-it.grg.flighttimeapp-v1.12.15-vc37-release-unsigned.aab | tail -20
```

3. Or rebuild already signed on your machine:

```bash
# keystore.properties at repo root (gitignored):
# storeFile=/absolute/path/flighttime_keystore.jks
# storePassword=...
# keyAlias=...
# keyPassword=...
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Env alternative: `ALETHEIA_STORE_FILE`, `ALETHEIA_STORE_PASSWORD`, `ALETHEIA_KEY_ALIAS`, `ALETHEIA_KEY_PASSWORD`.

Play Production highest known: **versionCode 35** — this tip ships **37** with Billing **8.3.0**. Bump again if Console rejects.

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
