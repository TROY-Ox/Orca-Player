package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import java.lang.ref.WeakReference

/**
 * Host copy of CloudStream's application companion: the context and the small key-value store
 * extensions keep their settings in. The host sets [context] before loading any extension.
 * (GPL-3.0, extended build only.)
 */
class CloudStreamApp {
    companion object {
        tailrec fun Context.getActivity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.getActivity()
            else -> null
        }

        private var _context: WeakReference<Context>? = null
        var context: Context?
            get() = _context?.get()
            internal set(value) {
                _context = WeakReference(value)
            }

        /** Called by the extension host. */
        fun attachHostContext(value: Context) {
            context = value.applicationContext ?: value
        }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? = context?.getKey(path, valueType)

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun removeKeys(folder: String): Int? = context?.removeKeys(folder)

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = context?.getKey(path, defVal)

        inline fun <reified T : Any> getKey(path: String): T? = context?.getKey(path)

        inline fun <reified T : Any> getKey(folder: String, path: String): T? = context?.getKey(folder, path)

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? = context?.getKey(folder, path, defVal)

        fun getKeys(folder: String): List<String>? = context?.getKeys(folder)

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        fun openBrowser(url: String, activity: Activity?) {
            val ctx: Context = activity ?: context ?: return
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** The older name for [CloudStreamApp], still used by many extensions. */
class AcraApplication {
    companion object {
        val context: Context? get() = CloudStreamApp.context

        fun removeKeys(folder: String): Int? = CloudStreamApp.removeKeys(folder)

        fun <T> setKey(path: String, value: T) = CloudStreamApp.setKey(path, value)

        fun <T> setKey(folder: String, path: String, value: T) = CloudStreamApp.setKey(folder, path, value)

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = CloudStreamApp.getKey(path, defVal)

        inline fun <reified T : Any> getKey(path: String): T? = CloudStreamApp.getKey(path)

        inline fun <reified T : Any> getKey(folder: String, path: String): T? = CloudStreamApp.getKey(folder, path)

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? = CloudStreamApp.getKey(folder, path, defVal)

        fun getKeys(folder: String): List<String>? = CloudStreamApp.getKeys(folder)

        fun removeKey(folder: String, path: String) = CloudStreamApp.removeKey(folder, path)

        fun removeKey(path: String) = CloudStreamApp.removeKey(path)
    }
}

/**
 * Host copy of the parts of CloudStream's CommonActivity that extensions call: the current
 * activity (none in the extension process) and toasts, shown through the application context.
 */
object CommonActivity {
    private var _activity: WeakReference<Activity>? = null
    var activity: Activity?
        get() = _activity?.get()
        private set(value) {
            _activity = WeakReference(value)
        }

    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }

    private fun toast(message: String?, duration: Int?) {
        val ctx = activity ?: CloudStreamApp.context ?: return
        if (message.isNullOrBlank()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(ctx, message, duration ?: android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    fun showToast(message: Int, duration: Int? = null) {
        val ctx = activity ?: CloudStreamApp.context ?: return
        toast(runCatching { ctx.getString(message) }.getOrNull(), duration)
    }

    fun showToast(message: String?, duration: Int? = null) = toast(message, duration)

    fun showToast(act: Activity?, message: Int, duration: Int? = null) {
        val ctx = act ?: CloudStreamApp.context ?: return
        toast(runCatching { ctx.getString(message) }.getOrNull(), duration)
    }

    fun showToast(act: Activity?, message: String?, duration: Int? = null) = toast(message, duration)
}
