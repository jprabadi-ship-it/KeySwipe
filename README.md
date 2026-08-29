# KeySwipe — Ctrl+矢印でアプリ操作（Pixel 10 Pro Fold 向け）

物理キーボード接続時に、Ctrl+矢印キーとトラックボールで以下の操作ができる Android アプリです。

| キー | 動作 |
|---|---|
| Ctrl+↑ | アプリ一覧（Overview）を開いて選択モードに入る |
| ↑↓←→（Ctrl不要） | 一覧のカードを青枠の選択カーソルで上下左右に辿る（カードの実座標に基づく空間ナビゲーション。左右の端では自動ページ送り）。一覧が開いていないときは通常のキーとしてアプリに届く |
| Enter | 選択中のカードをクリックしてそのアプリへ遷移 |
| Ctrl+←/→（一覧が閉じているとき） | 下端ナビバーの横スワイプを注入して隣のアプリへクイックスイッチ（最下端ぎりぎりを狙う2段階ジェスチャーで、タスクバー固定表示の画面でも動作することを実機確認済み） |
| Ctrl+↓ | アプリドロワー（全インストールアプリ一覧）を表示 |
| 修飾キー+トラックボール/マウス移動 | 画面をスクロール（押下中はカーソル非表示のスクロール専用モード） |

- Ctrl+↑/↓ はアプリ画面のスイッチで無効化できます（OFF の間は通常のキー操作としてアプリに届きます）。
- スクロールの**修飾キー**（Option/Ctrl/Cmd）、**速度**（0.2〜5.0倍）、**加速**（0〜2.0）は
  アプリ画面で調整できます。キーボードによっては物理の Option キーが Ctrl として届くため、
  実際に届くキーに合わせて選んでください（このプロジェクトの実機では Ctrl でした）。

## 仕組み

- ユーザー補助サービス（AccessibilityService）が `FLAG_REQUEST_FILTER_KEY_EVENTS` で
  物理キーボードのキーイベントを横取りします。
- **アプリ切り替え（Ctrl+←/→）**: `GLOBAL_ACTION_RECENTS` で一覧を開き、
  `canRetrieveWindowContent` でランチャーの全ウィンドウからタスクカード
  （clickable かつ画面の18%超の要素）を検出。青枠は TYPE_ACCESSIBILITY_OVERLAY に描画し、
  一覧表示中は80ms間隔でカードの実位置に追従させます。確定は検出したカードノードへの
  `ACTION_CLICK` なので、グリッド表示（内側画面）でもカルーセル（外側画面）でも動きます。
  端に達したときは1列ぶんのドラッグでページ送りし、カード名（contentDescription）の照合で
  選択位置を継ぎ足します。
  （下端スワイプのクイックスイッチ注入は、タスクバー固定画面ではOS側が受け付けないため不採用）
- Ctrl+↑ は `GLOBAL_ACTION_RECENTS`、Ctrl+↓ は `GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS`
  （Android 12+ の公式グローバルアクション）を実行します。
- **スクロール**: Android 14 (API 34) の `setMotionEventSources` + `onMotionEvent` を
  **修飾キー押下中だけ動的に有効化**してマウス/トラックボールの移動量を取得し、
  画面中央起点の連続ドラッグ（willContinue ストローク）に変換します。捕捉中はイベントが
  システムに渡らないためカーソルは動かず、離すと即復帰します。短い単発ドラッグは
  タップ判定され広告等を誤クリックするため、開始しきい値（40px）と連続ストロークで防いでいます。
- 座標・画面サイズは操作のたびに `currentWindowMetrics` から取得するため、
  **画面回転・折りたたみ状態（内側/外側画面）に自動追従**します。

## 必要条件

- Android 12 (API 31) 以上を推奨。Pixel 10 Pro Fold は対象内です。
- ジェスチャーナビゲーション / 3ボタンのどちらでも動作します（切り替えは一覧経由のため）。

## ビルド方法

### 方法A: Android Studio（推奨）

1. [Android Studio](https://developer.android.com/studio) をインストール
2. このフォルダ（`KeySwipe`）を "Open" で開く（Gradle wrapper が自動生成・同期されます）
3. Pixel を USB 接続し（開発者オプション → USB デバッグ ON）、Run ▶

### 方法B: コマンドラインのみ

```bash
brew install --cask temurin@17
brew install gradle android-commandlinetools
sdkmanager --licenses   # ライセンスに同意
sdkmanager "platforms;android-35" "build-tools;35.0.0"
cd KeySwipe
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

インストール後にコードを更新した場合は、再インストールに加えて
「設定 → ユーザー補助 → KeySwipe を一度 OFF → ON」でサービスを入れ直してください
（capability の変更はサービス再起動まで反映されません）。

## セットアップ（初回のみ）

1. アプリ「KeySwipe」を起動 → 「ユーザー補助設定を開く」
2. 設定 → ユーザー補助 → **KeySwipe を ON**（「フルコントロールを許可」に同意）
3. キーボードで **Ctrl+←** を2回押して動作確認（一覧が開き、青枠が動けばOK）

## カスタマイズ（KeySwipeService.kt の定数）

- `OVERVIEW_PAGE_SCROLL_RATIO` (0.38): 端に達したときのページ送り距離（1列ぶん）
- `SCROLL_START_THRESHOLD_PX` (40): スクロール開始のしきい値（タップ誤認防止）
- `NATURAL_SCROLL` (true): スクロール方向の反転
- `LAUNCHER_PACKAGE`: Pixel Launcher 以外のランチャーを使う場合は変更

## 制限事項

- ランチャーは Pixel Launcher（`com.google.android.apps.nexuslauncher`）前提です。
  他のランチャーではカード検出の条件調整が必要になる場合があります。
- ロック画面・一部のシステム画面ではジェスチャー注入がキャンセルされることがあります。
- Ctrl+矢印を文字選択・単語移動等に使うアプリでも、本サービス有効中は本アプリの操作が優先されます
  （キーイベントを消費するため、アプリ側には届きません）。
- スクロールモード中（修飾キー押下中）はマウスクリックも捕捉されるため、
  修飾キー+クリックの操作はできません。
