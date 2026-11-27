# ミチツミ（michitsumi）

歩いた道の形を積み木のパーツにして、バランスを取りながら塔を積む散歩アプリ（Android）。

DevFest GDGoC Japan Hackathon 2026 で、チーム Rein4ce（[@kkambayashi](https://github.com/kkambayashi)・[@mintBomber](https://github.com/mintBomber)・[@atvani](https://github.com/atvani)・[@suguru-t](https://github.com/suguru-t)）が 4 人で開発したものを元に、ハッカソン後からは [@kkambayashi](https://github.com/kkambayashi) が個人で開発を続けています。

- ハッカソン時点の開発リポジトリ：https://github.com/suguru-t/Rein4ce-devfest-2026
- ハッカソンの提出版：https://github.com/Devfest-Hackathon-2026/df-2026-t076
- ライセンス：MIT（[LICENSE](LICENSE)）

## できること

- **記録**：歩いた道（GPS）を記録して、積み木のパーツにする。AI（Gemini）がパーツに名前を付ける。「デモ散歩」や、GPX / JSON のファイルでも試せる
- **コレクション**：集めたパーツを、歩いたときの天気と時間帯の色で並べる。拡大して名前を付けたり、歩いた場所の地図や、足跡でなぞり直すのを見たりできる
- **積む**：パーツを台に積んで、高さを競う。遊び方は、通常・90 秒チャレンジ・毎日 1 つずつ積むの 3 つ。長く歩いた道ほど大きく、雨や雪の日の道ほど滑る
- **みんな**：送った塔を、遊び方ごとのランキングで比べる。共有ページを LINE や X に貼れる
- **そのほか**：今の天気を背景に降らせる。BGM・効果音・振動・ダークモードは設定で変えられる

画面ごとの詳しい説明は [docs/features.md](docs/features.md)。

## 動かし方

### アプリ

1. Android Studio（Narwhal 以降）で、このフォルダを開く
2. 地図のキーを、パソコンに 1 回だけ書く（下の「地図のキー」）。書かなくても動き、地図の代わりに軌跡を描く
3. Run。エミュレータでも実機でもよい（minSdk 26）

アプリは、本番のサーバ（`https://road-collector-415368216017.asia-northeast1.run.app`）につながる。手元のサーバにつなぐときだけ、`local.properties` に `SERVER_BASE_URL=http://10.0.2.2:8080` を書いて、Sync してから Run し直す（10.0.2.2 は、エミュレータから見たパソコン。`local.properties.example` も参照）。http でつなげるのは debug ビルドだけ。

**速く動かしたいときは release ビルド**：debug ビルドは Compose が遅く、エミュレータで起動に約 4.5 秒かかる（release なら約 2 秒）。

- Android Studio の Build Variants で `app` を `release` にして Run する。コマンドなら `./gradlew :app:assembleRelease`
- release も debug と同じ鍵で署名するので、上書きで入れられる
- ブレークポイントで止めて調べたいときは、debug に戻す
- 人に配るときやストアに出すときは、release の署名を本番用の鍵に替える（`app/build.gradle.kts` の `release`）

### 地図のキー（Maps API キー）

キーは、自分の Google Cloud のプロジェクトで作る。**リポジトリには入れない。**

- `~/.gradle/gradle.properties` に `MAPS_API_KEY=AIza…` の 1 行を書いて、Sync する。Windows は `C:\Users\<ユーザー名>\.gradle\gradle.properties`。ファイルが無ければ作る
  - パソコンのユーザーの設定なので、どのクローン・作業フォルダ（`git worktree`）でも読まれる
- **リポジトリの中の `gradle.properties`（このフォルダのいちばん上にある、同じ名前のファイル）には書かない。** git に入って、キーが公開されてしまう（うっかり書いても、ビルドはそこからは読まない）
- 読む順は、`local.properties` → `-PMAPS_API_KEY=…` → `~/.gradle/gradle.properties` → 環境変数 `MAPS_API_KEY`（GitHub Actions は Secrets から渡す）。空の `MAPS_API_KEY=` の行は、無いのと同じ
- キーの制限
  - 「Android アプリ」：パッケージ名 `io.github.kkambayashi.michitsumi` と、署名の鍵（パソコンごとの `~/.android/debug.keystore`）の SHA-1。SHA-1 は `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` で出る
  - 「API の制限」：Maps SDK for Android だけ。リポジトリが公開で、配った APK からキーを取り出せるため（地図の表示は課金されない）

`local.properties` の無い作業フォルダをコマンドでビルドして、「SDK location not found」で止まったら、環境変数 `ANDROID_HOME` に SDK の場所を設定する（Mac は `~/Library/Android/sdk`、Windows は `%LOCALAPPDATA%\Android\Sdk`）。

### サーバ

```
./gradlew :server:run      # → http://localhost:8080/towers
```

本番（Cloud Run）に出す手順と、戻し方は [docs/deploy.md](docs/deploy.md)。はじめのセットアップと環境変数は [server/README.md](server/README.md)。

### テスト

```
./gradlew :core:test               # 変換・物理・SVG・軌跡ファイル・天気・遊び方の進行
./gradlew :server:test             # API・保存・共有ページ・画像・回数制限
./gradlew :app:testDebugUnitTest   # アプリの保存・設定・ゲームの進行など（Android なしで動く）
```

`main` への push とプルリクエストのたびに、GitHub Actions がこの 3 つと、`server` のビルド、`app` の APK のビルドを回す（`.github/workflows/ci.yml`）。

- できた APK は、実行結果のページの Artifacts（`app-debug.apk`。14 日間）から取れる。本番のサーバにつながる
- ただし、署名が手元の鍵と違うので、地図は出ない（軌跡を描く）。手元でビルドしたアプリと入れ替えるときは、一度アンインストールする

## 構成

```
core/    io.github.kkambayashi.michitsumi.core（純 Kotlin。Android に依存しない）
  model/       Part, Pt, Kind, TrackPoint, Tower, Placement（データの形）
  geo/         軌跡 → パーツの変換（TrackFilter, Projection, Simplify, TrackToPart, ConversionParams）
  io/          GPX / JSON の軌跡ファイルの読み込み（TrackFileParser）
  physics/     dyn4j の物理（PartGeometry, PartBodyFactory, StackWorld）
  challenge/   90 秒チャレンジの進行
  daily/       毎日 1 つずつ積むの塔
  weather/     天気（Sky, WalkWeather, OpenMeteo）
  sample/      見本の軌跡とパーツ
  svg/         共有ページ用の絵（TowerSvg）

app/     io.github.kkambayashi.michitsumi
  collect/       GPS の記録（LocationRecordingService, TrackRecorder, DemoWalkPlayer）
  data/          スマホの中の保存（パーツ・設定・記録・持ち主の印）と天気の取得
  net/           サーバとの通信（ApiClient）
  audio/         BGM と効果音
  ui/            ホーム・タブ・起動のタイトル（AppNav, HomeScreen, TitleIntro）
  ui/collect/    記録
  ui/inventory/  コレクション
  ui/game/       積む（通常・90 秒チャレンジ・毎日）
  ui/gallery/    みんな（ランキング）
  ui/weather/    背景の天気
  ui/settings/   設定
  ui/common/     パーツの描き方・枠・注記など、画面をまたいで使う部品

server/  io.github.kkambayashi.michitsumi.server（Ktor。Cloud Run で動く）
  Routes.kt / PartRoutes.kt   API（/towers、/parts/name、/nickname など）
  TowerStore.kt               保存先（メモリ版。FirestoreTowerStore.kt が Firestore 版）
  PartNamer.kt                パーツの名前とニックネーム（Gemini 版と、名前を付けない版）
  SharePage.kt / TowerPng.kt  共有ページと、SNS のプレビューの絵
  RateLimit.kt / BodyLimit.kt / TowerValidation.kt   回数制限・本文の大きさ・送られた塔の検査
```

### データの形

パーツは、アプリ・サーバ・共有ページで同じ形を使う（`core/model/Part.kt`）。

```kotlin
data class Part(
    val id: String,
    val points: List<Pt>,     // 正規化済み。バウンディングボックス中心が原点、長辺 1.0、y 上向き。最大 24 個
    val thickness: Float,     // 太さ（長辺に対する比率、初期値 0.15）
    val closed: Boolean,      // 輪なら true
    val kind: Kind,           // BAR / L / U / RING / ZIGZAG / OTHER（表示用）
    val distanceM: Int,
    val walkedAt: Long,
    val name: String? = null, // Gemini 命名（任意）
)
```

- 生の GPS の点（`TrackPoint`）は、`Part` に入れない。サーバにも送らない
- 歩いた場所（`PartPlace`）と、歩いたときの天気（`WalkWeather`）も、`Part` に入れない。スマホの中の別のファイル（`parts_places.json`・`parts_weather.json`）に持つ。`Part` はタワーと一緒にサーバへ送られ、共有ページに出るので、入れると歩いた場所が分かってしまうため

### 権限

- Android 14 以降で要る `FOREGROUND_SERVICE_LOCATION` の権限と、`foregroundServiceType="location"` は設定済み
- バックグラウンドの位置の権限（`ACCESS_BACKGROUND_LOCATION`）は使わない。記録は、アプリを開いてボタンを押したときだけ始める
- http（暗号化しない通信）は、debug ビルドだけで許す（手元のサーバ `http://10.0.2.2` 用。`src/debug/res/xml/network_security_config.xml`）

## 資料

| ファイル | 中身 |
|---|---|
| [docs/features.md](docs/features.md) | 画面ごとの、機能の詳しい説明 |
| [docs/deploy.md](docs/deploy.md) | サーバを本番に出す手順、前の版に戻す手順、データを戻す手順 |
| [docs/security.md](docs/security.md) | セキュリティとプライバシーの対策（していること・していないこと） |
| [docs/decisions.md](docs/decisions.md) | 決めたことと、その理由（文中の「決定 ○○」） |
| [docs/challenge-mode.md](docs/challenge-mode.md) | 90 秒チャレンジの決まり |
| [docs/game-ruler.md](docs/game-ruler.md)・[docs/game-move-buttons.md](docs/game-move-buttons.md) | 積む画面の模型の高さと、左右ボタンを考えたときの記録 |
| [server/README.md](server/README.md) | サーバのセットアップ、環境変数、API |
