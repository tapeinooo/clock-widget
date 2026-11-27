# たぺいの時計（clock-widget）

時刻と日付を大きく出す自分用のホーム画面ウィジェット。アプリを開くと設定画面（文字の大きさ・フォント・更新）。

## 共通ルール

このアプリは自分用アプリの共通ルールに従う：
https://github.com/tapeinooo/app-rules/blob/main/RULES.md

作業を始める前に読むこと（読めないときは `add_repo` で `tapeinooo/app-rules` を足すか、
https://raw.githubusercontent.com/tapeinooo/app-rules/main/RULES.md を開く）。
主な点：GitHub Actions でビルド・main への push で自動リリース・版の番号は自動・同じ署名の鍵・アプリの中の更新・
コミットの作者は tapeinooo の noreply・秘密はリポジトリに入れない・利用者への説明は日本語でやさしく。

## このアプリだけの決まり

- 【例外】署名の鍵はリポジトリ直下の clock.p12（前から使っている固定の鍵。変えない）。
- 【例外】版の番号：手で付けていた頃（v1.10・versionCode 11）より大きくするため、gradle.properties の versionOffset=100 と versionMajor=2 を使う（versionName は 2.0.<実行番号>）。
- 【移行中】v1.10 までのアプリは「latest」という名前のリリースを見て更新するので、build.yml がリリースのたびに「latest」も作り直す（プレリリース扱いにして、新しい版の更新の確認には出ないようにしている）。みんな v2 になったら、この手順と「latest」は消してよい。
- 画面の見本は workflow_dispatch の screenshots（.github/shots.sh）で撮れる。
