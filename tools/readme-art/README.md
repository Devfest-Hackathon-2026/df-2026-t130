# README の絵

リポジトリの [README](../../README.md) の題、3つの約束のカード、見出しの帯を SVG で作るスクリプトです。色は、アプリと同じもの（`apps/rotep` の `ui/theme/Color.kt`）を写しています。鳥は、アプリの止まった絵（`res/drawable-nodpi/aitel_bird_*_still.png`）を SVG の中に PNG として入れます。アプリの見た目を変えたら、こちらも合わせてください。

字は、アプリに入っている2つのフォントを線（アウトライン）にして描きます。GitHub は SVG を画像として出すので、フォントを読み込めないためです。

- Dela Gothic One：題の「アイてる!? - AITEL -」だけ
- 源暎きわみゴ：大きな見出しだけ

作り直すときは、リポジトリのいちばん上で（JDK 21。ビルドに使うものでよい）：

```bash
java tools/readme-art/ReadmeArt.java apps/rotep/src/main/res/font docs/images
```

`docs/images/` の SVG が上書きされます。見出しの言葉や色を変えるときは、`ReadmeArt.java` の `HEADINGS` と `PROMISES` を直します。

画面の写真（`docs/images/screens/`）は、このスクリプトでは作りません。Compose の Preview（`apps/rotep` の `ui/preview/`）を画像にして、幅 360 に縮め、角を丸めたものです。
