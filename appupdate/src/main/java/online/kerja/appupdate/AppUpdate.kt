package online.kerja.appupdate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Forced app update. Call [install] once from `Application.onCreate`:
 *
 * ```
 * AppUpdate.install(this)
 * ```
 *
 * The app always opens normally. Every time it comes to the foreground it asks
 * `GET {baseUrl}/api/v1/update/{package}?version_code={versionCode}` in the background; when the
 * answer is `need_update=true`, every screen of the app is replaced by [UpdateRequiredActivity],
 * which only offers the update link.
 *
 * The last answer is cached and used only when the server cannot be reached, so turning the
 * network off does not escape a block; it is forgotten when the installed versionCode changes.
 * Without a cached block, network or server errors never block the app.
 *
 * All state is main-thread only.
 */
object AppUpdate {

    const val DEFAULT_BASE_URL = "https://dev.kerja.online"

    private const val TAG = "AppUpdate"
    private const val PREFS = "online.kerja.appupdate"
    private const val KEY_REQUIRED = "required"
    private const val KEY_URL = "url"
    private const val KEY_VERSION = "version_code"
    private const val TIMEOUT_MS = 10_000

    private lateinit var application: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var endpoint: String
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var installed = false
    private var checking = false
    private var startedActivities = 0
    private var resumed: Activity? = null

    /** The block in force in this process; only ever set from a finished check. */
    internal var required = false
        private set
    internal var updateUrl = ""
        private set
    internal var packageName = ""
        private set

    /** The last answer from the server, applied only when a check fails. */
    private var cachedRequired = false
    private var cachedUrl = ""

    /**
     * @param baseUrl the update API, without a trailing path.
     * @param packageName the key looked up on the server; defaults to the installed package name.
     *   Pass a fixed one when build variants change it (e.g. a `.debug` suffix).
     */
    @JvmStatic
    @JvmOverloads
    fun install(
        application: Application,
        baseUrl: String = DEFAULT_BASE_URL,
        packageName: String = application.packageName,
    ) {
        if (installed) return
        installed = true
        this.application = application
        this.packageName = packageName

        val versionCode = installedVersionCode(application)
        endpoint = baseUrl.trimEnd('/') + "/api/v1/update/" +
            URLEncoder.encode(packageName, "UTF-8") + "?version_code=" + versionCode

        prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_VERSION, -1) == versionCode) {
            cachedRequired = prefs.getBoolean(KEY_REQUIRED, false)
            cachedUrl = prefs.getString(KEY_URL, "").orEmpty()
        } else {
            // A different build is installed (most likely the update itself): forget the old answer.
            prefs.edit().clear().putLong(KEY_VERSION, versionCode).apply()
        }

        application.registerActivityLifecycleCallbacks(lifecycle)
    }

    /** Asks the server again; the result is applied to whatever screen is showing. */
    internal fun check() {
        if (checking) return
        checking = true
        io.execute {
            val result = fetch()
            main.post {
                checking = false
                if (result != null) {
                    cachedRequired = result.first
                    cachedUrl = result.second
                    prefs.edit().putBoolean(KEY_REQUIRED, result.first).putString(KEY_URL, result.second).apply()
                    apply(result.first, result.second)
                } else if (!required && cachedRequired) {
                    // Server unreachable: the last known block still stands.
                    apply(true, cachedUrl)
                }
            }
        }
    }

    /** @return (needUpdate, updateUrl), or null when the answer is unknown (keep the cache). */
    private fun fetch(): Pair<Boolean, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    json.optBoolean("need_update", false) to json.optString("update_url", "")
                }
                HttpURLConnection.HTTP_NOT_FOUND -> false to ""
                else -> null
            }
        } catch (e: IOException) {
            Log.w(TAG, "update check failed: ${e.message}")
            null
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "bad update response: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun apply(needUpdate: Boolean, url: String) {
        required = needUpdate
        updateUrl = url

        val activity = resumed ?: return
        if (needUpdate && activity !is UpdateRequiredActivity) {
            block(activity)
        } else if (!needUpdate && activity is UpdateRequiredActivity) {
            activity.release()
        }
    }

    /** Replaces the whole task with the update screen, so nothing of the app stays reachable. */
    private fun block(activity: Activity) {
        activity.startActivity(
            Intent(activity, UpdateRequiredActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        activity.finish()
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (required && activity !is UpdateRequiredActivity) block(activity)
        }

        override fun onActivityStarted(activity: Activity) {
            if (startedActivities++ == 0) check()
        }

        override fun onActivityResumed(activity: Activity) {
            resumed = activity
            if (required && activity !is UpdateRequiredActivity) block(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed === activity) resumed = null
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            if (resumed === activity) resumed = null
        }
    }
}
