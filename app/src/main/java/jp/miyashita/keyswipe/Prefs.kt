package jp.miyashita.keyswipe

import android.content.Context
import android.content.SharedPreferences

/** アプリ設定。サービスと設定画面の両方から参照する。 */
object Prefs {
    private const val NAME = "keyswipe_prefs"
    private const val KEY_UPDOWN_ENABLED = "updown_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Ctrl+↑/↓（Recents / アプリドロワー）を横取りするか。既定は有効。 */
    fun isUpDownEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UPDOWN_ENABLED, true)

    fun setUpDownEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_UPDOWN_ENABLED, enabled).apply()
    }

    // --- Alt+トラックボールスクロール ---

    private const val KEY_SCROLL_SPEED = "scroll_speed"
    private const val KEY_SCROLL_ACCEL = "scroll_accel"

    const val SCROLL_SPEED_DEFAULT = 1.0f   // 倍率 (0.2〜5.0)
    const val SCROLL_ACCEL_DEFAULT = 0.5f   // 加速係数 (0.0〜2.0)

    /** スクロール速度（移動量の倍率）。 */
    fun getScrollSpeed(context: Context): Float =
        prefs(context).getFloat(KEY_SCROLL_SPEED, SCROLL_SPEED_DEFAULT)

    fun setScrollSpeed(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_SCROLL_SPEED, value).apply()
    }

    /** スクロール加速（速く回すほど倍率が上がる係数）。0で加速なし。 */
    fun getScrollAccel(context: Context): Float =
        prefs(context).getFloat(KEY_SCROLL_ACCEL, SCROLL_ACCEL_DEFAULT)

    fun setScrollAccel(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_SCROLL_ACCEL, value).apply()
    }

    // スクロールに使う修飾キー。キーボードによっては物理の Option が
    // Ctrl として届くことがある（実測: ZMK系キーボードで確認）ため選択式。
    private const val KEY_SCROLL_MODIFIER = "scroll_modifier"
    const val MOD_ALT = "alt"
    const val MOD_CTRL = "ctrl"
    const val MOD_META = "meta"

    fun getScrollModifier(context: Context): String =
        prefs(context).getString(KEY_SCROLL_MODIFIER, MOD_ALT) ?: MOD_ALT

    fun setScrollModifier(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SCROLL_MODIFIER, value).apply()
    }
}
