package dev.opencodelab.pocket.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

object TermuxBridge {
    const val PACKAGE = "com.termux"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"

    const val PREFIX = "/data/data/com.termux/files/usr"
    const val DEFAULT_WORKDIR = "~/"

    @Suppress("DEPRECATION")
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun runBackgroundCommand(
        context: Context,
        path: String,
        args: Array<String> = emptyArray(),
        workdir: String = DEFAULT_WORKDIR,
        label: String,
        description: String
    ) {
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            component = ComponentName(PACKAGE, "com.termux.app.RunCommandService")
            putExtra(EXTRA_PATH, path)
            putExtra(EXTRA_ARGS, args)
            putExtra(EXTRA_WORKDIR, workdir)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_DESCRIPTION, description)
        }
        context.startService(intent)
    }

    fun openTermuxAppInfo(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PACKAGE")))
    }
}
