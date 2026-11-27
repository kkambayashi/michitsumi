# 本番デプロイの手順書

本番の Cloud Run サービス `road-collector`（東京 `asia-northeast1`、プロジェクト `hackathon-2026-513703`）に、サーバを出すときの手順。
初回のセットアップ（API の有効化、Artifact Registry、認証ヘルパ）は済んでいるので、ここには**2 回目以降の手順**だけを書く。初回は `server/README.md`。

- 本番 URL：`https://road-collector-415368216017.asia-northeast1.run.app`（アプリの `SERVER_BASE_URL`。古い形の `https://road-collector-3v3ltz2gla-an.a.run.app` も同じサービスにつながる）
- お試し用（非公開。呼ぶにはログインが要る）：`road-collector-preview`。本番に出す前にここで確かめる
- サーバは、専用のサービスアカウント `road-collector-server@hackathon-2026-513703.iam.gserviceaccount.com` で動かす。権限は Firestore の読み書き（`roles/datastore.user`）と Gemini（`roles/aiplatform.user`）だけ。既定の Compute のアカウントは権限を外したので、`--service-account` を付け忘れると Firestore も Gemini も使えない
- 文中の `#番号` は、ハッカソン中のチームのリポジトリ（README の「ハッカソン時点の開発リポジトリ」）の issue と PR
- 所要時間：ビルド＋デプロイで 5 分、確認を入れて 20〜30 分

## 0. 出す前に

- [ ] 出したい PR がすべて**CI 通過・マージ済み**で、手元の `main` が最新（`git switch main && git pull`）
- [ ] `./gradlew :server:test` が手元で通る
- [ ] 必要なら、先にお試し用で確かめる（手順 A）
- [ ] **送るタワーの形（`core/model` の `Tower`・`Placement`）が変わる変更は、新しいアプリを使う前にサーバを出す。** 古いサーバは知らない項目を読み飛ばして保存するので、その間に送ったタワーは、デプロイしたあとも古い形のまま残る（例：#71 の `Placement.scale`。デプロイ前に新しいアプリで送ったタワーは、ずっと 1 倍の大きさで描かれる）。エミュレータや端末に新しいアプリを入れるのも、デプロイのあとにする

## A. お試し用で確かめる（任意だが、大きな変更のときは必ず）

```bash
TOKEN=$(gcloud auth print-access-token)
./gradlew :server:jib \
  --image=asia-northeast1-docker.pkg.dev/hackathon-2026-513703/road-collector/server:preview \
  -Djib.to.auth.username=oauth2accesstoken -Djib.to.auth.password="$TOKEN"

gcloud run deploy road-collector-preview \
  --image=asia-northeast1-docker.pkg.dev/hackathon-2026-513703/road-collector/server:preview \
  --region=asia-northeast1 --no-allow-unauthenticated \
  --service-account=road-collector-server@hackathon-2026-513703.iam.gserviceaccount.com \
  --set-env-vars=TOWER_COLLECTION=towers_preview --quiet
```

お試し用はログインが要るので、呼ぶときは ID トークンを付ける：

```bash
IT=$(gcloud auth print-identity-token)
U=https://road-collector-preview-415368216017.asia-northeast1.run.app
curl -H "Authorization: Bearer $IT" $U/towers
```

保存先はコレクション `towers_preview` なので、本番の `towers` には混ざらない。

## B. 本番に出す

```bash
# 0. 出す前に、今どの版が動いているかを記録しておく（D で戻す先になる）
gcloud run services describe road-collector \
  --project=hackathon-2026-513703 --region=asia-northeast1 \
  --format='yaml(status.latestReadyRevisionName,status.traffic)'
#   status.traffic の revisionName と percent を控える。これが「動作確認済みの版」

# 1. イメージをビルドして push（タグは latest。Docker 不要）
TOKEN=$(gcloud auth print-access-token)
./gradlew :server:jib \
  --image=asia-northeast1-docker.pkg.dev/hackathon-2026-513703/road-collector/server \
  -Djib.to.auth.username=oauth2accesstoken -Djib.to.auth.password="$TOKEN"

# 2. デプロイ（環境変数は何も要らない。Cloud Run 上では Firestore と Gemini が自動で選ばれる）
gcloud run deploy road-collector \
  --image=asia-northeast1-docker.pkg.dev/hackathon-2026-513703/road-collector/server \
  --region=asia-northeast1 --allow-unauthenticated \
  --service-account=road-collector-server@hackathon-2026-513703.iam.gserviceaccount.com \
  --memory=512Mi --max-instances=2 --quiet
```

- `--min-instances` は**ここでは指定しない**。最小台数は版（リビジョン）ごとの設定で、指定しなければ前の版の値が次の版にも引き継がれる。ここで `0` と書いてしまうと、E で `1` にした設定が次のデプロイで `0` に戻り、アクセスが途切れたあとの最初の 1 回が起動待ち（アプリの送信は 10 秒で諦めるので失敗しうる）になる。最小台数を変えるのは E のときだけ
- `Service URL` が今までと同じ `https://road-collector-415368216017.asia-northeast1.run.app` で出ることを確かめる（変わっていたらアプリ側の設定も変える必要がある）
- D で古い版にトラフィックを固定したあとなら、デプロイしただけでは URL はまだ古い版を向いている。D の後半の手順で最新に切り替える

## C. 本番で確かめる（毎回やる）

```bash
U=https://road-collector-415368216017.asia-northeast1.run.app

# 起動ログ：保存先と命名の部品が意図どおりか
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="road-collector" AND textPayload=~"TowerStore:|PartNamer:|RateLimit:"' \
  --limit 5 --freshness=10m --format="value(textPayload)"
#   期待：TowerStore: firestore（project=hackathon-2026-513703, database=(default), collection=towers）
#         PartNamer: Gemini（project=hackathon-2026-513703, location=global, model=gemini-3.5-flash-lite）
#         RateLimit: on（1 分あたり 端末ごと / 1 台の合計：投稿 10 / 120、一覧 60 / なし、名前 20 / 120、ニックネーム 10 / 60）
#   回数制限で困ったとき（人が一度に集まるときなど）は、--update-env-vars=RATE_LIMIT=off で出し直すと数えなくなる（戻すときは --remove-env-vars=RATE_LIMIT）

# トラフィックが新しい版に 100% 向いているか（D で古い版に固定したままだと、ここで古い版の名前が出る）
gcloud run services describe road-collector \
  --project=hackathon-2026-513703 --region=asia-northeast1 \
  --format='yaml(status.latestReadyRevisionName,status.traffic)'

curl -s $U/health                      # ok
curl -s $U/towers                      # 一覧（JSON）。Firestore 化後は再デプロイしても消えない

# 命名（名前が返る。503 ならサーバのログで「パーツの名前を考えるのが時間切れ」「〜に失敗」「パーツの名前なし」「聞き直してもパーツの名前が長すぎる」のどれかを見る）
curl -s -X POST $U/parts/name -H "Content-Type: application/json" \
  -d '{"id":"p1","points":[{"x":-0.5,"y":0},{"x":0,"y":0.3},{"x":0.5,"y":0}],"kind":"ZIGZAG","distanceM":820,"walkedAt":1793952000000}'

# ニックネームの案（名前が返る。503 ならサーバのログで「ニックネームを考えるのが時間切れ」「〜に失敗」「ニックネームなし」「聞き直してもニックネームが長すぎる」のどれかを見る）
curl -s -X POST $U/nickname -H "Content-Type: application/json" \
  -d '{"partNames":["晩秋のジグザグ","深夜の縦線"],"partCount":5,"totalDistanceM":2400}'

# エラーのログが出ていないか。このサーバのログは素のテキスト（logback）で severity が付かないので、
# 本文の ERROR / WARN も検索する。WARN も混ざるので、出ただけで失敗と決めず中身を見る
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="road-collector" AND (severity>=ERROR OR textPayload=~" (ERROR|WARN) ")' \
  --project=hackathon-2026-513703 --limit=20 --freshness=10m \
  --format='yaml(timestamp,resource.labels.revision_name,severity,textPayload,jsonPayload.message,httpRequest.status)'
```

そのあと**アプリから**：デモ散歩 → パーツに名前が付く → 積む → 終わりのカードの「保存」 → 「みんな」に出る。

送信したタワーの ID（送信結果の `id`、または共有 URL の末尾）で、共有ページと画像をサーバから取れることを確かめてから LINE / X に貼る。プレビューが出ないとき、サーバ側か SNS 側かを切り分けるため：

```bash
ID='送信結果に含まれる実際の id'

# 共有ページが、公開 URL の同じ ID の image.png を参照していること
curl -fsS "$U/towers/$ID" | grep -F '<meta property="og:image"'

# 200 と image/png であること。HEAD（curl -I）には応えないので、GET で本文を捨てる
curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' "$U/towers/$ID/image.png"
```

最後に共有 URL を LINE / X に貼って絵が出る（見た目と SNS 側の表示は、貼って目で見るしかない）。


## D. 問題が出たら、前の版に戻す（1 分）

Cloud Run は古い版（リビジョン）を残しているので、イメージを作り直さずに戻せる。

戻す先は「1 つ前の版」ではなく、**B の 0 で控えた、動作確認済みの版**。環境変数の変更（F）や最小台数の変更（E）でも版は増えるので、直前の版が確認済みとは限らない。

```bash
# 版の一覧（新しい順）。参考に見る
gcloud run revisions list --service road-collector --region asia-northeast1

# 控えておいた版に全部のトラフィックを戻す
gcloud run services update-traffic road-collector --region asia-northeast1 \
  --to-revisions=<控えた版の名前>=100
```

戻したあと直すときは、次の順でやる（古い版への固定は、その後のデプロイにも引き継がれるので、B を出しただけでは URL はまだ古い版を向いている）：

1. 原因を直して、手順 B で修正版をデプロイする
2. トラフィックを最新の版に切り替える
   ```bash
   gcloud run services update-traffic road-collector --region asia-northeast1 --to-latest
   ```
3. 割り当て先を確認する。`status.traffic` が新しい版に 100%、`latestRevision: true` になっていること
   ```bash
   gcloud run services describe road-collector \
     --project=hackathon-2026-513703 --region=asia-northeast1 \
     --format='yaml(status.latestReadyRevisionName,status.traffic)'
   ```
4. 手順 C で実際の動作を確かめる。切り替え直後に問題が出たら、控えておいた版へもう一度戻す

**注意**：Firestore 化より前の版（メモリ保存）に戻すと、保存したタワーが見えなくなる（消えてはいない。Firestore 版に戻せばまた見える）。

## E. 人が多く使う日（発表や紹介の前）

```bash
# 前日の夜か当日の朝：常時 1 台を起動しておき、最初のアクセスの待ち時間（10 秒ほど）をなくす
gcloud run services update road-collector --region asia-northeast1 --min-instances=1

# 終わったら戻す（クレジット節約。1 台で 1 日 数十円）
gcloud run services update road-collector --region asia-northeast1 --min-instances=0
```

当日の朝に、手順 C の確認をもう一度やる。

### 変なタワーが投稿されたとき（消し方）

`POST /towers` は誰でも叩けるので、いたずらや壊れたタワーが「みんな」に並ぶことがある（壊れたデータは #58 の検査で 400 になるが、中身がまともな「変なタワー」は通る）。消すのは Firestore の文書を 1 つ消すだけ。サーバの再起動やデプロイは要らない。

```bash
# 1. 消したいタワーの id を知る（「みんな」のカードをタップすると開く共有 URL の末尾、または一覧）
curl -s $U/towers

# 2. Firestore の文書を消す（REST。gcloud に Firestore の文書を消すコマンドは無い）
ID='消したいタワーの id'
curl -sS -o /dev/null -w '%{http_code}\n' -X DELETE \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://firestore.googleapis.com/v1/projects/hackathon-2026-513703/databases/(default)/documents/towers/$ID?currentDocument.exists=true"
#   200 なら消えた。末尾の currentDocument.exists=true は「その id の文書があるときだけ消す」の条件で、
#   id を打ち間違えると 404（No document to update）で止まる。これが無いと、無い id でも 200 が返って気づけない。
#   消す前に GET（-X DELETE と末尾の条件を外す）で中身を見て、消したいタワーか確かめる

# 3. 一覧から消えたこと
curl -s $U/towers | grep -c "$ID"     # 0
```

- コンソールからでもよい：Firestore → データ → `towers` → 文書の id → 「文書を削除」
- 消したあとも、アプリの「みんな」は開き直す（取り直す）まで前の一覧を出す。共有ページと PNG は 404 になる
- 共有 URL が LINE や X に貼られていた場合、そのプレビュー（題名と絵）は相手側に保存されているので、消してもしばらく残る。開くと 404 になる
- 本番の `towers` を消すのはこの手順だけ。試し打ちのデータは、手元のサーバ（メモリ版）か `towers_localtest` で作り、本番には入れない

## F. 設定を変えるだけのとき（コードの変更なし）

環境変数は、イメージを作り直さずに変えられる。変えると新しい版が自動で立ち上がる。

```bash
# 例：Gemini のモデルを替える
gcloud run services update road-collector --region asia-northeast1 \
  --update-env-vars GEMINI_MODEL=gemini-3.5-flash,GEMINI_LOCATION=asia-northeast1
# 元に戻す
gcloud run services update road-collector --region asia-northeast1 \
  --remove-env-vars GEMINI_MODEL,GEMINI_LOCATION
```

使える環境変数は `server/README.md`（`TOWER_STORE` / `TOWER_COLLECTION` / `PART_NAMER` / `GEMINI_MODEL` / `GEMINI_LOCATION` / `PUBLIC_BASE_URL`）。

## G. データを戻す（Firestore のバックアップから）

Firestore（`(default)`）は、毎日バックアップを取って 7 日分残している（2026-11-27 から）。データベースには削除保護をかけてあるので、データベースごと消すことはできない。タワーを 1 基ずつ消したり書き換えたりするのは、今までどおりできる。

バックアップは、今のデータベースに直接は書き戻せない。**新しいデータベースに復元してから、要るものだけ本番に移す。** サーバはいつも `(default)` を読み書きするので、復元したデータベースを本番がそのまま使うことはない。

- 復元（1）と全部を戻す（3）は、まだ実際に試していない（2026-11-27 時点）。コマンドの形は gcloud のヘルプで確かめた。2 の「読んで本番に書く」は、`towers_preview` で別の id に写して、サーバから読めることを確かめた
- 費用：保管は 1 GiB あたり月 0.03 ドルほど、復元は 1 GiB あたり 0.2 ドルほど。今のデータは 1 MB に届かないので、どちらも 1 円未満

### 1. バックアップを選んで、新しいデータベースに復元する

```bash
P=hackathon-2026-513703

# 毎日の予定（retention: 604800s = 7 日）
gcloud firestore backups schedules list --database='(default)' --project=$P

# バックアップの一覧。snapshotTime が「いつの状態か」
gcloud firestore backups list --project=$P --location=asia-northeast1 \
  --format='table(name.basename(),database,snapshotTime,state)'

# 選んだバックアップを、新しいデータベースに復元する（名前は自由。ここでは restore-日付）
R=restore-20261127
gcloud firestore databases restore --project=$P \
  --source-backup=projects/$P/locations/asia-northeast1/backups/<バックアップの ID> \
  --destination-database=$R
#   終わるまで少しかかる。終わったかは次で見る
gcloud firestore operations list --database=$R --project=$P
```

### 2. 要るタワーだけ本番に戻す（消してしまった 1 基を戻すなど）

```bash
COL=towers               # 3 分チャレンジは towers_challenge、毎日 1 つずつ積むは towers_daily
ID='戻したいタワーの id'
TOKEN=$(gcloud auth print-access-token)
BASE="https://firestore.googleapis.com/v1/projects/$P/databases"

# 復元したデータベースから読み、中身（fields）だけを取り出す
curl -sS -H "Authorization: Bearer $TOKEN" "$BASE/$R/documents/$COL/$ID" \
  | python3 -c 'import json,sys; print(json.dumps({"fields": json.load(sys.stdin)["fields"]}))' > /tmp/tower.json

# 本番に書く。末尾の currentDocument.exists=false は「その id の文書が今無いときだけ書く」の条件
#   （消してしまったものを戻すとき）。壊れた中身を前の状態に戻すときは、この条件を外して上書きする
curl -sS -o /dev/null -w '%{http_code}\n' -X PATCH \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data @/tmp/tower.json \
  "$BASE/(default)/documents/$COL/$ID?currentDocument.exists=false"
#   200 なら戻った。409 なら、その id の文書がもう本番にある（上書きしたいなら、条件を外す）

# 一覧に出る（サーバは一覧を 20 秒覚えているので、出るまで少し待つ）
curl -s https://road-collector-415368216017.asia-northeast1.run.app/towers | grep -c "$ID"
```

### 3. 全部を戻すとき

復元したデータベースを Cloud Storage に書き出し、本番に読み込む。読み込むと、同じ id の文書は上書きされる。バックアップのあとに増えた文書は、消えずに残る。

```bash
gcloud storage buckets create gs://$P-firestore-restore --location=asia-northeast1 --project=$P
gcloud firestore export gs://$P-firestore-restore/$R --database=$R --project=$P
gcloud firestore import gs://$P-firestore-restore/$R --database='(default)' --project=$P
```

- 書き出しや読み込みが権限のエラーで止まったら、Firestore のサービスエージェント（`service-415368216017@gcp-sa-firestore.iam.gserviceaccount.com`）に、このバケットの `roles/storage.admin` を付ける

### 4. 片付け

```bash
gcloud firestore databases delete --database=$R --project=$P
gcloud storage rm -r gs://$P-firestore-restore      # 3 で作ったときだけ
```

## お試し用を片付けるとき（しばらく使わないとき）

```bash
gcloud run services delete road-collector-preview --region asia-northeast1 --quiet
gcloud artifacts docker tags delete asia-northeast1-docker.pkg.dev/hackathon-2026-513703/road-collector/server:preview --quiet
```

また使うときは、手順 A でそのまま作り直せる（`--service-account` を付けるのを忘れない）。

Firestore の `towers_preview`・`towers_localtest` はコンソールから消す（本番の `towers` は、E の「変なタワーが投稿されたとき」の手順以外では消さない）。
