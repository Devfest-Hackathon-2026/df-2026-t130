# 配布の手順

アイてる!? の APK にリリース用の署名をして、GitHub Releases に出すまでの手順です。**公開する人が自分で打ちます。** コマンドは Windows の PowerShell 用です。

使う人向けの手順は [install.md](install.md)、使う前の注意は [safety.md](safety.md) にあります。

> [!IMPORTANT]
> リリース署名の APK は、まだスマホで試していません。確かめたのは、公開用に書き出したソースからのビルドと lint、署名の検査、エミュレーター（Android 16）での起動、PC なしのペアリングと手伝い役の起動、全自動の許可までです（2026-11-10）。通話と AI との会話は確かめていません。公開する前に [5. スマホで確かめる](#5-スマホで確かめる) を行ってください。

## 1. 先に知っておくこと

**署名の違う APK は上書きできません。** これまでの APK はデバッグ署名です。その上にリリース署名の APK は入りません（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。入れ替えはアンインストールになり、次のものが消えます。

| 消えるもの | そのあと |
| --- | --- |
| 保存した Gemini と Claude の API キー | 貼り直す |
| adb のペアリングの鍵（PC なしの起動を使っているとき） | ペアリングをやり直す |
| 全自動で電話をかける許可（`MANAGE_ONGOING_CALLS`）と、PC なしの起動で入れた `WRITE_SECURE_SETTINGS` | 手伝い役の起動と「全自動を許可」で入れ直す |
| マイク、電話、通知などの許可 | 聞かれたら許可し直す |
| 設定、前回の電話の結果、予約した電話 | 設定し直す。結果と予約は戻らない |

動いている手伝い役は、アプリが入れ替わると自分で終わります。入れ替えたあとに起動し直します。

提出や発表の練習は、デバッグ署名の APK のまま進めます。入れ替えは、上のことが分かったうえで、時間のあるときに行います。

ほかに、次のことも先に知っておいてください。

- **鍵をなくすと、同じアプリとして更新できません。** 使う人はアンインストールが必要になります。鍵のファイルとパスフレーズは、別々の場所に控えます
- 鍵のファイルとパスフレーズは、リポジトリ、チャット、AI のツールに渡しません。この文書のコマンドは、鍵の入力を画面に出さない形にしてあります
- 版の番号は `gradle.properties` の `incallagent.versionCode` と `incallagent.versionName` です。手伝い役は版の番号を見ていません（アプリを入れた時刻で照らし合わせます）
- **versionCode が低い APK には戻せません**（`INSTALL_FAILED_VERSION_DOWNGRADE`）。入っている APK がデバッグ版なら、`adb install -r -d <APK>` で戻せます。リリース版からは戻せないので、消して入れ直します

## 2. 用意するもの

- Android Studio（JDK と SDK が一緒に入ります。Build Tools は 37.0.0）
- Android SDK の場所が分かること。リポジトリの一番上の `local.properties` の `sdk.dir` か、環境変数 `ANDROID_HOME`
- GitHub CLI（`gh`）。アップロードに使います

## 3. 鍵を作る（最初の 1 回だけ）

鍵はリポジトリの外に置きます。

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
New-Item -ItemType Directory -Force "$HOME\aitel-keys" | Out-Null
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore "$HOME\aitel-keys\aitel-release.p12" -storetype PKCS12 -alias aitel -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 -dname "CN=AITEL"
```

- パスフレーズを 2 回聞かれます。画面には出ません。長いものにして、パスワードマネージャーに控えます
- `-dname` に個人の名前や住所を入れないでください。APK の証明書は、APK を持っている人なら誰でも読めます
- 鍵のファイルの写しを、USB メモリなど別の場所に 1 つ置きます

## 4. 署名してビルドする

**公開するコミットそのもの**でビルドします（[8 節](#8-ソースの公開)）。公開用のリポジトリの clone なら、そこに `local.properties` を置くか、環境変数 `ANDROID_HOME` を入れます。

環境変数を入れます。パスフレーズの入力は画面に出ません。

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:RELEASE_KEYSTORE = "$HOME\aitel-keys\aitel-release.p12"
$env:RELEASE_KEY_ALIAS = "aitel"
$secret = Read-Host "鍵のパスフレーズ" -AsSecureString
$env:RELEASE_KEYSTORE_PASSPHRASE = [System.Net.NetworkCredential]::new("", $secret).Password
$env:RELEASE_KEY_PASSPHRASE = $env:RELEASE_KEYSTORE_PASSPHRASE
Remove-Variable secret
```

| 環境変数 | 中身 |
| --- | --- |
| `RELEASE_KEYSTORE` | 鍵のファイルの場所（絶対パス） |
| `RELEASE_KEYSTORE_PASSPHRASE` | 鍵のファイルのパスフレーズ |
| `RELEASE_KEY_ALIAS` | 鍵の名前（上の手順なら `aitel`） |
| `RELEASE_KEY_PASSPHRASE` | 鍵のパスフレーズ（PKCS12 では鍵のファイルと同じもの） |

ビルドします。リポジトリの一番上で打ちます。

```powershell
.\gradlew.bat --no-daemon --max-workers=4 "-Pkotlin.compiler.execution.strategy=in-process" :apps:rotep:assembleRelease :apps:rotep:lintRelease --console=plain
```

終わったら、環境変数を消します。

```powershell
Remove-Item Env:\RELEASE_KEYSTORE, Env:\RELEASE_KEYSTORE_PASSPHRASE, Env:\RELEASE_KEY_ALIAS, Env:\RELEASE_KEY_PASSPHRASE
```

できる APK は `apps\rotep\build\outputs\apk\release\rotep-release.apk` です。名前に `-unsigned` が付いていたら署名されていません（`RELEASE_KEYSTORE` が入っていない）。未署名の APK はスマホに入りません。

署名なしのビルドのあとに署名ありでビルドすると、Windows では `Unable to delete directory` で止まることがあります。`apps\rotep\build\outputs\apk\release` と `apps\rotep\build\intermediates\incremental\packageRelease` を消して、もう一度ビルドします。

### APK を確かめる

`JAVA_HOME` が入っている窓で打ちます（`apksigner.bat` が使います）。SDK の場所が違うときは `$sdk` を変えます。

```powershell
$apk = "apps\rotep\build\outputs\apk\release\rotep-release.apk"
$sdk = "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0"
& "$sdk\apksigner.bat" verify --print-certs --verbose $apk
& "$sdk\aapt2.exe" dump badging $apk | Select-String "^package:|application-debuggable"
(Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
```

見るところ：

- 最初の行に `Verifies` が出て、`V2 Signer: certificate DN: CN=AITEL` が出ること。`CN=Android Debug` はデバッグ署名です
- `V2 Signer: certificate SHA-256 digest` の値（証明書の指紋）を控えます。秘密ではありません。次の版でも同じ値になります
- `package:` の行の `versionCode` と `versionName` が、出す版と同じこと
- `application-debuggable` の行が出ないこと
- 最後の行が APK の SHA-256 です

## 5. スマホで確かめる

**アプリのデータが消えます**（[1 節](#1-先に知っておくこと)）。電話はかけず、まず練習で確かめます。

```powershell
adb uninstall io.github.incallagent.rotep
adb install $apk
```

次の順に確かめます。

1. 「設定」で API キーを貼り、練習を 1 回最後まで通す（[install.md](install.md) の 5 節）
2. 手伝い役を起動する。「設定」の「困ったとき」の「診断の画面を開く」から開いた画面の「2. 手伝い役の状態」が「動いている」になり、`uid=2000` と出ること（[install.md](install.md) の 6 節）
3. 同意をもらった相手に 1 本電話して、AI の声が届き、結果の画面まで進むこと
4. 全自動を使うなら、許可を入れ直して、「全自動で電話をかける」を選んでも「許可がありません」と出ないこと

通らなければ、デバッグ版に戻して原因を調べます（`adb uninstall` のあとで `adb install`）。

## 6. GitHub Releases に上げる

**ここは公開する人が自分のターミナルで打ちます。** `OWNER/REPO` は公開するリポジトリに置き換えます。タグは、APK を作ったコミットに付けて、先に push しておきます。

配るものを、リポジトリの外のフォルダに集めます。

```powershell
$v = "0.9.0"
$d = "$HOME\aitel-release-$v"
New-Item -ItemType Directory -Force $d | Out-Null
Copy-Item apps\rotep\build\outputs\apk\release\rotep-release.apk "$d\aitel-$v.apk"
Copy-Item apps\rotep\helper\rotep-helper.sh, apps\rotep\server\aitel_setup.py, apps\rotep\server\aitel_relay.py, apps\rotep\mcp\aitel_mcp.py, LICENSE, apps\rotep\NOTICE, apps\rotep\licenses\* $d
if ((Get-Content -Raw "$d\rotep-helper.sh") -match "`r") { throw "rotep-helper.sh の改行が CRLF です" }
Get-ChildItem $d -File | Where-Object Name -ne "SHA256SUMS.txt" | ForEach-Object { "$((Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower())  $($_.Name)" } | Set-Content "$d\SHA256SUMS.txt" -Encoding ascii
Get-Content "$d\SHA256SUMS.txt"
```

置くものの使い道：

- `rotep-helper.sh`：PC の手順で手伝い役を起動するときに要ります。APK の中にも同じものが入っていますが（PC なしの手順で使います）、PC からは取り出せません
- `aitel_setup.py`、`aitel_relay.py`、`aitel_mcp.py`：ほかの AI から依頼を受ける人だけが使います。**APK とは別のもので、スマホには入れません。** サーバーを立てる人は、この 3 つを同じフォルダに置いて、[mcp-setup.md](mcp-setup.md) のとおりに進めます。PC の MCP で頼む人は `aitel_mcp.py` だけを使います（[apps/rotep/mcp/README.md](../apps/rotep/mcp/README.md)）

説明の文を `$d\notes.md` に書きます。例：

```markdown
使う前に docs/safety.md を読んでください。入れ方は docs/install.md にあります。

- 確かめた端末は Nothing Phone (2a)（Android 16）の 1 台だけです
- ファイルの SHA-256 は SHA256SUMS.txt にあります
- 署名の証明書の SHA-256：（4 節で控えた値）
```

アップロードします。

```powershell
$files = (Get-ChildItem $d -File | Where-Object Name -ne "notes.md").FullName
gh release create "v$v" $files --repo OWNER/REPO --verify-tag --prerelease --title "アイてる!? $v" --notes-file "$d\notes.md"
```

スマホで確かめるまでは `--prerelease` を付けておきます。

## 7. 次の版を出すとき

1. `gradle.properties` の `incallagent.versionCode` を上げ、`incallagent.versionName` を変える
2. 同じ鍵で 4 節から 6 節を行う。証明書の SHA-256 が前の版と同じことを確かめる
3. 使う人は上書きで入れられます。アプリを入れ直すと手伝い役は終わるので、起動し直してもらいます

## 8. ソースの公開

アプリは GPL-3.0 です。APK を配るときは、その APK のもとになったソースを、同じ場所から追加の費用なしで手に入るようにします。タグを付けたコミットからビルドして、そのタグの Release に APK を置くと、そうなります。`LICENSE`、`NOTICE`、`apps/rotep/licenses/` のライセンスの全文も一緒に置きます（6 節のコマンドで入ります）。

鳥の絵は GPL の対象外です（`apps/rotep/NOTICE`）。この文書は法律の助言ではありません。

## 9. Android の開発者確認のこと

Google は、確認を受けた開発者のアプリだけを認定端末に入れられるようにする仕組みを進めています。公式の説明（2026-11-09 に確認）では、2026-09-30 からブラジル、インドネシア、シンガポール、タイで始まり、2027 年以降に世界へ広げる予定です。未登録のアプリも、adb か「高度な手順」（最初に 24 時間待つ）で入れられます。日本でいつ始まるかは確かめていません。

出典：[Android 開発者確認の案内](https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html)、[未確認の開発者のアプリを許可する手順](https://support.google.com/android/answer/17588095)
