package jp.miyashita.keyswipe

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * 【実験】本体を開いていく角度に合わせて、外側画面をすりガラス状に霞ませる。
 *
 * ヒンジ角センサー（TYPE_HINGE_ANGLE）の値に応じて、外側画面の上に
 * 「背後ぼかし＋白の薄膜」のオーバーレイを重ねる。内側画面へ切り替わったら
 * （画面サイズが外側のものでなくなったら）外す。
 *
 * 実機で確かめたい点（資料では確認できなかった）:
 * - 何度で内側画面に切り替わるか（＝霞ませられる角度の幅）
 * - TYPE_ACCESSIBILITY_OVERLAY で FLAG_BLUR_BEHIND が効くか
 * そのため、霞の上に現在の角度を表示し、切り替わった角度をログに出す。
 */
class FoldVeil(private val service: AccessibilityService) : SensorEventListener {

    private val sensorManager = service.getSystemService(SensorManager::class.java)
    private val hingeSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private val windowManager = service.getSystemService(WindowManager::class.java)

    private var listening = false
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    // 閉じている間に測った外側画面のサイズ（短辺・長辺）。回転しても比較できる形で持つ
    private var outerShort = 0
    private var outerLong = 0
    private var lastAngle = 0f

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> sync() }

    fun start() {
        Prefs.registerListener(service, prefsListener)
        sync()
    }

    fun stop() {
        Prefs.unregisterListener(service, prefsListener)
        setListening(false)
    }

    /** 設定スイッチの状態にセンサー監視を合わせる（OFFの間はセンサーを使わない）。 */
    private fun sync() {
        setListening(Prefs.isMasterEnabled(service) && Prefs.isFoldVeilEnabled(service))
    }

    private fun setListening(on: Boolean) {
        if (on == listening) return
        if (on) {
            val sensor = hingeSensor
            if (sensor == null) {
                Log.w(TAG, "ヒンジ角センサーが見つかりません（この端末では使えません）")
                return
            }
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensorManager?.unregisterListener(this)
            hide()
        }
        listening = on
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values.firstOrNull() ?: return
        lastAngle = angle
        val bounds = windowManager?.currentWindowMetrics?.bounds ?: return
        val short = min(bounds.width(), bounds.height())
        val long = max(bounds.width(), bounds.height())

        if (angle < CLOSED_DEG) {
            // 閉じている＝いま点いているのは外側画面。そのサイズを覚えておく
            outerShort = short
            outerLong = long
            hide()
            return
        }
        val onOuter = outerShort > 0 && short == outerShort && long == outerLong
        if (!onOuter) {
            if (view != null) Log.i(TAG, "内側画面へ切り替わり: %.0f°".format(angle))
            hide()
            return
        }
        show(((angle - CLOSED_DEG) / (FULL_DEG - CLOSED_DEG)).coerceIn(0f, 1f), angle)
    }

    private fun show(strength: Float, angle: Float) {
        val wm = windowManager ?: return
        val v = view ?: TextView(service).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.DKGRAY)
        }
        val lp = params ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.flags = it.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }
        }

        v.setBackgroundColor(Color.argb((strength * MAX_FILM_ALPHA).toInt(), 255, 255, 255))
        v.text = "%.0f°".format(angle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lp.blurBehindRadius = (strength * MAX_BLUR_PX).toInt()
        }
        try {
            if (view == null) {
                wm.addView(v, lp)
                view = v
                params = lp
            } else {
                wm.updateViewLayout(v, lp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "show failed", e)
        }
    }

    private fun hide() {
        val v = view ?: return
        view = null
        params = null
        try {
            windowManager?.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "hide failed", e)
        }
    }

    private companion object {
        const val TAG = "FoldVeil"
        const val CLOSED_DEG = 5f     // これ未満は「閉じている」とみなす
        const val FULL_DEG = 60f      // この角度で霞が最大になる（実測後に調整）
        const val MAX_BLUR_PX = 80f   // 背後ぼかしの最大半径
        const val MAX_FILM_ALPHA = 110f // 白い薄膜の最大不透明度（0〜255）
    }
}
