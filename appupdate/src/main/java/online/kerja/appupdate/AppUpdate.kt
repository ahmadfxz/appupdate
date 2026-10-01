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
 * Every time the app comes to the foreground it asks `GET {baseUrl}/api/v1/update/{package}
 * ?version_code={versionCode}`. When the answer is `need_update=true`, every screen of the app is
 * replaced by [UpdateRequiredActivity], which only offers the update link. The answer is cached,
 * so the block survives a restart without network; it is lifted when the server says otherwise
 * or the installed versionCode changes. Network or server errors never block the app.
 *
 * Until the server has answered in the current foreground session, a cached block shows only a
 * neutral loading screen, so lifting it on the server does not flash the update message.
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

    internal var required = false
        private set

    /**
     * [required] reflects this foreground session: the server answered, or could not be reached
     * and the cached answer stands. Until then a cached block shows no update message.
     */
    internal var settled = false
        private set
    internal var updateUrl = ""
        private set
    internal var packageName = ""
        private set

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
            required = prefs.getBoolean(KEY_REQUIRED, false)
            updateUrl = prefs.getString(KEY_URL, "").orEmpty()
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
                settled = true
                if (result != null) {
                    apply(result.first, result.second)
                } else {
                    (resumed as? UpdateRequiredActivity)?.render()
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
        prefs.edit().putBoolean(KEY_REQUIRED, needUpdate).putString(KEY_URL, url).apply()

        val activity = resumed ?: return
        when {
            needUpdate && activity !is UpdateRequiredActivity -> block(activity)
            needUpdate && activity is UpdateRequiredActivity -> activity.render()
            !needUpdate && activity is UpdateRequiredActivity -> activity.release()
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
            // Back in the background: the next foreground session waits for a fresh answer.
            if (startedActivities == 0) settled = false
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            if (resumed === activity) resumed = null
        }
    }
}
