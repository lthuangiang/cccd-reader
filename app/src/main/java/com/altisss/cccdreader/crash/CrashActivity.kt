package com.altisss.cccdreader.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Hiện ra khi app crash ở bất kỳ đâu (xem GlobalCrashHandler). Hiển thị full stack trace,
 * có nút Copy để người dùng copy gửi đi debug, và nút Đóng để thoát hẳn app.
 */
class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashText = GlobalCrashHandler.readLastCrash(this) ?: "(Không đọc được crash log)"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }

        val title = TextView(this).apply {
            text = "App đã gặp lỗi và crash"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val tvCrash = TextView(this).apply {
            text = crashText
            textIsSelectable = true
            setTextIsSelectable(true)
            setPadding(8, 8, 8, 8)
        }
        scrollView.addView(tvCrash)
        root.addView(scrollView)

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        val btnCopy = Button(this).apply {
            text = "Copy toàn bộ lỗi"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Crash log", crashText))
                Toast.makeText(this@CrashActivity, "Đã copy, gửi lại nội dung này để debug", Toast.LENGTH_LONG).show()
            }
        }
        buttonBar.addView(btnCopy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val btnClose = Button(this).apply {
            text = "Đóng"
            setOnClickListener { finishAffinity() }
        }
        buttonBar.addView(btnClose, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(buttonBar)

        setContentView(root)
    }
}