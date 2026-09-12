package dev.voicecommander.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.voicecommander.AppState
import dev.voicecommander.overlay.InputService

/**
 * The Edit step of the review popup: quick edit, own Send (delivers, closes)
 * or back/Cancel (returns to the popup untouched).
 */
class EditActivity : Activity() {
    private lateinit var edit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edit = EditText(this).apply {
            isSingleLine = false
            minLines = 4
            gravity = Gravity.TOP
            setText(intent.getStringExtra(InputService.EXTRA_TEXT) ?: "")
            setSelection(length())
        }
        val send = Button(this).apply { text = "Send" }
        send.setOnClickListener {
            AppState.deliverAction?.invoke(edit.text.toString())
            finish()
        }
        val cancel = Button(this).apply { text = "Cancel" }
        cancel.setOnClickListener { finish() }

        val title = TextView(this).apply {
            text = "Edit instruction"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(12))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(send, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(title)
            addView(edit)
            addView(row)
        }
        setContentView(col)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}