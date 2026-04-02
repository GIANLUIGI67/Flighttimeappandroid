# FlightTimeApp Android — Testing Skill

## Overview
Android app for pilots/crew to track flight hours, calculate salary, manage crew layovers, and access training materials. Uses Material3 DayNight theme with `values/` and `values-night/` color qualifiers.

## Build

```bash
export ANDROID_SDK_ROOT=$HOME/android-sdk
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
```

### Known Build Blocker
The repo has **pre-existing duplicate strings** in `app/src/main/res/values-it/strings.xml` (e.g., `cl_near_me` on lines 61 & 210, plus `cl_online_now`, `cl_active_last_24h`, `cl_distance_max_label`, `cl_distance_unlimited_toggle`). These exist on `main` and will cause the build to fail. To unblock:
```bash
# Remove duplicate block (lines 210-216) temporarily
sed -i '210,216d' app/src/main/res/values-it/strings.xml
```
Do NOT commit this fix unless you're explicitly asked to — it's a pre-existing issue.

## Testing Without an Emulator (aapt2 Resource Verification)

The Devin VM has **no KVM/hardware acceleration**. The Android emulator uses software rendering and is extremely slow — the PM service may never stabilize (expect `NullPointerException` on `adb install`). 

**Use `aapt2` to verify compiled resources instead:**

```bash
AAPT2=$HOME/android-sdk/build-tools/36.0.0/aapt2
APK=app/build/outputs/apk/debug/app-debug.apk

# Dump XML tree of a layout file
$AAPT2 dump xmltree $APK --file res/layout/activity_flight_editor.xml

# Dump all resources (styles, colors, etc.)
$AAPT2 dump resources $APK | grep -A5 "CrewDatePickerDialog"

# Verify a specific resource ID
$AAPT2 dump resources $APK | grep "iosBackground"
```

### What aapt2 Verification Proves
- Layout files reference correct themed color resources (app namespace `0x7f05xxxx`) vs hardcoded system colors (`0x0106xxxx`)
- Style parents are correct (e.g., `DayNight.Dialog` vs `Light.Dialog`)
- Color aliases resolve correctly (e.g., `qbOptionNormalStroke` → `iosCardStroke`)

### What It Does NOT Prove
- Visual rendering (text readability, color contrast)
- Runtime behavior (dialogs, animations, transitions)
- Accessibility compliance

For visual verification, recommend the user test on a real device or local emulator with hardware acceleration.

## Key Resource IDs
| Resource | ID | Light | Dark |
|---|---|---|---|
| iosBackground | 0x7f050072 | #FFFFFF | #000000 |
| iosRed | 0x7f05007e | #FF3B30 | #FF453A |
| iosCardStroke | 0x7f050076 | #E5E5EA | #2C2C2E |
| iosText | 0x7f050080 | #111111 | #FFFFFF |
| qbOptionNormalStroke | 0x7f050322 | → iosCardStroke | → iosCardStroke |

**Note:** Resource IDs may change if new resources are added. Re-run `aapt2 dump resources $APK | grep "iosBackground"` to get current IDs.

## App Navigation
- **MainActivity** → 3 cards: Salary Calculator, Crew Layover, Training
- **Salary Calculator** → Flight list + editors (flight, layover, overtime, deduction, allowance, salary bands)
- **Training** → A320 Question Bank (uses `item_qb_option.xml`)
- **Crew Layover** → Layover list + editors

## Dark Mode Color System
The app uses a custom iOS-style color palette defined in:
- `app/src/main/res/values/colors.xml` (light mode)
- `app/src/main/res/values-night/colors.xml` (dark mode)

14+ themed colors with proper light/dark pairs. Layout files should reference these via `@color/iosXxx` — never use `@android:color/*` or hardcoded hex values for theme-sensitive elements.

**Intentionally hardcoded (don't change):**
- Photo preview overlays (`dialog_photo_preview`, `item_photo_pager`, `bottom_sheet_reaction_list`) — dark backgrounds by design
- White text on blue buttons in `styles.xml` and `CrewChatActivity` — intentional contrast

## Devin Secrets Needed
None — this is a public repo with no API keys or authentication required for building and testing.
