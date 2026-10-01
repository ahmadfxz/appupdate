package online.kerja.appupdate

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/**
 * The only screen left while an update is required: a message and a button that opens the
 * update link. Back sends the app to the background instead of returning into it. While the
 * block only comes from the cache ([AppUpdate.settled] is false) it shows the app icon and a
 * spinner instead, so a block lifted on the server reads as a splash, not as an update prompt.
 */
class UpdateRequiredActivity : Activity() {

    private lateinit var progress: ProgressBar
    private lateinit var prompt: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { moveTaskToBack(true) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!AppUpdate.required) {
            release()
            return
        }
        render()
        AppUpdate.check()
    }

    /** Shows the update prompt once the block is confirmed, a spinner until then. */
    internal fun render() {
        val ready = AppUpdate.settled
        progress.visibility = if (ready) View.GONE else View.VISIBLE
        prompt.forEach { it.visibility = if (ready) View.VISIBLE else View.GONE }
    }

    @Deprecated("Below API 33 only; newer versions use the OnBackInvokedCallback.")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    /** The update is no longer required: go back to the app's launcher screen. */
    internal fun release() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
    }

    private fun openUpdateLink() {
        val playStore = "https://play.google.com/store/apps/details?id=${AppUpdate.packageName}"
        val url = AppUpdate.updateUrl.ifBlank { playStore }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            if (url != playStore) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(playStore))) }
            }
        }
    }

    private fun buildContent(): LinearLayout {
        val pad = dp(32)
        val title = TextView(this).apply {
            setText(R.string.appupdate_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val message = TextView(this).apply {
            setText(R.string.appupdate_message)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        val button = Button(this).apply {
            setText(R.string.appupdate_button)
            setOnClickListener { openUpdateLink() }
        }
        progress = ProgressBar(this)
        prompt = listOf(title, message, button)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)

            addView(ImageView(context).apply {
                setImageDrawable(applicationInfo.loadIcon(packageManager))
            }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(24) })
            addView(progress, wrap())
            addView(title, wrap().apply { bottomMargin = dp(12) })
            addView(message, wrap().apply { bottomMargin = dp(32) })
            addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        }
    }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()
}
