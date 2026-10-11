package dev.opencodelab.pocket.runtime

/** 持有 App 进程内启动的 opencode serve 子进程。App 被杀时子进程一并结束，下次启动重拉。 */
object ServeHolder {
    var process: Process? = null

    fun stop() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        try {
            process?.waitFor()
        } catch (_: Exception) {
        }
        process = null
    }

    fun isAlive(): Boolean {
        return try {
            process?.isAlive == true
        } catch (_: Exception) {
            false
        }
    }
}
