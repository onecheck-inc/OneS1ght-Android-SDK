# OneS1ght SDK — Android (Kotlin)

**English** | [한국어](README.ko.md) | [日本語](README.ja.md)

📖 Documentation: https://docs.ones1ght.com/en/sdk/integration/android

Indoor location intelligence SDK. Add it to your app to collect visit and movement data
through UWB (DL-TDoA) indoor positioning, and receive zone enter / exit / dwell events
on device. Same server contract and (with three platform-forced exceptions, see
[CHANGELOG](CHANGELOG.md)) the same public API as the iOS SDK.

---

## Requirements

| Item | Requirement |
|---|---|
| Positioning | **Android 17 (API 37)+** · UWB **DL-TDoA** capable device |
| Package | Android 8.1 (API 27)+ — the app runs normally on unsupported devices, only the SDK stays inactive |
| Build | `compileSdk` / `targetSdk` 37, JVM target 17 |
| Language | Works from Java 8+ / Kotlin 1.9+ apps |

You also need keys and a configured space before the SDK does anything useful:

| Prerequisite | Where |
|---|---|
| SDK key (`ock_sdk_…`) | OneS1ght Console → **Mobile SDK** |
| Building · floor · locator setup | Your platform administrator (done at install) |
| Zones | OneS1ght Console → **Space** |

---

## Step 1: Project Setup

Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("co.onecheck.ones1ght:android:0.0.1")
}
```

> ⚠️ The distribution repository is **not finalized yet** — the coordinates above are
> correct, but where to resolve them from is provisional. Ask your OneS1ght contact for
> the current repository before wiring this into a CI build.

The library's own manifest declares `RANGING`, `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION` and `INTERNET` —
these are merged into your app automatically. You do not add them yourself.

---

## Step 2: SDK Initialization

Call this once at app start. It verifies the key, confirms the backend is reachable, and
receives tenant settings.

```kotlin
import co.onecheck.ones1ght.android.OneS1ght

try {
    OneS1ght.initialize(
        context = applicationContext,
        sdkKey = "ock_sdk_…",
    )
} catch (e: Exception) {
    // E1002 (invalid key) · E1003 (positioning disabled) · E5001 (network) …
}
```

```java
// Java
OneS1ght.initialize(context, "ock_sdk_…", new Callback<Void>() {
    @Override public void onSuccess(Void result) { /* initialized */ }
    @Override public void onError(Throwable error) { /* E1002 · E1003 · E5001 */ }
});
```

> This is the only key you pass. Everything else positioning and maps need is **served by
> the console** — you do not embed it in the app, and changing it does not require a new
> app release. A platform administrator sets it in the console.

⚠️ `initialize` does **not** look up buildings or floors. Space selection is a separate
step (Step 5) — only your app knows which floor to use.

**Expected logs**

```
[I1001] Initialized — tenant=itoku
```

### Check device support first

```kotlin
when (OneS1ght.deviceAvailability) {
    DeviceAvailability.AVAILABLE            -> { }
    DeviceAvailability.OS_VERSION_TOO_LOW   -> showNotice("Requires Android 17 or later")
    DeviceAvailability.DEVICE_NOT_SUPPORTED -> showNotice("This device does not support UWB")
}
```

This never throws and makes no network call. Read it **after `initialize`** — the chip
check needs the app context that `initialize` (or `permissions(activity)`) hands over.
Read before that on Android 17+, it cannot tell and answers `DEVICE_NOT_SUPPORTED`
(with a WARN in `onDebugLog`).

`initialize` starts the chip check in the background, and the answer is remembered for the
life of the process (a chip that never answers is remembered as not supported after 5
seconds). Reads after that return immediately; only a read that races that very first
check waits for it, for up to 5 seconds.

---

## Step 3: Permissions

Android requests the positioning permissions in one call — there is no separate
"location manager" step like on iOS.

```kotlin
when (OneS1ght.permissions(activity)) {
    PermissionStatus.AUTHORIZED  -> { /* ready to start positioning */ }
    PermissionStatus.DENIED      -> showSettingsGuide()      // no re-prompt — send to Settings
    PermissionStatus.UNSUPPORTED -> showUnsupportedNotice()
}
```

```java
// Java
OneS1ght.permissions(activity, new Callback<PermissionStatus>() {
    @Override public void onSuccess(PermissionStatus status) {
        if (status == PermissionStatus.AUTHORIZED) { /* ready */ }
    }
    @Override public void onError(Throwable error) { }
});
```

`permissions(activity)` requests `RANGING` + `ACCESS_FINE_LOCATION` together through
`ActivityResultRegistry`, so it is safe to call any time after `onCreate`. It also asks for
`ACCESS_COARSE_LOCATION`, because Android 12+ only offers precise location when approximate
location is requested alongside it.

⚠️ Positioning needs **precise** location. If the user picks "Approximate" in the system
prompt, the result is `DENIED`.

⚠️ If `deviceAvailability != AVAILABLE`, this returns `UNSUPPORTED` immediately with no
system prompt. If `RANGING` and precise location are already granted, it returns `AUTHORIZED`
immediately, also with no prompt. Otherwise the request times out after 30 seconds and
resolves to `DENIED`.

⚠️ Once denied, guide the user to your app's Settings screen — the system does not offer
a second in-app prompt for a permission the user has already refused.

---

## Step 4: Profile

The server issues a `profileId`. **Store it in your app and reuse it** — it is the key
that visit and movement data is attributed to.

```kotlin
// First run only — issue and store (SharedPreferences, etc.)
val profileId: String = savedProfileId ?: OneS1ght.createProfile(
    mapOf(
        "gender" to "F",
        "ageBand" to "20s",       // age band, not exact age
    ),
)

// Every run — attach the stored value
OneS1ght.identify(profileId)
```

Your member ID never reaches OneS1ght — only `profileId` does. You keep the mapping.

⚠️ Use **age bands** rather than exact ages. Gender + exact age + interests + movement
paths combined can become re-identifiable.

⚠️ `identify` must be called after `initialize`. Calling it earlier means the value does
not take effect.

| Function | Purpose |
|---|---|
| `createProfile(attrs)` | Create, returns `profileId` |
| `getProfile(id)` | Read |
| `putProfile(id, attrs)` | Replace all attributes |
| `deleteProfile(id)` | Delete |
| `identify(profileId)` | Attach — required before positioning |

---

## Step 5: Select Space (required)

```kotlin
val buildings = OneS1ght.buildings()
val floors = OneS1ght.floors(buildings[0].id)

OneS1ght.setFloorMap(floors[0], buildingId = buildings[0].id)
```

`setFloorMap` fetches locators, the UWB session ID and zones, then injects them into the
positioning pipeline. Calling it again while running switches floors — the session stays.

⚠️ **Unlike iOS, Android does not discover the floor by itself.** The iOS engine finds
the floor from locators advertising over BLE; the Android pipeline has no equivalent
path. If you `begin()` without calling `setFloorMap` first, positioning runs but produces
no coordinates, and `E3001` (WARN) is logged once. Calling `setFloorMap` is not optional.

### Drawing the map

```kotlin
// floors() returns floors with image == null to keep the list light.
// Fetch the single floor you are drawing — it comes from cache, so no extra round trip.
val floor = OneS1ght.floor(buildingId, floorId)

mapView.setBackground(
    floor.image,          // ByteArray? — PNG bytes
    minX = floor.minX, minY = floor.minY,
    maxX = floor.maxX, maxY = floor.maxY,
)
```

**Expected logs**

```
[I3001] Floor set — building=B1 floor=9f3a1c2e locators=4 zones=3
```

If something is missing you get a code instead:

```
[E3003] No UWB session on floor — floor=9f3a1c2e
```

---

## Step 6: Start Positioning

```kotlin
val session = OneS1ght.floorSession()

session.onZoneEnter = ZoneListener { zone -> showCoupon(zone) }
session.onZoneExit  = ZoneListener { zone -> hideCoupon(zone) }
session.onZoneDwell = DwellListener { zone, seconds -> logDwell(zone, seconds) }
session.onPosition  = PositionListener { coord -> mapView.moveMarker(coord) }
session.onTriggers  = TriggersListener { zoneId, triggers -> handle(triggers) }

session.begin()
…
OneS1ght.floorSession().end()
```

```java
// Java
FloorSession session = OneS1ght.floorSession();
session.setOnPosition(coord -> mapView.moveMarker(coord));
session.setOnZoneEnter(zone -> showCoupon(zone));

session.begin(new Callback<Void>() {
    @Override public void onSuccess(Void result) { /* positioning started */ }
    @Override public void onError(Throwable error) {
        // SdkError subclasses — check with instanceof
    }
});
```

`floorSession()` always returns the same instance — the UWB radio, judgement engine and
coordinate buffer are one per device, so multiple sessions would physically collide.

⚠️ **Call `begin()` after the permission is granted.** Without the permission, `begin()`
does not throw: the session starts but produces no positions, and `E2003` is logged. A
second `begin()` while that session is still running is ignored — so after the user grants
the permission, call `end()` first and then `begin()` again.

ℹ️ `begin(provider)` (a custom or mock positioning source, for tests and demos) feeds
positions only. `onZoneEnter` · `onZoneExit` · `onZoneDwell` come from the SDK's built-in
positioning (`begin()`) and do not fire for a custom provider.

### Pausing is not stopping

```kotlin
session.pause()      // stop showing/collecting — the engine keeps running
session.resume()     // resumes instantly — locators are not re-acquired
session.isPaused
```

| | `pause()` | `end()` |
|---|---|---|
| Position callbacks | stop | stop |
| Zone enter/exit | stop | stop |
| Upload to server | stop | flush, then stop |
| Engine · floor · locators | **kept** | released |
| Cost of coming back | instant | locators found from scratch |

Use `pause()` for "stop showing my position for a moment". `end()` is for leaving the
space. Resuming clears the judgement state, so the first zone event after `resume()`
re-establishes where you are.

**Expected logs**

```
[I4001] Positioning started — visitor=v-20260928-001
[I4002] Positioning ended — visitor=v-20260928-001 points=240
```

---

## API Reference

| Group | API |
|---|---|
| Setup | `initialize(context, sdkKey, baseUrl)` · `permissions(activity)` · `reset()` |
| Profile | `createProfile(attrs)` · `getProfile(id)` · `putProfile(id, attrs)` · `deleteProfile(id)` · `identify(profileId)` |
| Space | `buildings()` · `building(id)` · `floors(buildingId)` · `floor(b, f)` · `zones(b, f)` · `zone(b, f, z)` · `locators(b, f)` |
| Floor | `setFloorMap(floor, buildingId)` · `refreshZones()` |
| Positioning | `floorSession()` → `begin()` · `end()` · `pause()` · `resume()` · `isPaused` |
| Session callbacks | `onZoneEnter` · `onZoneExit` · `onZoneDwell` · `onPosition` · `onTriggers` · `onConfigChanged` |
| Buffer | `send()` (upload now) · `empty()` (discard) |
| Status | `isInitialized` · `isDeviceAvailable` · `deviceAvailability` · `onDebugLog` · `setLanguage(code)` · `SDK_VERSION` |
| Console-provided values | `googleMapKey` |

⚠️ `empty()` **discards** buffered coordinates without sending. Use `send()` to upload.

⚠️ `googleMapKey` is the only console value your app touches. The positioning license and
the space-service address are used inside the SDK only and are not exposed — your app
neither needs them nor has to manage them.

### Java and Kotlin

Every asynchronous public function is available two ways, under the same name:

- Kotlin: `suspend fun foo(...): T` — call it from a coroutine.
- Java: `fun foo(..., callback: Callback<T>)` — the callback runs on the main thread.
  When there is no return value, the callback type is `Callback<Void?>` (Java sees
  `Callback<Void>`), so you never have to handle `Unit.INSTANCE`.

ℹ️ If you call a Java callback variant on the main thread and the call finishes without
waiting on anything (for example an immediate `SdkError.NotInitialized`), the callback may
run **before the call returns**. Do not rely on code after the call running first.

Event callbacks (`onZoneEnter`, `onPosition`, …) are `fun interface` types, so both a
Kotlin lambda (`ZoneListener { zone -> … }`) and a Java lambda
(`session.setOnZoneEnter(zone -> …)`) work. `SdkError` and `ApiError` are `Exception`
subclasses, so Java code can branch on them with `instanceof`.

---

## Appendix

### How data flows

```
initialize ─→ begin ─→ [UWB coordinates] ─┬─→ onPosition            (your app)
                                           ├─→ buffer → server      (batched)
                                           └─→ zone judgement ─┬─→ onZoneEnter/Exit
                                                               └─→ server → onTriggers
```

`onZoneEnter` fires immediately from on-device judgement. `onTriggers` arrives after the
server responds — if the network is down you get the former but not the latter.

### Batching

| Trigger | Value |
|---|---|
| Count | 300 points |
| Interval | 60 seconds |
| Background | pause + flush |
| `end()` | flush remainder |

⚠️ UWB is **foreground only** on Android. Positioning stops in the background and resumes
when you return — this is a platform limit, same as iOS.

⚠️ The buffer is in memory. Coordinates not yet uploaded are lost if the app is killed.

---

## Troubleshooting

Every failure carries a code. Include it when contacting support.

| Symptom | Codes | First check |
|---|---|---|
| App runs but no coordinates | `E3001` · `E3003` · `E4002` | Floor set (`setFloorMap`)? → UWB session? → locator placement |
| Zone events never fire | `E3004` | Are zones registered in Console? Were they there **when positioning started**? |
| Fails on specific devices | `E2001` · `E2002` | Android 17 / UWB DL-TDoA capable? |
| Permission prompt never returns | `E2003` | Denied once — guide to Settings |
| 401 right after integration | `E1002` | Key status and environment (production/development) |
| Data missing in Console | `E5001` · `E5006` | Network → batching |

| Code | Meaning |
|---|---|
| `E1001` | SDK not initialized |
| `E1002` | Invalid or revoked SDK key |
| `E1003` | Positioning disabled for tenant |
| `E1004` | No profile attached |
| `E1007` | Positioning key unavailable from console |
| `E2001` | Android version too low |
| `E2002` | Device does not support UWB |
| `E2003` | Positioning permission denied |
| `E3001` | No floor set — **WARN, this is expected until `setFloorMap` is called** |
| `E3002` | No locators on floor |
| `E3003` | No UWB session on floor |
| `E3004` | No zones on floor |
| `E3006` | Locator lookup failed (map still renders) |
| `E4001` | UWB session failed |
| `E4002` | No position fix |
| `E4003` | Some locators not received — **WARN, positioning continues** |
| `E4004` | Zone judgement failed for one sample |
| `E5001` | Network failure |
| `E5002` | Server error |
| `E5003` | Payload mismatch |
| `E5004` | Forbidden resource |
| `E5005` | Response decoding failed |
| `E5006` | Pending coordinates dropped |

Codes `E3007` and `E3008` exist for the automatic BLE floor-detection path (on iOS, the
engine finds and tracks the floor from locator advertisements). They are reserved but not
raised by the Android SDK, since your app selects the floor itself through `setFloorMap` —
there is no automatic detection step to fail.

Code `E3009` exists for a different thing: mapping an engine-reported area **name** to a
console zone. On iOS the positioning engine judges zone entry/exit against its own
geofences and reports them by name, so the SDK has to match that name back to a console
zone id — `E3009` fires when it can't. It is also reserved but not raised on Android,
because zone judgement runs on-device directly against the zone geometry fetched from the
console — there is no separate name to match.

Errors are also uploaded to the Console log analyzer, where tenant administrators can see
them without touching the app.

### Seeing SDK logs during development

```kotlin
OneS1ght.onDebugLog = DebugLogListener { level, message -> Log.d("OneS1ght", "[$level] $message") }
```

⚠️ Leave this unset in production.

---

## Support

onesight-support@onecheck.co.kr
