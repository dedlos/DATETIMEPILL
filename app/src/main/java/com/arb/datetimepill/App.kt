package com.arb.datetimepill

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The floating DATE | TIME pill.
 * It ignores every screen event. It only acts when you tap the pill,
 * and then only writes text into the box your cursor is in.
 * There is no internet permission, so nothing can leave the phone.
 */
class PillService : AccessibilityService() {

    companion object {
        var instance: PillService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pill: LinearLayout? = null
    private val inserted = mutableMapOf<String, String>() // "date"/"time" -> text we pasted
    private val hideRunnable = Runnable { hidePill() }

    // How long the pill stays after a tap (milliseconds)
    private val stayAfterTap = 3000L

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately empty: we never look at what is on screen.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hidePill()
        instance = null
        return super.onUnbind(intent)
    }

    fun isShowing() = pill != null

    fun toggle() {
        if (pill == null) {
            showPill()
            // Close the swipe-down panel so your text box is active again
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }, 250)
        } else {
            hidePill()
        }
    }

    // ---------- Formats (change these to fine-tune) ----------
    private fun dateText() = SimpleDateFormat("ddMMMyy", Locale.ENGLISH).format(Date()) // 07Nov26
    private fun timeText() = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date())   // 14:35

    private fun textFor(side: String) = if (side == "date") dateText() else timeText()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- The pill itself ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sideLabel(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(14), dp(28), dp(14))
        setOnClickListener { onTap() }
    }

    private fun showPill() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        inserted.clear()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.parseColor("#2B2B2B"))
                setStroke(dp(2), Color.WHITE)
            }
            addView(sideLabel("DATE") { tap("date") })
            addView(View(this@PillService).apply {
                setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(2), LinearLayout.LayoutParams.MATCH_PARENT)
            })
            addView(sideLabel("TIME") { tap("time") })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE keeps your cursor in the text box
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(340) // height above the bottom of the screen; change to move the pill
        }

        wm.addView(row, params)
        pill = row
    }

    private fun hidePill() {
        handler.removeCallbacks(hideRunnable)
        pill?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        pill = null
        inserted.clear()
    }

    // ---------- Pasting ----------
    private fun tap(side: String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (node != null && node.isPassword) {
            toast("Not available in password boxes")
            return
        }

        // Route 1: normal text boxes (Keep, messages, notes...)
        var done = false
        if (node != null && node.isEditable) {
            done = insertDirect(node, side)
        }

        // Route 2: apps like Docs and Sheets, via the clipboard
        if (!done) {
            done = insertByPaste(side)
        }

        if (!done) {
            toast("Couldn't find a place to paste")
        }

        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, stayAfterTap)
    }

    private fun insertDirect(node: AccessibilityNodeInfo, side: String): Boolean {
        val current = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val prev = inserted[side]

        if (prev != null) {
            // Second tap on the same side: take it back out
            inserted.remove(side)
            val i = current.lastIndexOf(prev)
            if (i >= 0) {
                writeText(node, current.removeRange(i, i + prev.length), i)
            }
            return true
        }

        val text = textFor(side)
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0) {
            s = current.length
            e = current.length
        }
        val from = minOf(s, e).coerceIn(0, current.length)
        val to = maxOf(s, e).coerceIn(0, current.length)
        val ok = writeText(node, current.substring(0, from) + text + current.substring(to), from + text.length)
        if (ok) inserted[side] = text
        return ok
    }

    // Writes the text, then checks the box really changed.
    // Returns false if the box ignored us, so the clipboard route can take over.
    private fun writeText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)

        // Give the app a moment, then look again
        try {
            Thread.sleep(80)
        } catch (_: InterruptedException) {
        }
        node.refresh()
        val now = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        return now == text
    }

    // Clipboard route: no "take it back out" here, use the app's own undo.
    private fun insertByPaste(side: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: findFocusedNode(root, 0)
            ?: return false

        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val old = try {
            cm.primaryClip
        } catch (_: Exception) {
            null
        }

        cm.setPrimaryClip(ClipData.newPlainText("date/time", textFor(side)))
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // Put your old clipboard back shortly afterwards
        handler.postDelayed({
            try {
                if (old != null) cm.setPrimaryClip(old) else cm.clearPrimaryClip()
            } catch (_: Exception) {
            }
        }, 700)

        return ok
    }

    private fun findFocusedNode(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 25) return null
        if (n.isFocused) return n
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            val found = findFocusedNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }
}

/** The tile in the swipe-down panel that shows or hides the pill. */
class PillTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val service = PillService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "First turn on Date Time Pill in Accessibility settings",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        service.toggle()
        refresh()
    }

    private fun refresh() {
        qsTile?.apply {
            label = "Date/Time"
            state = if (PillService.instance?.isShowing() == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}

/** A small setup screen with shortcuts to the two settings pages you need. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        column.addView(TextView(this).apply {
            text = "Date Time Pill\n\n" +
                "Setup, once only:\n" +
                "1. Tap the first button. Tap the three dots (top right) and choose " +
                "\"Allow restricted settings\".\n" +
                "2. Tap the second button, find Date Time Pill and switch it on.\n" +
                "3. Swipe down twice, tap the pencil, and drag the Date/Time tile into your panel.\n\n" +
                "Then, whenever you want the pill, tap the tile."
            textSize = 16f
        })

        column.addView(Button(this).apply {
            text = "1. App info (restricted settings)"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        column.addView(Button(this).apply {
            text = "2. Accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        setContentView(column)
    }
}
