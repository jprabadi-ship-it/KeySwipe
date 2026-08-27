package jp.miyashita.keyswipe

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)

        // Ctrl+↑/↓ の有効/無効
        findViewById<MaterialSwitch>(R.id.updown_switch).apply {
            isChecked = Prefs.isUpDownEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setUpDownEnabled(this@MainActivity, checked)
            }
        }

        // スクロールの修飾キー
        val modGroup = findViewById<MaterialButtonToggleGroup>(R.id.mod_group)
        val modButtonId = when (Prefs.getScrollModifier(this)) {
            Prefs.MOD_CTRL -> R.id.btn_mod_ctrl
            Prefs.MOD_META -> R.id.btn_mod_meta
            else -> R.id.btn_mod_alt
        }
        modGroup.check(modButtonId)
        modGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val value = when (checkedId) {
                R.id.btn_mod_ctrl -> Prefs.MOD_CTRL
                R.id.btn_mod_meta -> Prefs.MOD_META
                else -> Prefs.MOD_ALT
            }
            Prefs.setScrollModifier(this, value)
        }

        // 速度スライダー: 内部値 4〜100 → 0.20〜5.00 倍（0.05 刻み）
        val speedLabel = findViewById<TextView>(R.id.speed_label)
        val speedSlider = findViewById<Slider>(R.id.speed_slider)
        fun speedOf(sliderValue: Float) = sliderValue / 20f
        fun updateSpeedLabel(v: Float) {
            speedLabel.text = getString(
                R.string.scroll_speed_label, String.format(Locale.JAPAN, "%.2f", v)
            )
        }
        speedSlider.value = (Prefs.getScrollSpeed(this) * 20f).roundToInt()
            .coerceIn(4, 100).toFloat()
        updateSpeedLabel(speedOf(speedSlider.value))
        speedSlider.setLabelFormatter { String.format(Locale.JAPAN, "%.2f", speedOf(it)) }
        speedSlider.addOnChangeListener { _, value, fromUser ->
            val v = speedOf(value)
            updateSpeedLabel(v)
            if (fromUser) Prefs.setScrollSpeed(this, v)
        }

        // 加速スライダー: 内部値 0〜100 → 0.00〜2.00（0.02 刻み）
        val accelLabel = findViewById<TextView>(R.id.accel_label)
        val accelSlider = findViewById<Slider>(R.id.accel_slider)
        fun accelOf(sliderValue: Float) = sliderValue / 50f
        fun updateAccelLabel(v: Float) {
            accelLabel.text = getString(
                R.string.scroll_accel_label, String.format(Locale.JAPAN, "%.2f", v)
            )
        }
        accelSlider.value = (Prefs.getScrollAccel(this) * 50f).roundToInt()
            .coerceIn(0, 100).toFloat()
        updateAccelLabel(accelOf(accelSlider.value))
        accelSlider.setLabelFormatter { String.format(Locale.JAPAN, "%.2f", accelOf(it)) }
        accelSlider.addOnChangeListener { _, value, fromUser ->
            val v = accelOf(value)
            updateAccelLabel(v)
            if (fromUser) Prefs.setScrollAccel(this, v)
        }

        findViewById<MaterialButton>(R.id.open_settings_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true
        statusText.text = getString(
            if (enabled) R.string.status_enabled else R.string.status_disabled
        )
        statusText.setTextColor(
            MaterialColors.getColor(
                statusText,
                if (enabled) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorError
            )
        )
    }
}
