package jp.miyashita.keyswipe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 物理キーボードのショートカットを横取りするサービス。
 *
 * - Ctrl+←/→ : ジェスチャーナビゲーションのクイックスイッチ
 *   （画面下端ハンドルの横フリック）を注入して隣のアプリへ切り替え。
 * - Ctrl+↑   : 最近使ったアプリ一覧（Recents）を開く。
 * - Ctrl+↓   : アプリドロワー（全インストールアプリ一覧）を開く。
 * - 修飾キー+トラックボール/マウス移動 : 画面スクロール。
 *   修飾キー押下中は透明の focusable オーバーレイを出して requestPointerCapture で
 *   ポインタを掴む。キャプチャ中はカーソルが消えて動かず、生の移動量だけが
 *   onCapturedPointerEvent に届く（motionEventSources 消費方式はカーソルの
 *   スプライト移動を止められないことが実測で判明したため不採用）。
 *
 * スワイプ座標はキー押下のたびに currentWindowMetrics から取り直すため、
 * 画面回転・折りたたみ（内側/外側ディスプレイ）に自動で追従する。
 */
class KeySwipeService : AccessibilityService() {

    companion object {
        private const val TAG = "KeySwipeService"

        // クイックスイッチの体感に合わせたパラメータ
        private const val SWIPE_WIDTH_RATIO = 0.5f    // 画面幅に対するスワイプ距離
        private const val QS_SEG1_RATIO = 0.65f       // 前半(速い区間)の距離割合
        private const val QS_SEG1_MS = 110L           // 前半の所要時間
        private const val QS_SEG2_MS = 170L           // 後半(減速区間)の所要時間

        // 「Ctrl+→ = 右側(新しい方)のアプリ」= 画面を左へフリック、が既定。
        // 逆に感じる場合は true にする。
        private const val SWAP_DIRECTIONS = false

        // --- 修飾キー+トラックボールスクロール ---
        // ドラッグ開始のしきい値。タッチスロップ(約24px)未満の単発ストロークは
        // タップと判定され、広告などをクリックしてしまうため必ず上回らせる。
        private const val SCROLL_START_THRESHOLD_PX = 40f
        private const val SCROLL_STROKE_DURATION_MS = 50L
        // 指を離す前の静止ホールド。短いと直前の移動速度が残って
        // フリング（離した後に少し流れる）と判定されるため、
        // アプリ側の速度計算窓(約100ms)ぶん静止してから離す
        private const val SCROLL_END_DURATION_MS = 100L
        private const val EDGE_MARGIN_RATIO = 0.12f     // 端ジェスチャー誤爆回避の安全マージン
        // オーバーレイ除去がシステムに反映されるのを待つ時間。
        // 旧スワイプ注入方式では250ms必要だったが、現在のグローバルアクション/
        // 一覧選択方式では短くて足りる（長いと Ctrl+↑/↓ の反応が遅く感じる）
        private const val OVERLAY_SETTLE_DELAY_MS = 50L
        // Recents 2連打の間隔（Overview が開いてから確定させるまで）
        private const val RECENTS_DOUBLE_DELAY_MS = 350L
        // ホームに戻ってからドロワー用の上スワイプを打つまでの待ち
        private const val DRAWER_SWIPE_DELAY_MS = 450L
        // ドロワーが開いてから青枠選択モードを開始するまでの待ち
        private const val DRAWER_SELECT_DELAY_MS = 750L
        // これ以上の高さのナビバーは固定タスクバーとみなす
        // （細いジェスチャーバーは~60px、Fold内側の固定タスクバーは136px: 実測）
        private const val TASKBAR_MIN_HEIGHT_PX = 100

        // --- アプリ一覧で止まって選ぶ方式 ---
        private const val LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher"
        private const val OVERVIEW_PAGE_SCROLL_MS = 250L   // 端に達したときのページ送りドラッグ
        // ページ送りの距離。グリッドの1列ピッチ(実測806px/2152px≒0.375)に合わせ、
        // 追跡中のカードが画面外へ出ないよう1列ぶんだけ送る
        private const val OVERVIEW_PAGE_SCROLL_RATIO = 0.38f
        private const val OVERVIEW_RESCAN_DELAY_MS = 400L  // ページ送り後の再検出待ち
        private const val OVERVIEW_INITIAL_HIGHLIGHT_DELAY_MS = 450L // 一覧が開くのを待って初期枠表示
        // 一覧を開いた直後は、元アプリ等のウィンドウイベントが飛び交うため
        // この間は「一覧が閉じた」判定をしない
        private const val OVERVIEW_OPEN_GRACE_MS = 800L
        private const val OVERVIEW_SCROLL_MS = 220L    // カード1枚ぶんの送りドラッグ時間
        private const val OVERVIEW_SCROLL_RATIO = 0.42f // 送りドラッグの距離(画面幅比)
    }

    private var gestureInFlight = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 修飾キー+トラックボールスクロールの状態
    private var modifierDown = false
    private var scrollGestureInFlight = false
    private var accumX = 0f
    private var accumY = 0f
    private var captureView: CaptureView? = null

    // 進行中の連続ストローク（一本の長いドラッグ）と現在の指の位置
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var strokeX = 0f
    private var strokeY = 0f

    /**
     * ボール移動の検知を切り替える（修飾キー押下中のみON）。
     * 修飾キーを押しただけではオーバーレイを出さず、実際にボールが動いた瞬間に
     * onMotionEvent 経由でスクロールモードへ入る。押下だけでフォーカスを奪う
     * オーバーレイを出すと、レイヤーキー(=修飾キー)を押しながらの通常キー入力が
     * オーバーレイに吸われて届かなくなるため（実測）。
     */
    private fun setMotionDetection(enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        serviceInfo = serviceInfo?.apply {
            motionEventSources = if (enable) {
                android.view.InputDevice.SOURCE_MOUSE or android.view.InputDevice.SOURCE_TRACKBALL
            } else {
                0
            }
        }
    }

    override fun onMotionEvent(event: MotionEvent) {
        // 修飾キー押下中にボールが動いた: ここで初めてスクロールモードへ入る
        if (modifierDown && captureView == null && !overviewMode &&
            Prefs.isMasterEnabled(this)
        ) {
            enterScrollMode()
        }
    }

    /** ポインタを掴むための透明オーバーレイ。ウィンドウフォーカス取得後にキャプチャする。 */
    private inner class CaptureView(context: Context) : View(context) {
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            Log.d(TAG, "overlay windowFocus=$hasWindowFocus")
            if (hasWindowFocus) {
                requestFocus()
                requestPointerCapture()
            }
        }

        override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
            handleCapturedPointer(event)
            return true
        }
    }

    /** スクロールモード開始: オーバーレイを追加してポインタキャプチャを要求する。 */
    private fun enterScrollMode() {
        if (captureView != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val view = CaptureView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 触れないウィンドウにして、注入するスクロールジェスチャーは下のアプリへ通す
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, lp)
            captureView = view
            Log.d(TAG, "enterScrollMode: overlay added")
        } catch (e: Exception) {
            Log.w(TAG, "enterScrollMode failed", e)
        }
    }

    /** スクロールモード終了: キャプチャを解放してオーバーレイを外す。 */
    private fun exitScrollMode() {
        val view = captureView ?: return
        captureView = null
        try {
            view.releasePointerCapture()
            getSystemService(WindowManager::class.java)?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "exitScrollMode failed", e)
        }
        resetScrollState()
        // 進行中のドラッグがあれば指を離す（注入中なら完了コールバック側で終了する）
        maybeDispatchScroll()
        Log.d(TAG, "exitScrollMode: overlay removed")
    }

    override fun onDestroy() {
        exitScrollMode()
        hideHighlight()
        super.onDestroy()
    }

    // 現在のIME(キーボード)のパッケージ名。IMEのウィンドウイベントは
    // 「一覧が閉じた」判定から除外する（Gboardのイベントで選択モードが
    // 誤解除される実測不具合があった）
    private val imePackage: String? by lazy {
        android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
        )?.substringBefore('/')
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // ランチャー(アプリ一覧)以外の「実アプリ」が前面に来たら選択モードを終える
        // （手動でカードをタップした・ホームに戻った・コミット完了した等）。
        // IME・システムUI・自分自身のウィンドウイベントはアプリ切り替えではない。
        if (overviewMode && pkg != LAUNCHER_PACKAGE && pkg != packageName &&
            pkg != imePackage && pkg != "com.android.systemui" &&
            android.os.SystemClock.uptimeMillis() - overviewOpenedAt > OVERVIEW_OPEN_GRACE_MS
        ) {
            Log.d(TAG, "overviewMode cleared by window change: $pkg")
            overviewMode = false
            overviewCards = emptyList()
            stopHighlightTracking()
            hideHighlight()
        }
    }

    override fun onInterrupt() = Unit

    /** 設定された「スクロール修飾キー」かどうか。 */
    private fun isScrollModifierKey(code: Int): Boolean = when (Prefs.getScrollModifier(this)) {
        Prefs.MOD_CTRL -> code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT
        Prefs.MOD_META -> code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT
        else -> code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 注意: ここは全キー入力が通る経路。素通りさせるキーの処理は最短に保つ
        // （ログ出力も連打時のオーバーヘッドになるため、通常キーでは行わない）

        // マスタースイッチOFF: 一切横取りしない
        if (!Prefs.isMasterEnabled(this)) return false

        // 診断用: Ctrl/修飾キー押下中と一覧選択中のキーだけログする（素のキーは対象外）
        if (overviewMode || event.isCtrlPressed || modifierDown) {
            Log.d(TAG, "key: code=${event.keyCode} action=${event.action} " +
                    "meta=${event.metaState} overview=$overviewMode capture=${captureView != null}")
        }

        // アプリ一覧の選択中: ↑↓←→（Ctrl不要）で枠を上下左右に移動、Enter で確定。
        // いずれも消費してアプリには流さない。
        if (overviewMode) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        Log.d(TAG, "arrow input latency=" +
                                "${android.os.SystemClock.uptimeMillis() - event.eventTime}ms")
                        val dx = when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> -1
                            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                            else -> 0
                        }
                        val dy = when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> -1
                            KeyEvent.KEYCODE_DPAD_DOWN -> 1
                            else -> 0
                        }
                        suspendScrollModeAnd { moveOverviewSelectionSpatial(dx, dy) }
                    }
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        commitOverviewSelection()
                    }
                    return true
                }
            }
        }

        // スクロール修飾キーはモードのトグルとして監視する（消費はしない）
        if (isScrollModifierKey(event.keyCode)) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (down != modifierDown) {
                modifierDown = down
                if (down) {
                    // ボール移動の検知だけを開始（オーバーレイはまだ出さない）
                    setMotionDetection(true)
                } else {
                    setMotionDetection(false)
                    exitScrollMode()
                }
            }
            // Ctrl を修飾キーにした場合も Ctrl+矢印の判定は下へ続行させる
            if (!event.isCtrlPressed) return false
        }

        if (!event.isCtrlPressed) return false

        val action: () -> Unit = when (event.keyCode) {
            // 一覧が開いていないときの Ctrl+←/→: 下端ナビバーの横スワイプを注入して
            // クイックスイッチ（一覧選択中の素の←/→は上のブロックで処理済み）
            KeyEvent.KEYCODE_DPAD_RIGHT -> ({
                suspendScrollModeAnd { performQuickSwitchBySwipe(toRightApp = true) }
            })
            KeyEvent.KEYCODE_DPAD_LEFT -> ({
                suspendScrollModeAnd { performQuickSwitchBySwipe(toRightApp = false) }
            })
            KeyEvent.KEYCODE_DPAD_UP -> {
                // スイッチOFF時は横取りせず、キーをそのままアプリへ流す
                if (!Prefs.isUpDownEnabled(this)) return false
                ({ suspendScrollModeAnd { openOverviewSelection() } })
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!Prefs.isUpDownEnabled(this)) return false
                ({ suspendScrollModeAnd { openAllApps() } })
            }
            else -> return false
        }

        // DOWN の初回のみ発火。UP や自動リピートも消費してアプリ側へ流さない。
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            action()
        }
        return true
    }

    /**
     * スクロールモード中なら一旦解除してから実行する。
     * フォーカスを取るオーバーレイが出たままだと、クイックスイッチや
     * Recents などのシステム遷移が阻害されるため（実測）。
     */
    private fun suspendScrollModeAnd(run: (wasScrollMode: Boolean) -> Unit) {
        if (captureView != null) {
            exitScrollMode()
            // オーバーレイ除去とフォーカス返却が反映されてから実行する
            mainHandler.postDelayed({ run(true) }, OVERLAY_SETTLE_DELAY_MS)
        } else {
            run(false)
        }
    }

    /**
     * アプリドロワー（全インストールアプリ一覧）を開く。
     *
     * GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS はタスクバーのある大画面では
     * 「Taskbar Overlay」の全アプリパネルを開き、これは物理キーの矢印ナビを
     * 一切受け付けない（実測: mCurrentFocus は移るがキーに無反応）。
     * そこでホームへ戻ってから上スワイプを注入し、キーボード操作が効く
     * ランチャー本来のドロワーを開く。
     */
    private fun openAllApps() {
        // ランチャーのキーボードフォーカスは不安定（動く時と動かない時がある実測）
        // なので頼らず、Overviewと同じ青枠選択モードでアイコンを操作する。
        // モードは即座に有効化し、開くまでの間に押された矢印もリトライで反映する
        selectionIsDrawer = true
        overviewMode = true
        overviewScrolled = false
        overviewCards = emptyList()
        overviewIndex = 0
        overviewOpenedAt = android.os.SystemClock.uptimeMillis()
        performGlobalAction(GLOBAL_ACTION_HOME)
        mainHandler.postDelayed({ injectDrawerOpenSwipe() }, DRAWER_SWIPE_DELAY_MS)
        mainHandler.postDelayed({
            if (overviewMode && selectionIsDrawer) moveOverviewSelection(0, attempts = 6)
        }, DRAWER_SELECT_DELAY_MS)
        // 開くアニメーション中の初回スキャンは行が欠けることがあるため、
        // 落ち着いた頃に完全な地図へ差し替える
        mainHandler.postDelayed({
            if (overviewMode && selectionIsDrawer) rescanKeepingSelection()
        }, DRAWER_SELECT_DELAY_MS + 700L)
    }

    /** ホーム画面で上スワイプを注入してドロワーを開く。 */
    private fun injectDrawerOpenSwipe() {
        val g = screenGeometry() ?: return
        val x = g[0] / 2f
        val path = Path().apply {
            moveTo(x, g[1] * 0.85f)
            lineTo(x, g[1] * 0.35f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250L, false)
        Log.d(TAG, "injectDrawerOpenSwipe")
        dispatchGesture(buildGesture(stroke), null, null)
    }

    // ------------------------------------------------------------------
    // 修飾キー + トラックボール/マウス移動でスクロール（ポインタキャプチャ方式）
    // ------------------------------------------------------------------

    /** キャプチャ中のポインタイベント。x/y は相対移動量が入る。 */
    private fun handleCapturedPointer(event: MotionEvent) {
        if (!modifierDown) return

        // SOURCE_MOUSE_RELATIVE では getX/getY が相対値。相対軸があればそちらを優先。
        var dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        var dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        if (dx == 0f && dy == 0f) {
            dx = event.x
            dy = event.y
        }
        if (dx == 0f && dy == 0f) return
        Log.d(TAG, "capturedPointer: dx=$dx dy=$dy")

        // 速度・加速の適用。加速はイベントごとの移動量（≒ボールを回す速さ）に比例。
        val speed = Prefs.getScrollSpeed(this)
        val accel = Prefs.getScrollAccel(this)
        val magnitude = hypot(dx, dy)
        val gain = min(speed * (1f + accel * magnitude / 10f), speed * 6f)
        accumX += dx * gain
        accumY += dy * gain

        maybeDispatchScroll()
    }

    private fun resetScrollState() {
        accumX = 0f
        accumY = 0f
    }

    /** 画面サイズと安全マージン [w, h, marginX, marginY]。 */
    private fun screenGeometry(): FloatArray? {
        val wm = getSystemService(WindowManager::class.java) ?: return null
        val bounds = wm.currentWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        return floatArrayOf(w, h, w * EDGE_MARGIN_RATIO, h * EDGE_MARGIN_RATIO)
    }

    /** 蓄積量を消費して移動ベクトル(スクロール方向設定適用済み)を返す。 */
    private fun consumeMove(): Pair<Float, Float> {
        // 既定(-1): ボールを下に転がすとページが下へ進む。反転設定でその逆
        val sign = if (Prefs.isScrollInverted(this)) 1f else -1f
        val moveX = accumX * sign
        val moveY = accumY * sign
        accumX = 0f
        accumY = 0f
        return moveX to moveY
    }

    /**
     * スクロール注入の中枢。ボールを回している間は willContinue の連続ストロークで
     * 「一本の長いドラッグ」を維持する。短い down→up を繰り返すと、移動量が
     * タッチスロップ未満のときタップと誤判定され広告等をクリックしてしまうため。
     */
    private fun maybeDispatchScroll() {
        if (scrollGestureInFlight) return
        val pending = hypot(accumX, accumY)
        if (activeStroke == null) {
            // 新しいドラッグはタップ誤認しない移動量が溜まってから開始する
            if (!modifierDown) { resetScrollState(); return }
            if (pending < SCROLL_START_THRESHOLD_PX) return
            startStroke()
        } else {
            if (!modifierDown || pending < 1f) endStroke() else continueStrokeSegment()
        }
    }

    /** 画面中央から最初のセグメントを打つ（willContinue=true で指は離さない）。 */
    private fun startStroke() {
        val g = screenGeometry() ?: return
        val (w, h, marginX, marginY) = g
        val (moveX, moveY) = consumeMove()
        val startX = w / 2f
        val startY = h / 2f
        val endX = (startX + moveX).coerceIn(marginX, w - marginX)
        val endY = (startY + moveY).coerceIn(marginY, h - marginY)
        if (abs(endX - startX) < 1f && abs(endY - startY) < 1f) return

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(
            path, 0, SCROLL_STROKE_DURATION_MS, true
        )
        strokeX = endX
        strokeY = endY
        activeStroke = stroke
        dispatchStroke(stroke)
    }

    /** 進行中のドラッグに次のセグメントを継ぎ足す。 */
    private fun continueStrokeSegment() {
        val stroke = activeStroke ?: return
        val g = screenGeometry() ?: return
        val (w, h, marginX, marginY) = g
        val (moveX, moveY) = consumeMove()
        val endX = (strokeX + moveX).coerceIn(marginX, w - marginX)
        val endY = (strokeY + moveY).coerceIn(marginY, h - marginY)
        if (abs(endX - strokeX) < 1f && abs(endY - strokeY) < 1f) {
            // 安全圏の端に達して進めない: 一度指を離し、次は中央から打ち直す
            endStroke()
            return
        }

        val path = Path().apply {
            moveTo(strokeX, strokeY)
            lineTo(endX, endY)
        }
        val next = stroke.continueStroke(path, 0, SCROLL_STROKE_DURATION_MS, true)
        strokeX = endX
        strokeY = endY
        activeStroke = next
        dispatchStroke(next)
    }

    /** ドラッグを終了する（その場で指を離す。フリングはさせない）。 */
    private fun endStroke() {
        val stroke = activeStroke ?: return
        activeStroke = null
        val path = Path().apply { moveTo(strokeX, strokeY) }
        dispatchStroke(stroke.continueStroke(path, 0, SCROLL_END_DURATION_MS, false))
    }

    private fun dispatchStroke(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder()
            .setDisplayId(Display.DEFAULT_DISPLAY)
            .addStroke(stroke)
            .build()
        scrollGestureInFlight = true
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                scrollGestureInFlight = false
                // 注入中に溜まった分の継続、または終了処理
                maybeDispatchScroll()
            }

            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "scroll stroke cancelled")
                scrollGestureInFlight = false
                activeStroke = null
            }
        }, null)
        if (!dispatched) {
            scrollGestureInFlight = false
            activeStroke = null
        }
    }

    /** 切り替え後のスクロール復帰は不要になった（ボールが動けば自動で入るため）。 */
    private fun scheduleScrollModeResume(@Suppress("UNUSED_PARAMETER") resumeScroll: Boolean) = Unit

    /**
     * アプリ切り替えの入口。画面下部のナビゲーションバーの高さで方式を選ぶ。
     *
     * - 細いジェスチャーバー: 下端の横スワイプ（クイックスイッチ）を注入。
     *   ←→で方向つきにアプリを辿れる。
     * - 高いバー = タスクバー固定表示（Fold内側等）: 横スワイプのクイック
     *   スイッチがOSで成立しない（実測）ため、Recents 2連打で直前アプリと
     *   トグルする（Overviewダブルタップ相当の公式挙動）。
     */
    // オーバービュー内のカード送りが実行中のとき、次の押下を積むキュー
    private val qsQueue = ArrayDeque<Boolean>()

    // アプリ一覧（Overview/Recents）で止まって選ぶ方式の状態
    private var overviewMode = false
    private var overviewScrolled = false

    // 一覧から検出したタスクカード（新しい順）と選択位置
    private var overviewCards: List<OverviewCard> = emptyList()
    private var overviewIndex = 0
    private var highlightView: HighlightView? = null

    // 選択モードの対象: false=Overview(タスクカード) / true=ドロワー(アプリアイコン)
    private var selectionIsDrawer = false

    private data class OverviewCard(val node: AccessibilityNodeInfo, val bounds: Rect)

    /**
     * Ctrl+←/→: 1回目でアプリ一覧を開いて止まり、2回目以降は一覧のカードを
     * 選択カーソル（枠ハイライト）で辿る。Enter で選択中のカードをクリックして
     * 遷移する。カードは画面内容から検出するため、グリッド表示（Fold内側）でも
     * カルーセル表示（外側）でも動く。
     */
    // 一覧を開いた時刻（開いた直後のウィンドウイベント無視に使う）
    private var overviewOpenedAt = 0L

    /** Ctrl+↑: アプリ一覧を開いて選択モードに入る。以後 Ctrl+←/→ で選択、Enter で確定。 */
    private fun openOverviewSelection() {
        if (overviewMode) {
            // 状態がずれて「開いているつもり」の可能性がある。カードが実在するなら
            // 本当に開いているので何もしない。無ければ捨てて開き直す（自動リカバリ）。
            refreshOverviewCards()
            if (overviewCards.isNotEmpty()) return
            Log.d(TAG, "openOverviewSelection: stale state, reopening")
        }
        Log.d(TAG, "openOverviewSelection")
        selectionIsDrawer = false
        overviewOpenedAt = android.os.SystemClock.uptimeMillis()
        overviewMode = true
        overviewScrolled = false
        overviewCards = emptyList()
        overviewIndex = 0
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        // 一覧が開いたら、矢印を待たずに現在のカード(先頭)へ青枠を出す
        mainHandler.postDelayed({
            if (overviewMode) moveOverviewSelection(0)
        }, OVERVIEW_INITIAL_HIGHLIGHT_DELAY_MS)
    }

    /** 選択カーソルを移動する。カード未検出なら検出し、まだ描画途中なら待って再試行。 */
    private fun moveOverviewSelection(delta: Int, attempts: Int = 4) {
        if (overviewCards.isEmpty()) refreshOverviewCards()
        if (overviewCards.isEmpty()) {
            if (attempts > 0) {
                mainHandler.postDelayed({
                    if (overviewMode) moveOverviewSelection(delta, attempts - 1)
                }, 250L)
            } else {
                // 一覧が既に閉じている等でカードが見つからない: 状態を捨てて
                // 次の Ctrl+←/→ で一覧を開き直せるようにする
                Log.w(TAG, "moveOverviewSelection: no cards found, resetting")
                overviewMode = false
                stopHighlightTracking()
                hideHighlight()
            }
            return
        }
        val target = overviewIndex + delta
        if (target < 0 || target > overviewCards.lastIndex) {
            // 見えているカードの端に達した: グリッドをページ送りして続きを呼び込む
            scrollOverviewPageAndContinue(delta)
            return
        }
        overviewIndex = target
        overviewScrolled = true
        showHighlight(overviewCards[overviewIndex].bounds)
        // 一覧表示中は常時追跡し、アニメーション後の実位置に枠を吸い付かせる
        startHighlightTracking()
        Log.d(TAG, "overview selection -> $overviewIndex / ${overviewCards.size}")
    }

    /** カードの照合キー（アプリ名）。説明はカード本体ではなく子のサムネイルに付く。 */
    /**
     * カードの実座標に基づく空間ナビゲーション。dx/dy の方向にある最も近い
     * カードへ枠を移動する（直交方向のズレは重めに罰して直感的な隣を選ぶ）。
     * 横方向で候補が無い場合は端なのでページ送りに委譲する。
     */
    // カード未検出時のリトライは連鎖を量産しないよう「最後の入力1つ」だけ保持する
    private var pendingSpatialDx = 0
    private var pendingSpatialDy = 0
    private var spatialRetryScheduled = false

    // 最後にカード検出した時刻。開くアニメーション中の不完全なスキャン結果や
    // 古くなった地図を使い続けないための再スキャン判定に使う
    private var lastCardScanAt = 0L

    /** 現在の選択をアプリ名で保ったまま検出し直す（不完全な地図の差し替え）。 */
    private fun rescanKeepingSelection() {
        if (!overviewMode) return
        val key = overviewCards.getOrNull(overviewIndex)?.let { cardKey(it) } ?: ""
        val before = overviewCards.size
        refreshOverviewCards()
        if (overviewCards.isEmpty()) return
        val idx = if (key.isNotEmpty()) overviewCards.indexOfFirst { cardKey(it) == key } else -1
        overviewIndex = if (idx >= 0) idx else 0
        showHighlight(overviewCards[overviewIndex].bounds)
        startHighlightTracking()
        Log.d(TAG, "rescanKeepingSelection: $before -> ${overviewCards.size} cards, idx=$overviewIndex")
    }

    private fun moveOverviewSelectionSpatial(
        dx: Int,
        dy: Int,
        attempts: Int = 4,
        allowPage: Boolean = true,
    ) {
        // ドロワーで地図が古くなっていたら（手でスクロールされた等）操作前に更新
        if (selectionIsDrawer && overviewCards.isNotEmpty() &&
            android.os.SystemClock.uptimeMillis() - lastCardScanAt > 3000L
        ) {
            rescanKeepingSelection()
        }
        if (overviewCards.isEmpty()) refreshOverviewCards()
        if (overviewCards.isEmpty()) {
            if (attempts > 0) {
                pendingSpatialDx = dx
                pendingSpatialDy = dy
                if (!spatialRetryScheduled) {
                    spatialRetryScheduled = true
                    mainHandler.postDelayed({
                        spatialRetryScheduled = false
                        if (overviewMode) {
                            moveOverviewSelectionSpatial(
                                pendingSpatialDx, pendingSpatialDy, attempts - 1
                            )
                        }
                    }, 250L)
                }
            } else {
                Log.w(TAG, "moveOverviewSelectionSpatial: no cards found, resetting")
                overviewMode = false
                stopHighlightTracking()
                hideHighlight()
            }
            return
        }
        val current = overviewCards.getOrNull(overviewIndex) ?: run {
            overviewIndex = 0
            showHighlight(overviewCards[0].bounds)
            startHighlightTracking()
            return
        }
        val cb = current.bounds
        val best = overviewCards.withIndex()
            .filter { (i, c) ->
                if (i == overviewIndex) return@filter false
                when {
                    dx < 0 -> c.bounds.centerX() < cb.centerX() - 10
                    dx > 0 -> c.bounds.centerX() > cb.centerX() + 10
                    dy < 0 -> c.bounds.centerY() < cb.centerY() - 10
                    else -> c.bounds.centerY() > cb.centerY() + 10
                }
            }
            .minByOrNull { (_, c) ->
                val ddx = abs(c.bounds.centerX() - cb.centerX()).toFloat()
                val ddy = abs(c.bounds.centerY() - cb.centerY()).toFloat()
                if (dx != 0) ddx + ddy * 3f else ddy + ddx * 3f
            }
        if (best == null) {
            if (!allowPage) return
            if (selectionIsDrawer) {
                // ドロワー: 上下の端に達したら縦スクロールで続きの行を呼び込む
                if (dy != 0) scrollDrawerPageAndContinue(dy)
            } else {
                // Overview: 横の端に達したらページ送り（←=古い方=+1 / →=新しい方=-1）
                if (dx != 0) scrollOverviewPageAndContinue(if (dx < 0) 1 else -1)
            }
            return
        }
        overviewIndex = best.index
        overviewScrolled = true
        showHighlight(overviewCards[overviewIndex].bounds)
        startHighlightTracking()
        Log.d(TAG, "overview spatial(dx=$dx,dy=$dy) -> $overviewIndex / ${overviewCards.size}")
    }

    /**
     * ドロワーを縦にページ送りして続きの行を呼び込み、元の選択位置の
     * 直下(直上)のアイコンを選び直す。dyDir=1で下方向。
     */
    private fun scrollDrawerPageAndContinue(dyDir: Int) {
        if (gestureInFlight) return
        val current = overviewCards.getOrNull(overviewIndex) ?: return
        val key = cardKey(current)
        val prevKeys = overviewCards.map { cardKey(it) }.toSet()
        val prevCx = current.bounds.centerX()
        val g = screenGeometry() ?: return

        val x = g[0] / 2f
        val y0 = g[1] * 0.55f
        val y1 = (y0 - dyDir * g[1] * 0.35f).coerceIn(g[1] * 0.15f, g[1] * 0.85f)
        val path = Path().apply {
            moveTo(x, y0)
            lineTo(x, y1)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, OVERVIEW_PAGE_SCROLL_MS, false)
        gestureInFlight = true
        Log.d(TAG, "scrollDrawerPage: dyDir=$dyDir key=$key")
        val ok = dispatchGesture(buildGesture(stroke), object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) {
                gestureInFlight = false
                mainHandler.postDelayed(
                    { reselectAfterDrawerScroll(dyDir, key, prevKeys, prevCx) },
                    OVERVIEW_RESCAN_DELAY_MS
                )
            }

            override fun onCancelled(gd: GestureDescription?) {
                gestureInFlight = false
                stopHighlightTracking()
            }
        }, null)
        if (!ok) {
            gestureInFlight = false
            return
        }
        startHighlightTracking()
    }

    private fun reselectAfterDrawerScroll(dyDir: Int, key: String, prevKeys: Set<String>, prevCx: Int) {
        if (!overviewMode) return
        refreshOverviewCards()
        if (overviewCards.isEmpty()) return
        val prevIdx = if (key.isNotEmpty()) {
            overviewCards.indexOfFirst { cardKey(it) == key }
        } else -1
        if (prevIdx >= 0) {
            // 元のアイコンがまだ見えている: そこから改めて1歩進める（ページ再送はしない）
            overviewIndex = prevIdx
            moveOverviewSelectionSpatial(0, dyDir, allowPage = false)
            scheduleFallbackHighlight()
            return
        }
        // 元のアイコンが画面外に出た: 新しく現れた行の中で元のX位置に最も近いものを選ぶ
        val newCards = overviewCards.withIndex().filter { cardKey(it.value) !in prevKeys }
        val candidate = if (dyDir > 0) {
            newCards.minByOrNull {
                it.value.bounds.centerY() * 10000L + abs(it.value.bounds.centerX() - prevCx)
            }
        } else {
            newCards.maxByOrNull {
                it.value.bounds.centerY() * 10000L - abs(it.value.bounds.centerX() - prevCx)
            }
        }
        overviewIndex = candidate?.index ?: if (dyDir > 0) overviewCards.lastIndex else 0
        overviewScrolled = true
        showHighlight(overviewCards[overviewIndex].bounds)
        startHighlightTracking()
        Log.d(TAG, "drawer reselect -> $overviewIndex / ${overviewCards.size} (prevIdx=$prevIdx)")
    }

    /** spatial移動が空振りしても枠が現在位置に出るよう保険をかける。 */
    private fun scheduleFallbackHighlight() {
        overviewCards.getOrNull(overviewIndex)?.let {
            showHighlight(it.bounds)
            startHighlightTracking()
        }
    }

    private fun cardKey(card: OverviewCard): String = findDescription(card.node, 0) ?: ""

    private fun findDescription(node: AccessibilityNodeInfo?, depth: Int): String? {
        node ?: return null
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (depth >= 3) return null
        for (i in 0 until node.childCount) {
            findDescription(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    // 移動・ページ送り直後の短時間だけ、選択中カードに枠を追従させるポーリング。
    // node.refresh()は同期IPCでメインスレッドを塞ぐため、常時実行はキー処理の
    // 遅延源になる。位置が落ち着く時間(約0.6秒)だけ回して自動停止する。
    private var highlightTracking = false
    private var highlightTrackTicksLeft = 0
    private val highlightTrackRunnable = object : Runnable {
        override fun run() {
            if (!highlightTracking || !overviewMode || highlightTrackTicksLeft <= 0) {
                highlightTracking = false
                return
            }
            highlightTrackTicksLeft--
            val node = overviewCards.getOrNull(overviewIndex)?.node
            if (node != null && node.refresh()) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (!b.isEmpty) showHighlight(b)
            }
            mainHandler.postDelayed(this, 80L)
        }
    }

    private fun startHighlightTracking() {
        highlightTrackTicksLeft = 8   // 約0.64秒で自動停止
        if (highlightTracking) return
        highlightTracking = true
        mainHandler.post(highlightTrackRunnable)
    }

    private fun stopHighlightTracking() {
        highlightTracking = false
        mainHandler.removeCallbacks(highlightTrackRunnable)
    }

    /**
     * 一覧をページ送りして隠れているカードを表示し、元の選択位置から delta ぶん
     * 進めた位置を選び直す。古い方(delta>0)は列を右へドラッグして左側を呼び込む。
     */
    private fun scrollOverviewPageAndContinue(delta: Int) {
        if (gestureInFlight) return
        val current = overviewCards.getOrNull(overviewIndex) ?: return
        val key = cardKey(current)
        val prevKeys = overviewCards.map { cardKey(it) }.toSet()
        val g = screenGeometry() ?: return
        val w = g[0]

        val dir = if (delta > 0) 1f else -1f
        val y = current.bounds.exactCenterY()
        val startX = w / 2f
        val endX = (startX + dir * w * OVERVIEW_PAGE_SCROLL_RATIO)
            .coerceIn(w * 0.05f, w * 0.95f)
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, OVERVIEW_PAGE_SCROLL_MS, false)
        gestureInFlight = true
        Log.d(TAG, "scrollOverviewPage: delta=$delta key=$key")
        val ok = dispatchGesture(buildGesture(stroke), object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) {
                gestureInFlight = false
                mainHandler.postDelayed(
                    { reselectAfterPageScroll(delta, key, prevKeys) },
                    OVERVIEW_RESCAN_DELAY_MS
                )
            }

            override fun onCancelled(gd: GestureDescription?) {
                gestureInFlight = false
                stopHighlightTracking()
            }
        }, null)
        if (!ok) {
            gestureInFlight = false
            return
        }
        // 送り中も枠を今のカードに貼り付けて一緒に動かす（視線が迷子にならないように）
        startHighlightTracking()
    }

    private fun reselectAfterPageScroll(delta: Int, key: String, prevKeys: Set<String>) {
        if (!overviewMode) return
        refreshOverviewCards()
        if (overviewCards.isEmpty()) return
        val prevIdx = if (key.isNotEmpty()) {
            overviewCards.indexOfFirst { cardKey(it) == key }
        } else -1
        // 追跡カードが画面外に出て照合できなかった場合は、
        // 「送りで新しく現れたカード」のうち元の選択に隣接する側を選ぶ
        val newIdxs = overviewCards.indices.filter { cardKey(overviewCards[it]) !in prevKeys }
        overviewIndex = when {
            prevIdx >= 0 -> (prevIdx + delta).coerceIn(0, overviewCards.lastIndex)
            newIdxs.isNotEmpty() && delta > 0 -> newIdxs.min()  // 古い方へ: 新出の中で最も新しい側
            newIdxs.isNotEmpty() -> newIdxs.max()               // 新しい方へ: 新出の中で最も古い側
            delta > 0 -> overviewCards.lastIndex
            else -> 0
        }
        overviewScrolled = true
        showHighlight(overviewCards[overviewIndex].bounds)
        startHighlightTracking()
        Log.d(TAG, "overview reselect -> $overviewIndex / ${overviewCards.size} (prevIdx=$prevIdx)")
    }

    /**
     * ランチャー(アプリ一覧)のルートノードを全て集める。ランチャーのウィンドウは
     * 複数あり（タスクバーも別ウィンドウ）、最初の1つだけ見るとタスクバー側に
     * 当たってカードが見つからない（実測）。rootInActiveWindow も当てにならない。
     */
    private fun launcherRoots(): List<AccessibilityNodeInfo> {
        val roots = windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == LAUNCHER_PACKAGE }
        if (roots.isNotEmpty()) return roots
        val active = rootInActiveWindow
        return if (active?.packageName?.toString() == LAUNCHER_PACKAGE) listOf(active) else emptyList()
    }

    /** アプリ一覧のウィンドウからタスクカード（大きなクリック可能要素）を検出する。 */
    private fun refreshOverviewCards() {
        // ドロワーは開き切る前に検出するとホーム画面のアイコンを誤検出するため、
        // オープンシーケンス完了まで待つ（リトライ側が拾い直す）
        if (selectionIsDrawer &&
            android.os.SystemClock.uptimeMillis() - overviewOpenedAt < DRAWER_SELECT_DELAY_MS
        ) {
            return
        }
        val roots = launcherRoots()
        if (roots.isEmpty()) {
            Log.d(TAG, "refreshOverviewCards: launcher window not found " +
                    "(windows=${windows.mapNotNull { it.root?.packageName }})")
            return
        }
        val g = screenGeometry() ?: return

        overviewCards = if (selectionIsDrawer) {
            // ドロワー: 専用コンテナ(apps_view)内の id=icon ノードだけをID指定で拾う。
            // サイズや可視性のヒューリスティックでは、背後のホーム画面の
            // アイコン等が構造的に混入して枠が暴れる（実測74件）ため。
            val found = mutableListOf<OverviewCard>()
            for (root in roots) {
                val container = root.findAccessibilityNodeInfosByViewId(
                    "$LAUNCHER_PACKAGE:id/apps_view"
                )?.firstOrNull() ?: continue
                fun visit(node: AccessibilityNodeInfo?) {
                    node ?: return
                    if (node.isClickable &&
                        node.viewIdResourceName?.endsWith("/icon") == true
                    ) {
                        val b = Rect()
                        node.getBoundsInScreen(b)
                        if (b.width() >= 100 && b.height() >= 100) {
                            found += OverviewCard(node, b)
                        }
                    }
                    for (i in 0 until node.childCount) visit(node.getChild(i))
                }
                visit(container)
                if (found.isNotEmpty()) break
            }
            // 読み順（行ごとに上から下、行内は左から右）
            val rowHeight = (g[1] * 0.08f).coerceAtLeast(1f)
            found.sortedWith(
                compareBy<OverviewCard> { (it.bounds.centerY() / rowHeight).toInt() }
                    .thenBy { it.bounds.centerX() }
            )
        } else {
            // Overview: 画面の18%超の大きなタスクカード
            val minW = g[0] * 0.18f
            val minH = g[1] * 0.18f
            val found = mutableListOf<OverviewCard>()
            fun visit(node: AccessibilityNodeInfo?) {
                node ?: return
                val b = Rect()
                node.getBoundsInScreen(b)
                if (node.isClickable && node.isVisibleToUser &&
                    b.width() >= minW && b.height() >= minH
                ) {
                    found += OverviewCard(node, b)
                }
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            roots.forEach { visit(it) }
            // 入れ子のクリック要素は大きい方（カード本体）だけ残す
            val cards = found.filter { c ->
                found.none { o -> o !== c && o.bounds.contains(c.bounds) }
            }
            // 新しい順 = 右の列から左へ、同じ列は上から下
            val colWidth = (g[0] * 0.3f).coerceAtLeast(1f)
            cards.sortedWith(
                compareByDescending<OverviewCard> { (it.bounds.centerX() / colWidth).toInt() }
                    .thenBy { it.bounds.centerY() }
            )
        }
        overviewIndex = 0
        if (overviewCards.isNotEmpty()) {
            lastCardScanAt = android.os.SystemClock.uptimeMillis()
        }
        Log.d(TAG, "refreshOverviewCards: drawer=$selectionIsDrawer ${overviewCards.size} cards " +
                overviewCards.take(8).joinToString { it.bounds.toShortString() })
    }

    /** Enter確定: 選択中のカードをクリックして、そのアプリへ遷移する。 */
    private fun commitOverviewSelection() {
        if (!overviewMode) return
        overviewMode = false
        stopHighlightTracking()
        hideHighlight()
        // 矢印で動かしていなくても Enter が来たら現在の選択（先頭=元のアプリ）を確定する
        if (overviewCards.isEmpty()) refreshOverviewCards()
        val card = overviewCards.getOrNull(overviewIndex) ?: return
        Log.d(TAG, "commitOverviewSelection: click index=$overviewIndex " +
                "app=${cardKey(card)} ${card.bounds.toShortString()}")
        card.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        overviewCards = emptyList()
    }

    // --- 選択枠ハイライトの描画 ---

    private inner class HighlightView(context: Context) : View(context) {
        private var current: RectF? = null
        private var animator: android.animation.ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 14f
            color = 0xFF2979FF.toInt()   // はっきりした青 (Blue A400)
        }

        /** 枠を目標矩形へスッと滑らせる（瞬間ワープだとぎこちないため）。 */
        fun moveTo(rect: Rect) {
            val target = RectF(rect)
            val from = current
            if (from == null) {
                // 初回は即表示
                current = target
                invalidate()
                return
            }
            if (from == target) return
            animator?.cancel()
            val start = RectF(from)
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 30L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    current = RectF(
                        start.left + (target.left - start.left) * f,
                        start.top + (target.top - start.top) * f,
                        start.right + (target.right - start.right) * f,
                        start.bottom + (target.bottom - start.bottom) * f
                    )
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val r = current ?: return
            canvas.drawRoundRect(r, 48f, 48f, paint)
        }
    }

    private fun showHighlight(bounds: Rect) {
        val view = highlightView ?: run {
            val wm = getSystemService(WindowManager::class.java) ?: return
            val v = HighlightView(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            try {
                wm.addView(v, lp)
            } catch (e: Exception) {
                Log.w(TAG, "showHighlight failed", e)
                return
            }
            highlightView = v
            v
        }
        view.moveTo(bounds)
    }

    private fun hideHighlight() {
        val view = highlightView ?: return
        highlightView = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(view)
        } catch (_: Exception) {
        }
    }

    /**
     * 下端ナビバーの横スワイプ（クイックスイッチ）を注入して隣のアプリへ切り替える。
     * 前半は速く、後半は減速して指を離す2段階ストロークで手のスワイプ感を再現。
     * 注意: タスクバー固定表示の画面では横スワイプのクイックスイッチ自体が
     * OSに存在しないため効かない（タスクバーを一時表示にすると使える）。
     */
    private fun performQuickSwitchBySwipe(toRightApp: Boolean) {
        if (gestureInFlight) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val navBottom = metrics.windowInsets
            .getInsets(android.view.WindowInsets.Type.navigationBars())
            .bottom
        // 細いジェスチャーバーならその中央、固定タスクバー等は最下端ぎりぎりを狙う
        val y = if (navBottom in 1 until TASKBAR_MIN_HEIGHT_PX) h - navBottom / 2f else h - 15f
        Log.d(TAG, "performQuickSwitchBySwipe(toRight=$toRightApp, navBottom=$navBottom, y=$y)")

        // タッチスクロールの向き: 右側(新しい方)のアプリを出す = バーを左へフリック
        val swipeLeft = if (SWAP_DIRECTIONS) !toRightApp else toRightApp
        val distance = w * SWIPE_WIDTH_RATIO
        val startX = w / 2f
        val dir = if (swipeLeft) -1f else 1f
        val midX = startX + dir * distance * QS_SEG1_RATIO
        val endX = startX + dir * distance

        val path1 = Path().apply {
            moveTo(startX, y)
            lineTo(midX, y)
        }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, QS_SEG1_MS, true)

        gestureInFlight = true
        val done: (String) -> Unit = { msg ->
            Log.d(TAG, msg)
            gestureInFlight = false
        }
        val dispatched = dispatchGesture(buildGesture(stroke1), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                val path2 = Path().apply {
                    moveTo(midX, y)
                    lineTo(endX, y)
                }
                val stroke2 = stroke1.continueStroke(path2, 0, QS_SEG2_MS, false)
                val ok = dispatchGesture(buildGesture(stroke2), object : GestureResultCallback() {
                    override fun onCompleted(g2: GestureDescription?) =
                        done("quick-switch swipe completed")

                    override fun onCancelled(g2: GestureDescription?) =
                        done("quick-switch swipe cancelled (seg2)")
                }, null)
                if (!ok) done("quick-switch swipe dispatch failed (seg2)")
            }

            override fun onCancelled(g: GestureDescription?) =
                done("quick-switch swipe cancelled (seg1)")
        }, null)
        if (!dispatched) gestureInFlight = false
    }

    private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription =
        GestureDescription.Builder()
            .setDisplayId(Display.DEFAULT_DISPLAY)
            .addStroke(stroke)
            .build()
}
