package dev.opencodelab.pocket.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * 内置 OpenCode 运行时管理器。
 * 首次启动从 hope2333 Release 下载 bionic 版 binary，解压到 App 私有目录，
 * 后台启动 `opencode serve`，无需 Termux。
 */
object RuntimeManager {

    // hope2333 opencode-termux v2 主线（与本机验证过的 2.0.22 同版本）
    const val RUNTIME_URL =
        "https://github.com/Hope2333/opencode-termux/releases/download/Push261005/opencode-2.0.22-1-aarch64.pkg.tar.xz"
    const val RUNTIME_SHA256 =
        "9830eb45f64b5caa1b797c6a399eab29a528a4ba3273cc4ad4430700cd877cbf"
    const val RUNTIME_VERSION = "2.0.22"

    private const val BIN_REL = "data/data/com.termux/files/usr/bin/opencode"
    private const val SO_REL = "data/data/com.termux/files/usr/lib/opencode/libopencode-crhandler.so"

    fun runtimeDir(context: Context): File = File(context.filesDir, "runtime")
    fun binFile(context: Context): File = File(runtimeDir(context), "bin/opencode")
    fun soFile(context: Context): File =
        File(runtimeDir(context), "lib/opencode/libopencode-crhandler.so")
    fun versionFile(context: Context): File = File(runtimeDir(context), "VERSION")

    fun ocHome(context: Context): File = File(context.filesDir, "oc-home")
    fun ocTmp(context: Context): File = File(context.cacheDir, "oc-tmp")

    /** 默认工作目录：App 私有外部目录，无需存储权限。 */
    fun defaultWorkDir(context: Context): File =
        File(context.getExternalFilesDir(null), "projects/demo")

    fun configFile(context: Context): File =
        File(ocHome(context), ".config/opencode/opencode.json")

    fun isInstalled(context: Context): Boolean {
        val bin = binFile(context)
        return bin.exists() && bin.canExecute() &&
            versionFile(context).takeIf { it.exists() }?.readText()?.trim() == RUNTIME_VERSION
    }

    data class DownloadProgress(val doneBytes: Long, val totalBytes: Long)

    /** 下载 + SHA256 校验 + 解压 + chmod，回调进度（0..1，-1 表示不确定）。 */
    suspend fun install(
        context: Context,
        onProgress: (fraction: Float, stage: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dir = runtimeDir(context)
        val tmpPkg = File(context.cacheDir, "oc-runtime.pkg.tar.xz")
        try {
            // 1. 下载
            onProgress(0f, "正在下载运行时（约 65MB）…")
            download(RUNTIME_URL, tmpPkg) { done, total ->
                val f = if (total > 0) done.toFloat() / total * 0.7f else -1f
                onProgress(f, "正在下载运行时 ${done / 1024 / 1024}MB…")
            }
            // 2. 校验
            onProgress(0.72f, "正在校验完整性…")
            val actual = sha256(tmpPkg)
            if (!actual.equals(RUNTIME_SHA256, ignoreCase = true)) {
                throw RuntimeException("校验失败：文件可能损坏，请重试")
            }
            // 3. 解压（只提取需要的两个文件）
            onProgress(0.78f, "正在解压…")
            extractNeeded(tmpPkg, dir)
            // 4. 权限
            val bin = binFile(context)
            bin.setExecutable(true, false)
            bin.setReadable(true, false)
            versionFile(context).writeText(RUNTIME_VERSION)
            onProgress(1f, "运行时就绪")
        } finally {
            tmpPkg.delete()
        }
    }

    private fun download(url: String, out: File, onProgress: (Long, Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OpenCodePocket/1.0")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw RuntimeException("下载失败：HTTP ${conn.responseCode}")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 512 * 1024) {
                        lastReport = done
                        onProgress(done, total)
                    }
                }
                onProgress(done, total)
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractNeeded(pkg: File, dir: File) {
        val binOut = File(dir, "bin/opencode")
        val soOut = File(dir, "lib/opencode/libopencode-crhandler.so")
        binOut.parentFile?.mkdirs()
        soOut.parentFile?.mkdirs()
        pkg.inputStream().use { fis ->
            BufferedInputStream(fis).use { bis ->
                XZCompressorInputStream(bis).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        var entry = tar.nextEntry
                        var found = 0
                        while (entry != null && found < 2) {
                            when (entry.name.trimStart('.', '/')) {
                                BIN_REL -> {
                                    FileOutputStream(binOut).use { out -> tar.copyTo(out) }
                                    found++
                                }
                                SO_REL -> {
                                    FileOutputStream(soOut).use { out -> tar.copyTo(out) }
                                    found++
                                }
                            }
                            entry = tar.nextEntry
                        }
                        if (found < 2) throw RuntimeException("运行时包结构异常，缺少文件")
                    }
                }
            }
        }
    }

    /** 生成随机 serve 密码。 */
    fun newPassword(): String = UUID.randomUUID().toString().replace("-", "") +
        UUID.randomUUID().toString().replace("-", "").take(8)

    /**
     * 启动 serve，返回 Process。调用方负责持有并在不需要时 destroy。
     * cwd = 工作目录；HOME/TMPDIR 重定向到私有目录；密码走环境变量。
     */
    fun startServe(
        context: Context,
        workDir: File,
        password: String,
        port: Int = 4096
    ): Process {
        val bin = binFile(context)
        require(bin.canExecute()) { "运行时未安装" }
        workDir.mkdirs()
        val home = ocHome(context).apply { mkdirs() }
        val tmp = ocTmp(context).apply { mkdirs() }
        val pb = ProcessBuilder(
            bin.absolutePath, "serve",
            "--hostname", "127.0.0.1",
            "--port", port.toString()
        )
        pb.directory(workDir)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["TMPDIR"] = tmp.absolutePath
        pb.environment()["OPENCODE_SERVER_PASSWORD"] = password
        pb.environment()["OPENCODE_SERVER_USERNAME"] = "opencode"
        // RUNPATH 已含 $ORIGIN/../lib/opencode，LD_LIBRARY_PATH 属于双保险
        pb.environment()["LD_LIBRARY_PATH"] =
            File(runtimeDir(context), "lib/opencode").absolutePath
        pb.redirectErrorStream(true)
        return pb.start()
    }

    /** 写入首次配置：OpenRouter key + 默认模型。 */
    fun writeConfig(context: Context, openRouterKey: String) {
        val cfg = JSONObject()
            .put("model", "openrouter/deepseek/deepseek-chat")
            .put("small_model", "openrouter/deepseek/deepseek-chat")
            .put("provider", JSONObject().put("openrouter", JSONObject()
                .put("npm", "@ai-sdk/openai-compatible")
                .put("name", "OpenRouter")
                .put("options", JSONObject()
                    .put("apiKey", openRouterKey)
                    .put("baseURL", "https://openrouter.ai/api/v1"))
                .put("models", JSONObject()
                    .put("deepseek/deepseek-chat", JSONObject()
                        .put("name", "DeepSeek Chat (OpenRouter)")))))
        val f = configFile(context)
        f.parentFile?.mkdirs()
        f.writeText(cfg.toString(2))
    }

    fun hasConfig(context: Context): Boolean =
        configFile(context).takeIf { it.exists() }?.readText()?.contains("apiKey") == true
}
