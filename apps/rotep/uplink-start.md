# 送話の出だしの乱れ（早送りのように聞こえる）を調べる手順

11/9 の協力者との通話（direct の方式）で、最初の方の AI の声が「大量の文章が高速で流れた」ように乱れ、後半ははっきり聞き取れた。端末の記録と AOSP 相当のソースで確かめたこと、入れた直し、次の試験の手順をまとめる。

ソースの版：`.reference/` の frameworks-av c50595c（2026-11-02）、frameworks-base 138e1794（2026-11-06）。Nothing の端末の audioserver が同じ動きかは確かめていない。

## 1. 分かったこと

### 事実（AOSP 相当のソース）

- `dumpsys audio` の `port updated` は `PLAYER_UPDATE_PORT_ID`（`AudioPlaybackConfiguration.java:1107`）で、`AudioTrack::createTrack_l` が出す（`AudioTrack.cpp:2008`）。`restoreTrack_l` は `createTrack_l` を呼ぶ（`AudioTrack.cpp:2924`）。連続の `port updated` は、同じ `AudioTrack` が作り直され続けた印
- 作り直しのとき、古いトラックのバッファのまだ鳴っていない音は失われる（`AudioTrack.cpp:2927-2932`）。作り直しは次の `write` の中（`obtainBuffer`、`AudioTrack.cpp:2221`）や `getPosition`（1623-1624）で起きる
- 新しいトラックが鳴り始めるのは、バッファに start threshold の分たまってから（`Tracks.cpp:1377-1401`）。既定の start threshold はバッファ全部（`Threads.cpp:2915`、`Tracks.cpp:982`、`AudioTrackShared.cpp:755`）。これまでの送話のバッファは 300 ms だった
- 再生中のトラックのデータが切れて再生できないままだと、`BUFFER TIMEOUT` で mixer から外れる（`Threads.cpp:6365-6371`）。外れると `stopOutput`（`Threads.cpp:4873`）が呼ばれる。`TYPE_TELEPHONY` に向けた `USAGE_VOICE_COMMUNICATION` のトラックは INCALL_MUSIC の出力を排他で使う（`AudioPolicyManager.cpp:1530-1536`）ので、止まるときに `stopSource` → `checkStrategyRoute` → `invalidateStreams`（`AudioPolicyManager.cpp:2976-2979`、`6326-6327`）で自分のトラックも無効になり、次の `write` で作り直される。AI の発話の出だしごとに 1 回の作り直し（12:45:15、18、21、33、44、56）は、この流れで説明できる
- 割り込みの flush（pause → flush → play）の時刻には、記録上 `port updated` はない（12:45:16、19）。pause が mixer に処理されて止まれば、次の `write` で 1 回作り直される

### 推測

- 12:44:59〜12:45:02 の 0.12 秒ごとの連続の作り直しの引き金：新しいトラックが start threshold（300 ms）までたまる前に `BUFFER TIMEOUT` で止まって作り直す繰り返し（再生待ちのタイマーは、HAL の `write` がブロックしない状態だと 50 回 × 約 2.5 ms = 約 125 ms で、観測の 0.12 秒と合う。ブロックしていれば約 625 ms で起きない）。別の候補の「古いトラックの `stopOutput` が新しいトラックの `startOutput` より先に走る連鎖」は、起きにくいと見ている（未確認）
- 早送りのように聞こえた見込み：作り直しのたびにバッファの中の音が捨てられて、話の内容が飛び飛びに進む。または HAL の出力が一時的に実時間で進まない。今回の記録では区別できない

### 不明

- この端末の HAL の `write` の実時間性、mix period（通話中の `dumpsys media.audio_flinger` で分かる）、なぜ最初の発話のときだけ連続したか

## 2. 入れた直し（direct の方式だけ。診断の試験で使う他の 2 方式は動きを変えない）

- **無音でつなぎ続ける**（`UplinkFeeder.forTelephonyOutput`、`keepOutputAlive`）：AI が話していない間も 20 ms の無音の小片を実時間で送る（先行は 80 ms）。トラックが止まらないので、発話の出だしごとの作り直しがなくなる見込み。無音は `isPlaying` に数えない（`halfDuplex` と `waitForLastWords` はこれまでどおり）。割り込みの flush は今までどおり（そのあと 1 回の作り直しは起こりうる）
- **start threshold を下げる**（`UPLINK_START_THRESHOLD_MS` = 60 ms、`track.setStartThresholdInFrames`）：300 ms 全部たまるのを待たず 60 ms で鳴り始める。作り直しや flush のあとの再開が短くなり、AI の声の出だしも今より早くなる
- **声の先行を 300 ms に**（`leadMs = UPLINK_BUFFER_MS`）：start threshold を下げると再生中のバッファの余裕が減るので、声については今の余裕（300 ms）を保つ。これと keep-alive、start threshold の 3 つは結びついている（どれか 1 つだけだと、今うまくいっている後半の余裕を下げるか、同じ競合を残す）
- **通話の終わりの報告**（`ShellAudioPath.sendUplink`）：無音を常に書いているので、手伝い役が閉じたパイプへの書き込み失敗が報告より先に起きることがある。受話の側と同じように、少し待ってから失敗と判断する
- **記録を足した**（`UplinkStats`）：手伝い役が 1 秒ごとに、下の表の値を `RotepHelper` のログと `/data/local/tmp/rotep-uplink-stats.csv` に残す（前の通話の分は `.prev.csv` に 1 世代）。音声、番号、会話の文は入らない

## 3. 次の試験の手順

### 通話の前に、PC の PowerShell で流す（通話が終わるまで動かしておく）

端末の main のログは約 1 分で消えるので、PC に取り続ける。

```powershell
adb logcat -v threadtime -s AudioTrack:I AudioTrackShared:W AudioFlinger:I APM_AudioPolicyManager:D AudioPolicyService:I RotepHelper:I Rotep/call:I Rotep/audio:I | Tee-Object -FilePath uplink-logcat.txt
```

通話のあと `Ctrl+C` で止める。

### 通話中に別の PowerShell で 1 回（AI が話している間に）

```powershell
adb shell dumpsys media.audio_flinger | Out-File -Encoding utf8 uplink-flinger.txt
```

`incall_music` の出力スレッドの `HAL frame count` と `Normal frame count` が mix period（48 kHz で何ms ごとか）。

### 通話のあとに読む

```powershell
adb shell cat /data/local/tmp/rotep-uplink-stats.csv | Set-Content uplink-stats.csv
adb shell dumpsys media.metrics | Out-File -Encoding utf8 uplink-metrics.txt
adb shell dumpsys audio | Out-File -Encoding utf8 uplink-audio.txt
Select-String -Path uplink-logcat.txt -Pattern "dead IAudioTrack","BUFFER TIMEOUT","Invalidate clients"
```

`uplink-stats.csv`（1 秒ごと）の列：`sec`（経過秒）、`writes`、`frames`（書いたフレーム）、`voice_frames`（無音でない小片のフレーム）、`head_advance`（再生位置の進み）、`underruns_new`（アンダーランの増え）、`port_changed`（作り直しの印。読めないときは -1）、`short_writes`、`failed_writes`、`flushes`、`longest_write_ms`。

`dumpsys media.metrics` の `restore` の項目は、作り直しの記録（どこから起きたか）がログより長く残る。

### 見ること

| 見るもの | 直っているとき | まだ問題があるとき |
| --- | --- | --- |
| logcat の `dead IAudioTrack ... creating a new one from ...` | 通話の最初に 1〜2 回、割り込みのあと 1 回まで。`from getPosition()` は記録の読み取りが作り直しを先に起こしただけで、1 回の作り直しの数え方は同じ | 0.1〜0.2 秒ごとに続く（`from` の名前でどこから起きたかが分かる） |
| logcat の `BUFFER TIMEOUT: remove track` | 無音でつないでいるので、ほぼ出ない | 作り直しの前に繰り返し出る（連続の原因の第一候補） |
| CSV の `port_changed` | 通話の最初以外はほとんど 0 | 1 が続く秒がある（`dumpsys audio` の `port updated` も連続） |
| CSV の `head_advance` | 毎秒 24000 に近い（代替の 48 kHz なら 48000） | 大きく超える秒（作り直しの飛び、または HAL が実時間でない）、大きく足りない秒 |
| CSV の `underruns_new` | 声も無音も、ほぼ 0 | 増える秒がある（書くのが追いついていない） |
| CSV の `longest_write_ms` | 短い（20 ms 前後まで） | 100 ms を超える（作り直しや HAL で止まっている） |
| 無音だけの秒（`voice_frames` が 0）の `underruns_new` と `head_advance` | `underruns_new` が 0、`head_advance` がレートに近い | そうでないとき、無音の先行（80 ms）や start threshold（60 ms）を見直す。`uplink-flinger.txt` の mix period と比べる |
| 協力者の聞こえ方 | 最初の発話からはっきり聞き取れる | どの秒に乱れたか、上の値と時刻で合わせて報告する |

乱れが残ったら、`uplink-logcat.txt`、`uplink-stats.csv`、`uplink-flinger.txt` を持ち帰る。番号は入っていない（タグで絞っている）が、貼る前に中を見る。

## 4. 確かめていないこと（実機の試験が要る）

2026-11-09 21:51 の協力者との電話（「全自動で電話をかける」）では、最初の AI の声がはっきり聞こえ（協力者の話）、送話の統計にも乱れはなかった。1 回の通話の結果なので、下の項目はそのまま残す。

- 上の直しで連続の作り直しが実際になくなるか。Nothing の端末の audioserver が AOSP と同じ流れか
- 無音を流すと INCALL_MUSIC の出力が通話の間ずっと動くことで、HAL がマイクの音の混ぜ方を変えないか（AI の通話はマイクを止める設定なら関係ない。マイクを生かす使い方をするときは、相手に聞いて確かめる）
- 通話が終わって `write` がリトライで止まるとき（10 回 × 150 ms、`AudioTrack.cpp:2914-2915`、2990-2993）、送話のスレッドが最大 1.5 秒遅れて終わる。受話の側が先に報告するので問題はない見込み。その間に次の通話を始めると「すでに動いている」で断られるが、実際には起きない間隔
