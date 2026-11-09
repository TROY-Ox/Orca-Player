package com.troy.orcaplayer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the stack trace of a crash so the next launch can show it (and the user can send it). It is
 * kept on the device only, never sent anywhere. While a report is waiting, the app starts in a safe
 * mode that skips the optional start-up work, so the report can always be shown.
 */
object CrashReport {
    private fun file(context: Context) = File(context.filesDir, "crash/last.txt")

    fun install(context: Context, process: String) {
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    appendLine("ORCA Player ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("Process: $process, thread: ${thread.name}")
                    appendLine()
                    append(sw.toString().take(60_000))
                }
                val f = file(app)
                f.parentFile?.mkdirs()
                f.writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A crash report waiting to be shown (only crashes of the main process). */
    fun pending(context: Context): String? = runCatching {
        file(context).takeIf { it.exists() }?.readText()?.takeIf { "Process: ${context.packageName}," in it }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}

/** Shows the last crash with Copy and Share, then continues into the app. Plain views, no Compose or Hilt. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReport.pending(this) ?: run {
            restart()
            return
        }
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 15, 20))
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.crash_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.crash_message)
            setTextColor(Color.rgb(190, 200, 210))
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = report
            setTextColor(Color.rgb(160, 230, 220))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
            text = getString(label)
            setOnClickListener { onClick() }
            buttons.addView(this)
        }
        button(R.string.crash_copy) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ORCA Player crash", report))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        }
        button(R.string.crash_share) {
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), null))
            }
        }
        button(R.string.crash_continue) {
            CrashReport.clear(this)
            restart()
        }
        root.addView(buttons)
        setContentView(root)
    }

    /** Starts the app again in a fresh process, so the normal start-up runs. */
    private fun restart() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) startActivity(launch)
        finish()
        Runtime.getRuntime().exit(0)
    }
}
