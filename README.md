# OneS1ght SDK — Android (Kotlin)

**English** | [한국어](README.ko.md) | [日本語](README.ja.md)

📖 Documentation: https://docs.ones1ght.com/en/sdk/integration/android

Indoor location intelligence SDK. Add it to your app to collect visit and movement data
through UWB (DL-TDoA) indoor positioning, and receive zone enter / exit / dwell events
on device. Same server contract, the same positioning structure (the engine finds the floor
over BLE and judges zones itself) and (with two platform-forced exceptions, see
[CHANGELOG](CHANGELOG.md)) the same public API as the iOS SDK.

---

## Requirements

| Item | Requirement |
|---|---|
| Positioning | **Android 17 (API 37)+** · UWB **DL-TDoA** capable device · Bluetooth LE (floor detection) |
| Package | **Android 8.0 (API 26)+** (`minSdk 26`). Below Android 17, or on devices without UWB, the app runs normally; only positioning stays inactive (`OS_VERSION_TOO_LOW` / `E2001`) |
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
    implementation("com.ones1ght.sdk:android:0.0.6")
}
```

The SDK is published to **Maven Central** — no extra repository is needed. New Android
projects already list it; if yours does not, make sure `settings.gradle.kts` has:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Your app module needs `minSdk = 26` or higher. Positioning itself runs only on Android 17 (API 37)+ —
below that `deviceAvailability` is `OS_VERSION_TOO_LOW`, `permissions(activity)` returns `UNSUPPORTED`
without a prompt, and `begin()` throws `SdkError.OsVersionTooLow` (`E2001`). Everything else
(initialize, spaces, profiles) works on every supported OS.

> Upgrading from 0.0.5? Nothing to change — Bluetooth being off is now logged as `E2004` instead of `E2003`
> (positioning permission denied). If you checked `E2003` in `onDebugLog` for that, check `E2004` too — see [CHANGELOG](CHANGELOG.md).
> Upgrading from 0.0.4? Nothing to change — 0.0.5 only adds API (an app can now create the positioning
> provider itself and observe its state; see [CHANGELOG](CHANGELOG.md)).
> Upgrading from 0.0.3? Nothing to change — you may lower your app's `minSdk` back (26+).
> Upgrading from 0.0.2? `permissions(activity)` now also asks for
> `BLUETOOTH_SCAN`, and `setFloorMap` became optional — see [CHANGELOG](CHANGELOG.md).
> Upgrading from 0.0.1? The coordinates also changed (`co.onecheck.ones1ght:android` →
> `com.ones1ght.sdk:android`); package names are the same.

The library's own manifest declares `RANGING`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_NETWORK_STATE` —
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

This never throws, makes no network call and never waits. Read it **after `initialize`** —
the UWB check needs the app context that `initialize` (or `permissions(activity)`) hands
over. Read before that, it cannot tell and answers `DEVICE_NOT_SUPPORTED` (with a WARN in
`onDebugLog`).

It checks that the device has a UWB chip. The rare device that has the chip but not DL-TDoA
support is caught when positioning starts, and logged as `E2002`.

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

`permissions(activity)` requests `RANGING` (UWB) + `ACCESS_FINE_LOCATION` + `BLUETOOTH_SCAN`
(nearby devices — the engine detects the floor over BLE) together through
`ActivityResultRegistry`, so it is safe to call any time after `onCreate`. It also asks for
`ACCESS_COARSE_LOCATION`, because Android 12+ only offers precise location when approximate
location is requested alongside it.

⚠️ Positioning needs **precise** location and **nearby devices**. If the user picks
"Approximate" or refuses nearby devices, the result is `DENIED`.

⚠️ If `deviceAvailability != AVAILABLE`, this returns `UNSUPPORTED` immediately with no
system prompt. If all three are already granted, it returns `AUTHORIZED`
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

`identify` may be called before or after `initialize` — the value carries over to the session,
including after `reset()` or re-initializing with another key.

| Function | Purpose |
|---|---|
| `createProfile(attrs)` | Create, returns `profileId` |
| `getProfile(id)` | Read |
| `putProfile(id, attrs)` | Replace all attributes |
| `deleteProfile(id)` | Delete |
| `identify(profileId)` | Attach — required before positioning |

---

## Step 5: Select Space (optional)

```kotlin
val buildings = OneS1ght.buildings()
val floors = OneS1ght.floors(buildings[0].id)

OneS1ght.setFloorMap(floors[0], buildingId = buildings[0].id)
```

The positioning engine finds the floor by itself over BLE, the same as iOS — you can
`begin()` without a floor and coordinates still come out (`E3001` WARN is logged once; that
is the normal path). If no floor is found within 20 seconds, `E3007` is logged.

`setFloorMap` fetches the floor's locators, UWB session ID and zones. Call it when you use
**zone events**: the engine reports zone entry/exit by area **name**, and the SDK matches that
name to a console zone of the selected floor to get the zone id it sends to the server
(`E3009` when a name has no match). If the floor the engine detects differs from the one you
set, `E3008` is logged. Calling it again while running switches floors — the session stays.

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
session.onStopped   = SessionStoppedListener { showRestart() } // engine stopped and could not restart — session closed, call begin() again

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

`floorSession()` always returns the same instance — the UWB radio, positioning engine and
coordinate buffer are one per device, so multiple sessions would physically collide.

⚠️ **Call `begin()` after the permission is granted.** Without the permission, `begin()`
does not throw: the session starts but produces no positions, and `E2003` is logged. A
second `begin()` while that session is still running is ignored — so after the user grants
the permission, call `end()` first and then `begin()` again.

ℹ️ `begin(provider)` (a custom or mock positioning source, for tests and demos) feeds
positions only. `onZoneEnter` · `onZoneExit` · `onZoneDwell` come from the SDK's built-in
positioning (`begin()`) and do not fire for a custom provider.

ℹ️ **Watching the positioning engine yourself (0.0.5+).** A map screen that needs the engine's
own state can create the built-in provider and pass it in — it is treated exactly like `begin()`
(same device check, zone listeners, debug log), and your hooks on the provider are kept:

```kotlin
if (UwbPositioningProvider.isSupported(context)) {
    val provider = UwbPositioningProvider(context)
    provider.onFloorDetected = FloorDetectedListener { floorId -> /* auto-select the floor */ }
    OneS1ght.floorSession().begin(provider)
    provider.latestPositionFlow.collect { position -> /* draw "me" */ }
}
```

`phase` · `isRunning` · `isPaused` · `latestPosition` · `detectedFloorId` · `measurementCount` · `log` are
available as getters and as `StateFlow`s (`…Flow`); Java apps use the getters plus `setOnChange(…)`.

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
| Cost of coming back | instant | floor and locators found from scratch |

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
| Session callbacks | `onZoneEnter` · `onZoneExit` · `onZoneDwell` · `onPosition` · `onTriggers` · `onConfigChanged` · `onFloorDetected` · `onStopped` |
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
| App runs but no coordinates | `E3007` · `E2004` · `E2003` · `E4002` | Floor found over BLE (nearby devices allowed, Bluetooth on)? → locator placement |
| Zone events never fire | `E3009` · `E3004` | Floor set (`setFloorMap`)? Do console zone names match the installed areas? |
| Data lands on another floor | `E3008` | The floor set in the app vs. the floor the engine detected |
| Fails on specific devices | `E2001` · `E2002` | Android 17 / UWB DL-TDoA capable? |
| Permission prompt never returns | `E2003` | Denied once — guide to Settings |
| Started with Bluetooth off | `E2004` | Turn Bluetooth on in Quick Settings → start positioning again |
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
| `E2004` | Bluetooth is off |
| `E3001` | No floor set — **WARN, expected: the engine finds the floor over BLE** |
| `E3002` | No locators on floor |
| `E3003` | No UWB session on floor |
| `E3004` | No zones on floor |
| `E3006` | Locator lookup failed (map still renders) |
| `E3007` | Floor not detected over BLE within 20 s — also raised when the device rate-limits BLE scan starts (context `engine=13`; starting again after a moment clears it) |
| `E3008` | Engine floor differs from the floor set in the app |
| `E3009` | Engine area name matches no console zone — event not sent |
| `E4001` | UWB session failed |
| `E4002` | No position fix |
| `E4003` | Some locators not received — **WARN, positioning continues** |
| `E4004` | Zone judgement failed in the positioning engine |
| `E5001` | Network failure |
| `E5002` | Server error |
| `E5003` | Payload mismatch |
| `E5004` | Forbidden resource |
| `E5005` | Response decoding failed |
| `E5006` | Pending coordinates dropped |

Errors are also uploaded to the Console log analyzer, where tenant administrators can see
them without touching the app.

### Seeing SDK logs during development

```kotlin
OneS1ght.onDebugLog = DebugLogListener { level, message -> Log.d("OneS1ght", "[$level] $message") }
```

⚠️ Leave this unset in production.

---

## License

The OneS1ght SDK is proprietary software licensed to OneS1ght customers under their
service agreement with OneCheck Inc. See [LICENSE](LICENSE) for the full terms.
Third-party components bundled with or used by the SDK remain under their own licenses.

---

## Support

onesight-support@onecheck.co.kr
