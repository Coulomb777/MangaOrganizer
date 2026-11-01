# Manga Organizer 試作版

## この版の機能
- Androidのフォルダ選択画面でPDF保存元と整理先を指定
- 選択フォルダ直下のPDFを一覧化
- PDFの1ページ目をAndroid PdfRendererで画像化
- ML Kit日本語OCRで文字を読み取る
- 作画担当者名を利用者が確認・入力
- 作画担当者名のフォルダへPDFをコピー
- 作画担当者が空欄なら「未分類」へコピー
- 同名ファイルは上書きせず停止
- 元PDFは削除しない

## 現時点の制限
これは初期試作版です。OCRで読み取った文字から作画担当者を自動確定したり、NDL等の書誌APIを使って作画担当者を自動判定したりはしません。表紙に作者情報がない場合や、原作者と作画担当者の区別が必要な場合は手動確認が必要です。
PDFファイル自体を外部サーバーへ送信しません。OCRは端末上のML Kitで実行します。
大量PDFの一括処理、途中再開履歴、重複作品の照合は今後の拡張項目です。

## スマホだけでGitHub ActionsからAPKを作る手順
1. GitHubアカウントにサインインし、新しいRepositoryを作成します（例：MangaOrganizer）。
2. ZIPを展開し、中身をRepositoryのルートへアップロードします。`.github`フォルダも含めてください。
3. Repositoryの「Actions」を開きます。
4. 「Build Android APK」を選び、「Run workflow」を押します。
5. 完了した実行結果を開き、ページ下部の「Artifacts」から `MangaOrganizer-debug-apk` をダウンロードします。
6. ダウンロードしたZIPを展開し、`app-debug.apk` をGalaxy S24 Ultraで開いてインストールします。

## ビルド設定
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- Gradle 8.9
- JDK 17
- compileSdk 35 / minSdk 26

## トラブル時
GitHub Actionsの実行結果に表示されるエラー全文を共有してください。ログを見て修正できます。
