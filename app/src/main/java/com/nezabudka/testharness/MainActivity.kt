package com.nezabudka.testharness

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val regular = Typeface.createFromAsset(assets, "fonts/DINish-Regular.ttf")
        val semiBold = Typeface.createFromAsset(assets, "fonts/DINish-SemiBold.ttf")
        val bold = Typeface.createFromAsset(assets, "fonts/DINish-Bold.ttf")

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "DINish — тест кириллицы Android"
            textSize = 24f
            typeface = bold
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        })

        root.addView(TextView(this).apply {
            text = "Редактируй текст прямо в полях. Особенно попробуй заменить первую строчную букву на заглавную — это тот сценарий, который ломается в Corel."
            textSize = 16f
            typeface = regular
            setPadding(0, 0, 0, dp(16))
        })

        addTestBlock(root, "Regular", regular)
        addTestBlock(root, "SemiBold", semiBold)
        addTestBlock(root, "Bold", bold)

        root.addView(TextView(this).apply {
            text = "Полный русский алфавит"
            textSize = 18f
            typeface = semiBold
            setPadding(0, dp(16), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ\nабвгдеёжзийклмнопрстуфхцчшщъыьэюя\nЁё Ъъ Ыы Ээ Йй Щщ"
            textSize = 22f
            typeface = regular
            setTextIsSelectable(true)
        })

        setContentView(scroll)
    }

    private fun addTestBlock(root: LinearLayout, label: String, face: Typeface) {
        root.addView(TextView(this).apply {
            text = label
            textSize = 18f
            typeface = face
            setPadding(0, dp(10), 0, dp(4))
        })

        root.addView(EditText(this).apply {
            setText("алексей, принять таблетки сегодня в 20:00. Ёжик съешь ещё этих мягких французских булок. Объём, подъём, съёмка, жёлтый, широкий, энергия.")
            textSize = 22f
            typeface = face
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
