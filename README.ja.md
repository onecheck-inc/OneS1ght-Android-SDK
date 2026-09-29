# OneS1ght SDK — Android (Kotlin)

[English](README.md) | [한국어](README.ko.md) | **日本語**

📖 ドキュメント: https://docs.ones1ght.com/ja/sdk/integration/android

屋内位置インテリジェンス SDK です。アプリに組み込むと UWB（DL-TDoA）屋内測位により
訪問・動線データを収集し、ゾーンの入場・退場・滞在イベントを端末上で直接受け取れます。
サーバー契約は iOS SDK と同一で、公開 API も(プラットフォームの制約による 3 点の例外を
除き、[CHANGELOG](CHANGELOG.md) 参照)同じです。

---

## 動作要件

| 項目 | 要件 |
|---|---|
| 測位 | **Android 17 (API 37) 以上** ・ UWB **DL-TDoA** 対応端末 |
| パッケージ導入 | Android 8.1 (API 27) 以上 — 非対応端末でもアプリは正常に動作し、SDK のみ無効になります |
| ビルド環境 | `compileSdk` / `targetSdk` 37、JVM target 17 |
| 言語 | Java 8+ / Kotlin 1.9+ のアプリで利用可能 |

SDK が実際に動作するには、キーと空間設定が先に用意されている必要があります。

| 事前準備 | 取得場所 |
|---|---|
| SDK キー (`ock_sdk_…`) | OneS1ght コンソール → **モバイル SDK** |
| 建物・フロア・ロケーターの設置 | 統合管理者(導入時に実施) |
| ゾーン | OneS1ght コンソール → **空間管理** |

---

## Step 1: プロジェクト設定

アプリモジュールの `build.gradle.kts` に依存関係を追加します。

```kotlin
dependencies {
    implementation("com.ones1ght.sdk:android:0.0.2")
}
```

SDK は **Maven Central** で配布されています — リポジトリを別途追加する必要はありません。新しい
Android プロジェクトには最初から含まれています。含まれていない場合は `settings.gradle.kts` に
次の設定があることを確認してください。

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

> 0.0.1 から更新する場合: 変わったのは座標だけです(`co.onecheck.ones1ght:android` →
> `com.ones1ght.sdk:android`)。パッケージ名と API は同じで、コードの修正は不要です。

ライブラリ自身のマニフェストが `RANGING` ・ `ACCESS_FINE_LOCATION` ・
`ACCESS_COARSE_LOCATION` ・ `INTERNET` 権限を
アプリに自動的にマージします。アプリ側で個別に宣言する必要はありません。

---

## Step 2: SDK の初期化

アプリ起動時に 1 回呼び出します。キーを検証し、バックエンドへの到達を確認し、
テナント設定を受け取ります。

```kotlin
import co.onecheck.ones1ght.android.OneS1ght

try {
    OneS1ght.initialize(
        context = applicationContext,
        sdkKey = "ock_sdk_…",
    )
} catch (e: Exception) {
    // E1002(キー無効) ・ E1003(測位無効) ・ E5001(ネットワーク) など
}
```

```java
// Java
OneS1ght.initialize(context, "ock_sdk_…", new Callback<Void>() {
    @Override public void onSuccess(Void result) { /* 初期化完了 */ }
    @Override public void onError(Throwable error) { /* E1002 · E1003 · E5001 */ }
});
```

> アプリが渡すキーはこれ一つだけです。測位と地図に必要な残りのキーは**コンソールが
> 配信します** — アプリに埋め込む必要はなく、値を変更してもアプリを再配布する必要は
> ありません。統合管理者がコンソールで設定します。

⚠️ `initialize` は建物・フロアを**取得しません**。空間の選択は別ステップ(Step 5)です —
どのフロアを使うかはアプリだけが知っているためです。

**想定されるログ**

```
[I1001] Initialized — tenant=itoku
```

### 端末の対応可否を先に確認

```kotlin
when (OneS1ght.deviceAvailability) {
    DeviceAvailability.AVAILABLE            -> { }
    DeviceAvailability.OS_VERSION_TOO_LOW   -> showNotice("Android 17 以上が必要です")
    DeviceAvailability.DEVICE_NOT_SUPPORTED -> showNotice("この端末は UWB に対応していません")
}
```

throw せず、ネットワークにもアクセスしません。**`initialize` の後に**読んでください —
チップの確認には `initialize`(または `permissions(activity)`)が渡すアプリの Context が
必要です。それより前に Android 17 以上で読むと判定できず `DEVICE_NOT_SUPPORTED` を
返します(`onDebugLog` に WARN が残ります)。

`initialize` がチップの確認をバックグラウンドで先に始め、答えはプロセスが生きている間
記憶します(5 秒以内に答えないチップは非対応として記憶します)。それ以降の読み取りは
すぐに返ります。最初の確認が終わる前に読んだ場合だけ、その答えを最大 5 秒待ちます。

---

## Step 3: 権限

Android は測位に必要な権限を一度に要求します — iOS のように位置情報の権限を
先に個別取得するステップはありません。

```kotlin
when (OneS1ght.permissions(activity)) {
    PermissionStatus.AUTHORIZED  -> { /* 測位開始可能 */ }
    PermissionStatus.DENIED      -> showSettingsGuide()      // 再要求は不可 — 設定アプリへ誘導
    PermissionStatus.UNSUPPORTED -> showUnsupportedNotice()
}
```

```java
// Java
OneS1ght.permissions(activity, new Callback<PermissionStatus>() {
    @Override public void onSuccess(PermissionStatus status) {
        if (status == PermissionStatus.AUTHORIZED) { /* 開始可能 */ }
    }
    @Override public void onError(Throwable error) { }
});
```

`permissions(activity)` は `ActivityResultRegistry` を通じて `RANGING` +
`ACCESS_FINE_LOCATION` をまとめて要求するため、`onCreate` 以降であればいつ呼び出しても
安全です。`ACCESS_COARSE_LOCATION` も一緒に要求します — Android 12 以降は、おおよその
位置情報を同時に要求しないと正確な位置情報を選べないためです。

⚠️ 測位には**正確な**位置情報が必要です。ユーザーがシステムダイアログで「おおよそ」を
選ぶと、結果は `DENIED` です。

⚠️ `deviceAvailability != AVAILABLE` の場合、システムダイアログなしで即座に
`UNSUPPORTED` を返します。`RANGING` と正確な位置情報が既に許可されている場合も、ダイアログなしで即座に
`AUTHORIZED` を返します。それ以外は 30 秒応答がないと `DENIED` として確定します。

⚠️ 一度拒否されると、システムは再度ダイアログを表示しません — アプリの設定画面へ
誘導してください。

---

## Step 4: プロフィール

サーバーが `profileId` を発行します。**アプリで保存して再利用してください** —
訪問・動線データはこのキーに紐づきます。

```kotlin
// 初回のみ — 発行して保存(SharedPreferences など)
val profileId: String = savedProfileId ?: OneS1ght.createProfile(
    mapOf(
        "gender" to "F",
        "ageBand" to "20s",       // 正確な年齢ではなく年代
    ),
)

// 起動のたびに — 保存済みの値を紐づける
OneS1ght.identify(profileId)
```

貴社の会員 ID が OneS1ght に送られることはありません。送られるのは `profileId` のみで、
その対応関係は貴社のみが保持します。

⚠️ 年齢は**年代**で入力することを推奨します。性別・正確な年齢・関心事・動線が組み合わ
さると再識別の可能性が生じます。

⚠️ `identify` は `initialize` の後に呼び出してください。それより前に呼ぶと値が
反映されません。

| 関数 | 用途 |
|---|---|
| `createProfile(attrs)` | 作成 — `profileId` を返す |
| `getProfile(id)` | 取得 |
| `putProfile(id, attrs)` | 属性の全置換 |
| `deleteProfile(id)` | 削除 |
| `identify(profileId)` | 紐づけ — 測位前に必須 |

---

## Step 5: 空間の選択(必須)

```kotlin
val buildings = OneS1ght.buildings()
val floors = OneS1ght.floors(buildings[0].id)

OneS1ght.setFloorMap(floors[0], buildingId = buildings[0].id)
```

`setFloorMap` はロケーター・UWB セッション ID・ゾーンを取得して測位パイプラインに
注入します。実行中に再度呼び出すとフロアが切り替わり、セッションはそのまま維持されます。

⚠️ **iOS と異なり、Android はフロアを自動的には見つけません。** iOS のエンジンは
ロケーターが BLE で発信する信号からフロアを検出しますが、Android のパイプラインには
その経路がありません。`setFloorMap` を呼ばずに `begin()` すると測位自体は動きますが
座標が出力されず、`E3001`(WARN)が一度記録されます。省略できないステップです。

### 地図の描画

```kotlin
// floors() は一覧を軽く保つため image が空です。
// 描画するフロアのみ単体で再取得します — キャッシュから返るため追加リクエストは
// 発生しません。
val floor = OneS1ght.floor(buildingId, floorId)

mapView.setBackground(
    floor.image,          // ByteArray? — PNG バイト
    minX = floor.minX, minY = floor.minY,
    maxX = floor.maxX, maxY = floor.maxY,
)
```

**想定されるログ**

```
[I3001] Floor set — building=B1 floor=9f3a1c2e locators=4 zones=3
```

不足しているものがある場合はコードが代わりに出力されます。

```
[E3003] No UWB session on floor — floor=9f3a1c2e
```

---

## Step 6: 測位の開始

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
    @Override public void onSuccess(Void result) { /* 測位開始 */ }
    @Override public void onError(Throwable error) {
        // SdkError のサブクラス — instanceof で分岐
    }
});
```

`floorSession()` は常に同じインスタンスを返します — UWB 無線・判定エンジン・座標
バッファは端末ごとに 1 つのため、セッションが複数あると物理的に競合します。

⚠️ **`begin()` は権限を得てから呼んでください。** 権限なしで呼んでも `begin()` は throw
しません — セッションは始まりますが座標は出ず、`E2003` が残ります。そのセッションが動いて
いる間に再度呼んだ `begin()` は無視されるため、ユーザーが権限を許可した後は、まず `end()`
を呼んでから `begin()` を呼び直してください。

ℹ️ `begin(provider)`(テスト・デモ用のカスタム・Mock 測位ソース)は座標だけを供給します。
`onZoneEnter` ・ `onZoneExit` ・ `onZoneDwell` は SDK 内蔵の測位(`begin()`)からのみ届き、
カスタム provider では発火しません。

### 一時停止は終了ではありません

```kotlin
session.pause()      // 座標表示・送信・ゾーン判定のみ停止(エンジンは動き続ける)
session.resume()     // 即座に再開 — ロケーターは再取得しない
session.isPaused
```

| | `pause()` | `end()` |
|---|---|---|
| 位置コールバック | 停止 | 停止 |
| ゾーン入場/退場 | 停止 | 停止 |
| サーバー送信 | 停止 | 残りを送信後に停止 |
| エンジン・フロア・ロケーター | **維持** | 解放 |
| 復帰コスト | 即座 | ロケーターを最初から再取得 |

「一時的に自分の位置表示を止める」には `pause()` を、空間を離れるときは `end()` を
使います。再開すると判定状態がリセットされるため、`resume()` 後の最初のゾーン
イベントは現在位置を改めて確定します。

**想定されるログ**

```
[I4001] Positioning started — visitor=v-20260928-001
[I4002] Positioning ended — visitor=v-20260928-001 points=240
```

---

## 主な API

| 区分 | API |
|---|---|
| 初期化 | `initialize(context, sdkKey, baseUrl)` · `permissions(activity)` · `reset()` |
| プロフィール | `createProfile(attrs)` · `getProfile(id)` · `putProfile(id, attrs)` · `deleteProfile(id)` · `identify(profileId)` |
| 空間取得 | `buildings()` · `building(id)` · `floors(buildingId)` · `floor(b, f)` · `zones(b, f)` · `zone(b, f, z)` · `locators(b, f)` |
| フロア指定 | `setFloorMap(floor, buildingId)` · `refreshZones()` |
| 測位 | `floorSession()` → `begin()` · `end()` · `pause()` · `resume()` · `isPaused` |
| セッションコールバック | `onZoneEnter` · `onZoneExit` · `onZoneDwell` · `onPosition` · `onTriggers` · `onConfigChanged` |
| バッファ | `send()`(送信) · `empty()`(破棄) |
| 状態 | `isInitialized` · `isDeviceAvailable` · `deviceAvailability` · `onDebugLog` · `setLanguage(code)` · `SDK_VERSION` |
| コンソール提供値 | `googleMapKey` |

⚠️ `empty()` はバッファ内の座標を**送信せずに破棄します。** 送信は `send()` です。

⚠️ アプリが直接扱うコンソール値は `googleMapKey` の一つだけです。測位ライセンスと
空間サービスのアドレスは SDK 内部でのみ使用し、外部には公開しません。

### Java と Kotlin

すべての非同期公開関数は同じ名前で 2 通り用意されています。

- Kotlin: `suspend fun foo(...): T` — コルーチンから呼び出します。
- Java: `fun foo(..., callback: Callback<T>)` — コールバックはメインスレッドで
  呼ばれます。戻り値がない場合は `Callback<Void?>`(Java では `Callback<Void>`)を
  使うため、`Unit.INSTANCE` を扱う必要はありません。

ℹ️ Java のコールバック版をメインスレッドで呼び、その呼び出しが何も待たずに終わる場合
(例: 即座に発生する `SdkError.NotInitialized`)、コールバックは**呼び出しが戻る前に**
呼ばれることがあります。呼び出しの次の行が先に実行されると仮定しないでください。

イベントコールバック(`onZoneEnter`、`onPosition` など)は `fun interface` のため、
Kotlin の SAM 変換(`ZoneListener { zone -> … }`)と Java のラムダ
(`session.setOnZoneEnter(zone -> …)`)のどちらも自然に使えます。`SdkError`・
`ApiError` は `Exception` のサブクラスなので、Java で `instanceof` により分岐
できます。

---

## 付録

### データの流れ

```
initialize ─→ begin ─→ [UWB 座標] ─┬─→ onPosition            (アプリ)
                                    ├─→ バッファ → サーバー   (バッチ)
                                    └─→ ゾーン判定 ─┬─→ onZoneEnter/Exit
                                                    └─→ サーバー → onTriggers
```

`onZoneEnter` は端末上の判定直後に発火します。`onTriggers` はサーバー応答後に届くため、
ネットワークが切断されている場合は前者のみ届きます。

### バッチ送信

| トリガー | 値 |
|---|---|
| 件数 | 300 件 |
| 間隔 | 60 秒 |
| バックグラウンド移行 | 測位停止 + 残りを送信 |
| `end()` | 残りを送信 |

⚠️ Android の UWB も**フォアグラウンド専用**です。バックグラウンドでは測位が停止し、
復帰時に再開されます — iOS と同じプラットフォームの制約です。

⚠️ バッファはメモリ上にあります。アプリが強制終了されると未送信の座標は失われます。

---

## トラブルシューティング

すべての失敗にはコードが付きます。お問い合わせの際に併せてお知らせください。

| 症状 | コード | 最初に確認すること |
|---|---|---|
| アプリは動くが座標が出ない | `E3001` · `E3003` · `E4002` | フロア指定(`setFloorMap`) → UWB セッション → ロケーター配置 |
| ゾーンイベントが発火しない | `E3004` | コンソールにゾーンが登録されているか、**測位開始時点**に存在したか |
| 特定の端末でのみ動作しない | `E2001` · `E2002` | Android 17 / UWB DL-TDoA 対応端末か |
| 権限ダイアログが再表示されない | `E2003` | 既に拒否済み — 設定アプリへ誘導 |
| 連携直後に 401 | `E1002` | キーの状態・環境(production/development) |
| コンソールにデータが表示されない | `E5001` · `E5006` | ネットワーク → バッチ間隔 |

| コード | 意味 |
|---|---|
| `E1001` | SDK が未初期化 |
| `E1002` | SDK キーが無効または失効 |
| `E1003` | テナントで測位が無効 |
| `E1004` | プロフィール未連携 |
| `E1007` | コンソールから測位キーを取得できない |
| `E2001` | Android バージョン不足 |
| `E2002` | UWB 非対応端末 |
| `E2003` | 測位権限が拒否された |
| `E3001` | フロア未指定 — **WARN、`setFloorMap` 呼び出し前は正常** |
| `E3002` | フロアにロケーターがない |
| `E3003` | フロアに UWB セッションがない |
| `E3004` | フロアにゾーンがない |
| `E3006` | ロケーター取得失敗(地図は正常) |
| `E4001` | UWB セッション失敗 |
| `E4002` | 座標が算出されない |
| `E4003` | 一部のロケーターが受信できない — **WARN、測位は継続します** |
| `E4004` | その回のゾーン判定失敗 |
| `E5001` | ネットワーク失敗 |
| `E5002` | サーバーエラー |
| `E5003` | リクエスト形式の不一致 |
| `E5004` | 権限のないリソースへのアクセス |
| `E5005` | レスポンスの解析失敗 |
| `E5006` | 未送信の座標が失われた |

`E3007` ・ `E3008` は(iOS が使用する)BLE によるフロア自動検出用にコードのみ
予約されています — エンジンがロケーターの発信信号からフロアを検出・追跡する経路です。
Android はアプリが `setFloorMap` でフロアを直接指定するため自動検出のステップ自体が
存在せず、このコードは発生しません。

`E3009` は別の用途です — エンジンが報告する領域**名**をコンソールのゾーンに対応づける
コードです。iOS の測位エンジンは自身のジオフェンスを基準にゾーンの入場・退場を判定し、
その結果を名前で報告するため、SDK はその名前をコンソールのゾーン ID に逆引きする必要が
あり、対応が取れないと `E3009` が発生します。このコードも予約のみで Android では
発生しません — Android のゾーン判定は、コンソールから取得したゾーンの図形データと
端末上で直接照合するため、対応づけるべき別名が存在しないからです。

エラーはコンソールのログ分析にも送られるため、テナント管理者はアプリを介さずに確認
できます。

### 開発中に SDK ログを見る

```kotlin
OneS1ght.onDebugLog = DebugLogListener { level, message -> Log.d("OneS1ght", "[$level] $message") }
```

⚠️ 本番環境では登録しないことを推奨します。

---

## ライセンス

OneS1ght SDK は、OneCheck Inc. とサービス契約を締結した OneS1ght のお客様に使用が許諾される
プロプライエタリソフトウェアです。全文は [LICENSE](LICENSE) をご覧ください。SDK に同梱される、
または SDK が利用するサードパーティ製コンポーネントは、それぞれのライセンスに従います。

---

## お問い合わせ

onesight-support@onecheck.co.kr
