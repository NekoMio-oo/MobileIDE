@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.mobileide

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

// =====================================================================
//  颜色
// =====================================================================
private val BgColor = Color(0xFF1E1F22)
private val PanelColor = Color(0xFF2B2D30)
private val ConsoleBg = Color(0xFF0D1117)
private val ConsoleFg = Color(0xFFC9D1D9)
private val AccentGreen = Color(0xFF6BCB77)
private val UbuntuOrange = Color(0xFFE95420)

private val IdeColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = Color.Black,
    secondary = UbuntuOrange,
    background = BgColor,
    surface = BgColor,
    surfaceVariant = PanelColor,
    onSurface = Color(0xFFDFE1E5),
    onBackground = Color(0xFFDFE1E5)
)

// =====================================================================
//  Activity
// =====================================================================
class MainActivity : ComponentActivity() {
    private val vm: IdeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = IdeColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
                    IdeApp(vm)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        vm.saveAll()
    }
}

// =====================================================================
//  多语言运行模板 / 新文件模板 / 语言环境安装包
// =====================================================================
val RUN_TEMPLATES: Map<String, String> = mapOf(
    "c" to "gcc \"\$file\" -O2 -lm -o /tmp/a.out && /tmp/a.out",
    "cpp" to "g++ \"\$file\" -O2 -std=c++20 -o /tmp/a.out && /tmp/a.out",
    "cc" to "g++ \"\$file\" -O2 -std=c++20 -o /tmp/a.out && /tmp/a.out",
    "py" to "python3 -u \"\$file\"",
    "js" to "node \"\$file\"",
    "go" to "go run \"\$file\"",
    "rs" to "rustc \"\$file\" -o /tmp/a.out && /tmp/a.out",
    "java" to "java \"\$file\"",
    "sh" to "bash \"\$file\""
)

val LANG_PACKS: List<Pair<String, String>> = listOf(
    "全部语言（推荐）" to "apt update && apt install -y build-essential python3 nodejs golang rustc default-jdk",
    "C / C++" to "apt update && apt install -y build-essential",
    "Python 3" to "apt update && apt install -y python3",
    "Node.js" to "apt update && apt install -y nodejs",
    "Go" to "apt update && apt install -y golang",
    "Rust" to "apt update && apt install -y rustc",
    "Java (JDK)" to "apt update && apt install -y default-jdk",
    "常用工具" to "apt update && apt install -y ca-certificates curl wget git nano vim less"
)

val NEW_FILE_EXTS = listOf(".c", ".cpp", ".py", ".js", ".go", ".rs", ".java", ".sh", ".txt")

fun templateFor(ext: String): String = when (ext.lowercase()) {
    "c" -> """#include <stdio.h>

int main(void) {
    printf("Hello from C on PRoot Ubuntu 24.04!\n");
    return 0;
}
"""
    "cpp", "cc" -> """#include <iostream>
#include <vector>

int main() {
    std::vector<int> v{1, 2, 3, 4, 5};
    int sum = 0;
    for (int x : v) sum += x;
    std::cout << "Hello C++20, sum = " << sum << std::endl;
    return 0;
}
"""
    "py" -> """name = input("请输入你的名字: ")
print(f"Hello, {name}! 来自 PRoot Ubuntu 24.04 的 Python")
"""
    "js" -> """console.log("Hello from Node.js on PRoot Ubuntu!");
"""
    "go" -> """package main

import "fmt"

func main() {
	fmt.Println("Hello from Go on PRoot Ubuntu!")
}
"""
    "rs" -> """fn main() {
    println!("Hello from Rust on PRoot Ubuntu!");
}
"""
    "java" -> """public class Main {
    public static void main(String[] args) {
        System.out.println("Hello from Java on PRoot Ubuntu!");
    }
}
"""
    "sh" -> """#!/bin/bash
echo "Hello from Bash"
uname -a
""" 
    else -> ""
}

private val SAMPLE_MAIN_C = """#include <stdio.h>
#include <math.h>

int main(void) {
    int n = 0;
    printf("求 [2, N] 内的素数，请在 Stdin 输入 N（例如 50）：\n");
    if (scanf("%d", &n) != 1) n = 50;
    int count = 0;
    for (int i = 2; i <= n; i++) {
        int ok = 1;
        for (int j = 2; j <= (int)sqrt((double)i); j++) {
            if (i % j == 0) { ok = 0; break; }
        }
        if (ok) { printf("%d ", i); count++; }
    }
    printf("\n共 %d 个素数\n", count);
    return 0;
}
"""

private val SAMPLE_APP_PY = """import platform
import sys

print("Python", sys.version.split()[0], "on", platform.machine())
name = input("请输入你的名字: ")
print(f"Hello, {name}! 这里是 PRoot Ubuntu 24.04")
"""

private val SAMPLE_HELLO_SH = """#!/bin/bash
echo "Hello from PRoot Ubuntu!"
uname -a
grep PRETTY_NAME /etc/os-release
echo "当前目录: ${'$'}(pwd)"
ls -la
"""

fun languageFor(ext: String): Language = when (ext.lowercase()) {
    "c", "h", "cpp", "cc", "hpp", "java", "js", "ts", "go", "rs", "kt", "cs" -> JavaLanguage()
    else -> EmptyLanguage()
}

fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

private val ANSI_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Za-z0-9]|\u001B[=>]")

fun cleanOutput(s: String): String =
    ANSI_REGEX.replace(s, "").replace("\r\n", "\n").replace('\r', '\n')

// =====================================================================
//  文件工具（不跟随符号链接的安全删除）
// =====================================================================
fun lexists(f: File): Boolean = try {
    Os.lstat(f.path); true
} catch (e: ErrnoException) {
    false
}

fun deleteTree(f: File) {
    val st = try {
        Os.lstat(f.path)
    } catch (e: ErrnoException) {
        return
    }
    if (OsConstants.S_ISDIR(st.st_mode)) {
        try { Os.chmod(f.path, 0x1C0) } catch (_: Exception) { } // 0700
        f.list()?.forEach { deleteTree(File(f, it)) }
    }
    if (!f.delete()) {
        try { f.parentFile?.let { Os.chmod(it.path, 0x1C0) } } catch (_: Exception) { }
        f.delete()
    }
}

fun writeReplacing(f: File, text: String) {
    if (lexists(f)) deleteTree(f)
    f.parentFile?.mkdirs()
    f.writeText(text)
}

// =====================================================================
//  控制台缓冲（线程安全，主线程刷新 Compose 状态）
// =====================================================================
class ConsoleBuffer(private val maxChars: Int = 150_000) {
    var text by mutableStateOf("")
        private set
    private val sb = StringBuilder()
    private val main = Handler(Looper.getMainLooper())

    fun append(s: String) {
        if (s.isEmpty()) return
        main.post {
            sb.append(s)
            if (sb.length > maxChars) sb.delete(0, sb.length - maxChars)
            text = sb.toString()
        }
    }

    fun clear() {
        main.post {
            sb.setLength(0)
            text = ""
        }
    }
}

// =====================================================================
//  ProotRunner：在 PRoot Ubuntu 容器中执行一切命令
// =====================================================================
class ProotRunner(context: Context) {
    val filesDir: File = context.filesDir
    val rootfsDir = File(filesDir, "rootfs")
    val tmpDir = File(filesDir, "proot-tmp")
    val workspaceDir = File(filesDir, "workspace")
    val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    val prootBinary = File(nativeLibDir, "libproot.so")
    val loader = File(nativeLibDir, "libproot-loader.so")
    val loader32 = File(nativeLibDir, "libproot-loader32.so")
    val readyMarker = File(rootfsDir, ".mobile-ide-ready")

    fun ensureDirs() {
        tmpDir.mkdirs()
        workspaceDir.mkdirs()
    }

    fun isRootfsReady(): Boolean =
        readyMarker.exists() && File(rootfsDir, "usr/bin/bash").exists()

    fun buildCommand(command: String): List<String> = listOf(
        prootBinary.absolutePath,
        "--kill-on-exit",
        "--link2symlink",
        "-0",
        "-r", rootfsDir.absolutePath,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-b", "/proc/self/fd:/dev/fd",
        "-b", "${workspaceDir.absolutePath}:/root/workspace",
        "-w", "/root/workspace",
        "/bin/bash", "-lc", command
    )

    @Throws(IOException::class)
    fun start(command: String): Process {
        ensureDirs()
        if (!prootBinary.exists()) {
            throw IOException("未找到 ${prootBinary.absolutePath}。请通过 GitHub Actions 构建 APK（构建脚本会自动下载 PRoot 到 jniLibs）。")
        }
        val pb = ProcessBuilder(buildCommand(command))
        pb.directory(filesDir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.clear()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
        if (loader32.exists()) env["PROOT_LOADER_32"] = loader32.absolutePath
        env["PROOT_NO_SECCOMP"] = "1"
        env["HOME"] = "/root"
        env["TERM"] = "xterm-256color"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["LANG"] = "C.UTF-8"
        env["TMPDIR"] = "/tmp"
        env["DEBIAN_FRONTEND"] = "noninteractive"
        return pb.start()
    }
}

/** 后台线程把进程输出实时泵入控制台，结束后回调退出码 */
fun pumpProcess(process: Process, out: ConsoleBuffer, onExit: (Int) -> Unit) {
    thread(isDaemon = true, name = "proot-pump") {
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    out.append(cleanOutput(String(buf, 0, n)))
                }
            }
        } catch (_: Exception) {
        }
        val code = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            -1
        }
        onExit(code)
    }
}

fun killProcess(p: Process) {
    try { p.destroy() } catch (_: Exception) { }
    thread(isDaemon = true) {
        try {
            if (!p.waitFor(800, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        } catch (_: Exception) {
        }
    }
}

// =====================================================================
//  Ubuntu Rootfs 下载 / 解压 / 初始化引擎
// =====================================================================
class ByteCountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile
    var count: Long = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(n)
        if (s > 0) count += s
        return s
    }
}

object RootfsInstaller {
    const val OFFICIAL_DIR = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
    const val TUNA_DIR = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/"
    const val DEFAULT_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.1-base-arm64.tar.gz"
    private val NAME_REGEX = Regex("ubuntu-base-24\\.04(\\.\\d+)?-base-arm64\\.tar\\.gz")
    private val PATCH_REGEX = Regex("24\\.04\\.(\\d+)")

    fun openConnection(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MobileIDE/1.0 (Android)")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("重定向缺少 Location: $current")
                current = URL(URL(current), loc).toString()
            } else {
                return c
            }
        }
        throw IOException("重定向次数过多: $url")
    }

    private fun httpGetText(url: String): String {
        val c = openConnection(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** 自动从目录索引中找到最新的 24.04.x arm64 base 包，并附带固定回退地址 */
    fun resolveUrls(useTuna: Boolean, log: (String) -> Unit): List<String> {
        val dirs = if (useTuna) listOf(TUNA_DIR, OFFICIAL_DIR) else listOf(OFFICIAL_DIR, TUNA_DIR)
        val result = mutableListOf<String>()
        for (dir in dirs) {
            try {
                val html = httpGetText(dir)
                val latest = NAME_REGEX.findAll(html).map { it.value }.distinct()
                    .sortedBy { PATCH_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                    .lastOrNull()
                if (latest != null) result += dir + latest
            } catch (e: Exception) {
                log("索引获取失败: $dir (${e.message})\n")
            }
        }
        result += DEFAULT_URL
        result += OFFICIAL_DIR + "ubuntu-base-24.04.3-base-arm64.tar.gz"
        return result.distinct()
    }

    /** 流式解压 tar.gz 到 rootfs，保留目录 / 文件权限 / 符号链接 / 硬链接 */
    fun extract(
        input: InputStream,
        totalBytes: Long,
        rootfs: File,
        onProgress: (Float, Long) -> Unit,
        log: (String) -> Unit
    ) {
        val counting = ByteCountingInputStream(BufferedInputStream(input, 1 shl 16))
        var lastReport = 0L
        var entries = 0
        TarArchiveInputStream(GZIPInputStream(counting, 1 shl 16)).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").trim('/')
                if (name.isEmpty() || name.split('/').any { it == ".." }) continue
                val out = File(rootfs, name)
                try {
                    when {
                        entry.isDirectory -> {
                            out.mkdirs()
                            Os.chmod(out.path, (entry.mode and 0xFFF) or 0x1C0)
                        }
                        entry.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            if (lexists(out)) deleteTree(out)
                            Os.symlink(entry.linkName, out.path)
                        }
                        entry.isLink -> {
                            out.parentFile?.mkdirs()
                            if (lexists(out)) deleteTree(out)
                            val target = File(rootfs, entry.linkName.removePrefix("./").trim('/'))
                            try {
                                Os.link(target.path, out.path)
                            } catch (e: Exception) {
                                target.copyTo(out, overwrite = true)
                                Os.chmod(out.path, (entry.mode and 0xFFF) or 0x180)
                            }
                        }
                        entry.isFile -> {
                            out.parentFile?.mkdirs()
                            if (lexists(out)) deleteTree(out)
                            FileOutputStream(out).use { fos -> tar.copyTo(fos, 1 shl 16) }
                            Os.chmod(out.path, (entry.mode and 0xFFF) or 0x180)
                        }
                        else -> {
                            // 字符设备 / FIFO 等在 PRoot 中由 -b /dev 提供，跳过
                        }
                    }
                } catch (e: Exception) {
                    log("跳过 $name: ${e.message}\n")
                }
                entries++
                val c = counting.count
                if (c - lastReport > 512 * 1024) {
                    lastReport = c
                    onProgress(if (totalBytes > 0) (c.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f, c)
                }
            }
        }
        log("解压完成，共 $entries 个条目\n")
    }

    /** 写入 resolv.conf / hosts / apt 配置 / Android 组，创建 tmp 目录 */
    fun configure(rootfs: File, prootTmp: File, useTuna: Boolean) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        writeReplacing(File(etc, "resolv.conf"), "nameserver 8.8.8.8\nnameserver 223.5.5.5\n")
        writeReplacing(
            File(etc, "hosts"),
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        )

        // apt 在 PRoot 中无法切换到 _apt 用户
        File(etc, "apt/apt.conf.d").mkdirs()
        File(etc, "apt/apt.conf.d/99-mobile-ide").writeText(
            "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDpkg::Use-Pty \"0\";\n"
        )

        // 清华镜像（ubuntu-base 默认无 CA 证书，因此使用 http）
        if (useTuna) {
            listOf(
                File(etc, "apt/sources.list.d/ubuntu.sources"),
                File(etc, "apt/sources.list")
            ).filter { it.isFile }.forEach { f ->
                f.writeText(
                    f.readText().replace(
                        "http://ports.ubuntu.com/ubuntu-ports",
                        "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
                    )
                )
            }
        }

        // 补齐 Android 进程所在的组，避免 "cannot find name for group ID"
        val groupFile = File(etc, "group")
        val existing = if (groupFile.exists()) groupFile.readLines() else emptyList()
        val gids = existing.mapNotNull { it.split(':').getOrNull(2) }.toMutableSet()
        val appId = Os.getuid() % 100000
        val extra = listOf(
            "aid_inet" to 3003,
            "aid_everybody" to 9997,
            "aid_app" to Os.getgid(),
            "aid_cache" to (appId - 10000 + 20000),
            "aid_all" to (appId - 10000 + 50000)
        )
        val sb = StringBuilder()
        for ((n, g) in extra) {
            if (g > 0 && gids.add(g.toString())) sb.append("$n:x:$g:\n")
        }
        if (sb.isNotEmpty()) groupFile.appendText(sb.toString())

        val profileD = File(etc, "profile.d").apply { mkdirs() }
        File(profileD, "mobile-ide.sh").writeText(
            "export LANG=C.UTF-8\nexport DEBIAN_FRONTEND=noninteractive\nexport PATH=\"\$PATH:/usr/local/go/bin:/root/.cargo/bin\"\n"
        )

        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        Os.chmod(tmp.path, 1023) // 01777
        File(rootfs, "root/workspace").mkdirs()
        prootTmp.mkdirs()
        File(rootfs, ".mobile-ide-ready").writeText("ok\n")
    }
}

// =====================================================================
//  数据模型
// =====================================================================
class EditorTab(val id: Long, val file: File) {
    var content: String = runCatching {
        if (file.length() > 4L * 1024 * 1024) "// 文件过大（>4MB），未加载" else file.readText()
    }.getOrDefault("")
}

data class FileNode(val file: File, val depth: Int, val isDir: Boolean)

// =====================================================================
//  ViewModel
// =====================================================================
class IdeViewModel(app: Application) : AndroidViewModel(app) {
    val runner = ProotRunner(app)
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) { mainHandler.post(block) }
    fun toast(msg: String) = ui { Toast.makeText(getApplication<Application>(), msg, Toast.LENGTH_SHORT).show() }

    // ---------- 环境 ----------
    var rootfsReady by mutableStateOf(false)
    var showEnvPanel by mutableStateOf(false)
    var bootstrapBusy by mutableStateOf(false)
    var bootstrapProgress by mutableStateOf(0f)
    var bootstrapStatus by mutableStateOf("")
    var useTuna by mutableStateOf(true)
    val envLog = ConsoleBuffer()
    private var envProcess: Process? = null
    var envRunning by mutableStateOf(false)

    // ---------- 文件 / 标签 ----------
    val fileNodes = mutableStateListOf<FileNode>()
    var targetDir by mutableStateOf(runner.workspaceDir)
    val tabs = mutableStateListOf<EditorTab>()
    var selectedTab by mutableStateOf(0)
    var editor: CodeEditor? = null
    private var tabSeq = 1L

    // ---------- 终端 ----------
    var terminalVisible by mutableStateOf(false)
    var terminalTab by mutableStateOf(0)
    val runOutput = ConsoleBuffer()
    var running by mutableStateOf(false)
    var elapsedMs by mutableStateOf(0L)
    var lastExit by mutableStateOf<String?>(null)
    private var runProcess: Process? = null

    val shellOutput = ConsoleBuffer()
    private var shellProcess: Process? = null
    var shellAlive by mutableStateOf(false)

    init {
        runner.ensureDirs()
        seedWorkspace()
        refreshFiles()
        rootfsReady = runner.isRootfsReady()
        if (!rootfsReady) showEnvPanel = true
        File(runner.workspaceDir, "main.c").takeIf { it.exists() }?.let { openFile(it) }
    }

    private fun seedWorkspace() {
        val ws = runner.workspaceDir
        if (ws.list().isNullOrEmpty()) {
            File(ws, "main.c").writeText(SAMPLE_MAIN_C)
            File(ws, "app.py").writeText(SAMPLE_APP_PY)
            File(ws, "hello.sh").writeText(SAMPLE_HELLO_SH)
        }
    }

    // ================= 文件管理 =================
    fun refreshFiles() {
        val list = mutableListOf<FileNode>()
        fun walk(dir: File, depth: Int) {
            if (list.size > 3000) return
            val children = dir.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            for (c in children) {
                if (c.name.startsWith(".")) continue
                list += FileNode(c, depth, c.isDirectory)
                if (c.isDirectory && depth < 6) walk(c, depth + 1)
            }
        }
        walk(runner.workspaceDir, 0)
        fileNodes.clear()
        fileNodes.addAll(list)
        if (!targetDir.exists()) targetDir = runner.workspaceDir
    }

    fun relPath(f: File): String =
        f.relativeTo(runner.workspaceDir).invariantSeparatorsPath.ifEmpty { "." }

    private fun validName(name: String): Boolean =
        name.isNotBlank() && !name.contains("..") && !name.startsWith("/")

    fun createFile(rawName: String) {
        val name = rawName.trim()
        if (!validName(name)) { toast("文件名无效"); return }
        val f = File(targetDir, name)
        if (f.exists()) { toast("文件已存在"); openFile(f); return }
        f.parentFile?.mkdirs()
        f.writeText(templateFor(f.extension))
        refreshFiles()
        openFile(f)
    }

    fun createFolder(rawName: String) {
        val name = rawName.trim()
        if (!validName(name)) { toast("文件夹名无效"); return }
        val d = File(targetDir, name)
        if (!d.mkdirs()) toast("创建失败或已存在")
        targetDir = d
        refreshFiles()
    }

    fun deleteFile(f: File) {
        syncEditorToTab()
        val prefix = f.absolutePath
        val toRemove = tabs.filter {
            it.file.absolutePath == prefix || it.file.absolutePath.startsWith("$prefix/")
        }
        val current = currentTab()
        tabs.removeAll(toRemove)
        selectedTab = if (current != null && tabs.contains(current)) tabs.indexOf(current)
        else selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        deleteTree(f)
        if (targetDir.absolutePath.startsWith(prefix)) targetDir = runner.workspaceDir
        refreshFiles()
    }

    // ================= 标签页 =================
    fun currentTab(): EditorTab? = tabs.getOrNull(selectedTab)

    fun syncEditorToTab() {
        val ed = editor ?: return
        val t = currentTab() ?: return
        if (ed.tag == t.id) t.content = ed.text.toString()
    }

    fun openFile(f: File) {
        if (f.isDirectory) { targetDir = f; return }
        val idx = tabs.indexOfFirst { it.file.absolutePath == f.absolutePath }
        if (idx >= 0) { selectTab(idx); return }
        syncEditorToTab()
        tabs.add(EditorTab(tabSeq++, f))
        selectedTab = tabs.lastIndex
    }

    fun selectTab(i: Int) {
        if (i == selectedTab) return
        syncEditorToTab()
        currentTab()?.let { t -> runCatching { t.file.writeText(t.content) } }
        selectedTab = i
    }

    fun closeTab(i: Int) {
        syncEditorToTab()
        val t = tabs.getOrNull(i) ?: return
        runCatching { t.file.writeText(t.content) }
        val current = currentTab()
        tabs.removeAt(i)
        selectedTab = if (current != null && current !== t && tabs.contains(current)) tabs.indexOf(current)
        else i.coerceAtMost(tabs.size - 1).coerceAtLeast(0)
    }

    fun saveCurrent(showToast: Boolean = false) {
        syncEditorToTab()
        val t = currentTab() ?: return
        try {
            t.file.writeText(t.content)
            if (showToast) toast("已保存 ${t.file.name}")
        } catch (e: Exception) {
            toast("保存失败: ${e.message}")
        }
    }

    fun saveAll() {
        syncEditorToTab()
        tabs.forEach { t -> runCatching { t.file.writeText(t.content) } }
    }

    // ================= 快捷符号栏 =================
    fun onSymbol(s: String) {
        val ed = editor ?: return
        when (s) {
            "Tab" -> ed.insertText("    ", 4)
            "←" -> sendKey(ed, KeyEvent.KEYCODE_DPAD_LEFT)
            "→" -> sendKey(ed, KeyEvent.KEYCODE_DPAD_RIGHT)
            "↑" -> sendKey(ed, KeyEvent.KEYCODE_DPAD_UP)
            "↓" -> sendKey(ed, KeyEvent.KEYCODE_DPAD_DOWN)
            else -> ed.insertText(s, s.length)
        }
    }

    private fun sendKey(ed: CodeEditor, code: Int) {
        val now = SystemClock.uptimeMillis()
        ed.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        ed.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    // ================= 多语言运行 =================
    fun runCurrent() {
        val tab = currentTab() ?: run { toast("请先打开一个文件"); return }
        saveCurrent()
        if (!rootfsReady) {
            showEnvPanel = true
            toast("请先初始化 Ubuntu 环境")
            return
        }
        val ext = tab.file.extension.lowercase()
        val template = RUN_TEMPLATES[ext] ?: run { toast("暂不支持运行 .$ext 文件"); return }
        val guestPath = "/root/workspace/" + relPath(tab.file)
        val guestDir = guestPath.substringBeforeLast('/')
        val cmd = "cd ${shQuote(guestDir)} && file=${shQuote(guestPath)} && $template"

        stopRun(silent = true)
        runOutput.clear()
        terminalVisible = true
        terminalTab = 0
        lastExit = null
        runOutput.append("▶ ${template.replace("\$file", relPath(tab.file))}\n\n")
        try {
            val p = runner.start(cmd)
            runProcess = p
            running = true
            elapsedMs = 0L
            val start = SystemClock.elapsedRealtime()
            viewModelScope.launch {
                while (running && runProcess === p) {
                    elapsedMs = SystemClock.elapsedRealtime() - start
                    delay(100)
                }
            }
            pumpProcess(p, runOutput) { code ->
                val cost = SystemClock.elapsedRealtime() - start
                ui {
                    if (runProcess === p) {
                        runOutput.append("\n[进程已退出，返回码 $code，耗时 $cost ms]\n")
                        lastExit = "退出码 $code · 耗时 $cost ms"
                        elapsedMs = cost
                        running = false
                        runProcess = null
                    }
                }
            }
        } catch (e: Exception) {
            runOutput.append("启动失败: ${e.message}\n")
            running = false
        }
    }

    fun stopRun(silent: Boolean = false) {
        val p = runProcess ?: return
        runProcess = null
        running = false
        killProcess(p)
        if (!silent) {
            runOutput.append("\n[已强制终止进程]\n")
            lastExit = "已强制终止 · ${elapsedMs} ms"
        }
    }

    fun sendStdin(text: String) {
        val p = runProcess ?: run { toast("当前没有运行中的程序"); return }
        runOutput.append(text + "\n")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                p.outputStream.write((text + "\n").toByteArray(Charsets.UTF_8))
                p.outputStream.flush()
            } catch (e: Exception) {
                runOutput.append("[stdin 写入失败: ${e.message}]\n")
            }
        }
    }

    fun sendEof() {
        val p = runProcess ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { p.outputStream.close() } catch (_: Exception) { }
        }
        runOutput.append("[已发送 EOF]\n")
    }

    // ================= 交互式 Ubuntu Shell =================
    private fun ensureShell(): Process? {
        shellProcess?.let { if (it.isAlive) return it }
        if (!rootfsReady) {
            showEnvPanel = true
            toast("请先初始化 Ubuntu 环境")
            return null
        }
        return try {
            val p = runner.start("exec /bin/bash -s")
            shellProcess = p
            shellAlive = true
            shellOutput.append("[PRoot Ubuntu 24.04 Shell 已启动，工作目录 /root/workspace]\n")
            pumpProcess(p, shellOutput) { code ->
                ui {
                    if (shellProcess === p) {
                        shellOutput.append("\n[Shell 已退出，返回码 $code]\n")
                        shellProcess = null
                        shellAlive = false
                    }
                }
            }
            p
        } catch (e: Exception) {
            shellOutput.append("Shell 启动失败: ${e.message}\n")
            null
        }
    }

    fun execShell(cmd: String) {
        if (cmd.isBlank()) return
        val p = ensureShell() ?: return
        shellOutput.append("root@ubuntu:~# $cmd\n")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                p.outputStream.write((cmd + "\n").toByteArray(Charsets.UTF_8))
                p.outputStream.flush()
            } catch (e: Exception) {
                shellOutput.append("[写入失败: ${e.message}]\n")
            }
        }
    }

    fun restartShell() {
        shellProcess?.let { killProcess(it) }
        shellProcess = null
        shellAlive = false
        shellOutput.append("\n[正在重启 Shell…]\n")
        ensureShell()
    }

    // ================= 语言环境一键安装 =================
    fun runEnvCommand(title: String, cmd: String) {
        if (!rootfsReady) { toast("请先初始化 Ubuntu 环境"); return }
        if (envRunning) { toast("已有安装任务在运行"); return }
        envLog.append("\n===== $title =====\n$ $cmd\n")
        try {
            val p = runner.start(cmd)
            envProcess = p
            envRunning = true
            pumpProcess(p, envLog) { code ->
                ui {
                    if (envProcess === p) {
                        envLog.append("\n[$title 结束，返回码 $code]\n")
                        envProcess = null
                        envRunning = false
                        if (code == 0) toast("$title 安装完成")
                    }
                }
            }
        } catch (e: Exception) {
            envLog.append("启动失败: ${e.message}\n")
        }
    }

    fun stopEnv() {
        val p = envProcess ?: return
        envProcess = null
        envRunning = false
        killProcess(p)
        envLog.append("\n[已终止安装任务]\n")
    }

    // ================= Rootfs 下载 / 导入 =================
    private fun stopAllProcesses() {
        runProcess?.let { killProcess(it) }; runProcess = null
        shellProcess?.let { killProcess(it) }; shellProcess = null
        envProcess?.let { killProcess(it) }; envProcess = null
        ui { running = false; shellAlive = false; envRunning = false }
    }

    private fun installFromStream(input: InputStream, total: Long) {
        ui { rootfsReady = false; bootstrapProgress = 0f }
        envLog.append("清理旧的 rootfs…\n")
        deleteTree(runner.rootfsDir)
        runner.rootfsDir.mkdirs()
        val totalText = if (total > 0) "%.1f MB".format(total / 1048576.0) else "未知大小"
        RootfsInstaller.extract(
            input, total, runner.rootfsDir,
            onProgress = { f, bytes ->
                ui {
                    bootstrapProgress = f
                    bootstrapStatus = "下载并解压中 %.1f MB / %s".format(bytes / 1048576.0, totalText)
                }
            },
            log = { envLog.append(it) }
        )
        envLog.append("写入 resolv.conf / hosts / apt 配置…\n")
        RootfsInstaller.configure(runner.rootfsDir, runner.tmpDir, useTuna)
        val ok = runner.isRootfsReady()
        ui {
            rootfsReady = ok
            bootstrapProgress = 1f
            bootstrapStatus = if (ok) "Ubuntu 24.04 环境已就绪" else "初始化未完成，请重试"
        }
        envLog.append(if (ok) "✅ Ubuntu 24.04 (arm64) 初始化完成！\n" else "❌ rootfs 校验失败\n")
    }

    fun downloadRootfs() {
        if (bootstrapBusy) return
        bootstrapBusy = true
        bootstrapProgress = 0f
        bootstrapStatus = "正在解析下载地址…"
        val tuna = useTuna
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stopAllProcesses()
                val urls = RootfsInstaller.resolveUrls(tuna) { envLog.append(it) }
                var ok = false
                for (url in urls) {
                    try {
                        envLog.append("下载: $url\n")
                        ui { bootstrapStatus = "连接 $url" }
                        val conn = RootfsInstaller.openConnection(url)
                        try {
                            if (conn.responseCode != 200) {
                                envLog.append("HTTP ${conn.responseCode}，尝试下一个地址\n")
                                continue
                            }
                            conn.inputStream.use { installFromStream(it, conn.contentLengthLong) }
                        } finally {
                            conn.disconnect()
                        }
                        ok = runner.isRootfsReady()
                        if (ok) break
                    } catch (e: Exception) {
                        envLog.append("失败: ${e.message}\n")
                    }
                }
                if (!ok) {
                    ui { bootstrapStatus = "所有下载地址均失败，可改用本地导入" }
                    toast("下载失败")
                } else {
                    toast("Ubuntu 环境初始化完成")
                }
            } finally {
                ui { bootstrapBusy = false }
            }
        }
    }

    fun importRootfs(uri: Uri) {
        if (bootstrapBusy) return
        bootstrapBusy = true
        bootstrapProgress = 0f
        bootstrapStatus = "正在导入本地 rootfs…"
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stopAllProcesses()
                val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                } ?: -1L
                envLog.append("从本地导入: $uri\n")
                val input = resolver.openInputStream(uri) ?: throw IOException("无法打开文件")
                input.use { installFromStream(it, size) }
                if (runner.isRootfsReady()) toast("导入完成") else toast("导入失败")
            } catch (e: Exception) {
                envLog.append("导入失败: ${e.message}\n（仅支持 .tar.gz 格式的 Ubuntu arm64 rootfs）\n")
                ui { bootstrapStatus = "导入失败: ${e.message}" }
            } finally {
                ui { bootstrapBusy = false }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveAll()
        runProcess?.let { killProcess(it) }
        shellProcess?.let { killProcess(it) }
        envProcess?.let { killProcess(it) }
    }
}

// =====================================================================
//  UI：主界面
// =====================================================================
@Composable
fun IdeApp(vm: IdeViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showNewDialog by remember { mutableStateOf(false) }
    var newIsFolder by remember { mutableStateOf(false) }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = Color(0xFF1B1C1F)
            ) {
                FileDrawer(
                    vm = vm,
                    onOpen = { f ->
                        vm.openFile(f)
                        scope.launch { drawerState.close() }
                    },
                    onNewFile = { newIsFolder = false; showNewDialog = true },
                    onNewFolder = { newIsFolder = true; showNewDialog = true }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgColor)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            TopBar(vm, onMenu = { vm.refreshFiles(); scope.launch { drawerState.open() } })
            if (vm.tabs.isNotEmpty()) EditorTabs(vm)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val tab = vm.tabs.getOrNull(vm.selectedTab)
                if (tab != null) {
                    SoraEditor(vm, tab, Modifier.fillMaxSize())
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("没有打开的文件", color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.refreshFiles(); scope.launch { drawerState.open() } }) {
                            Text("打开文件管理器")
                        }
                    }
                }
            }
            if (vm.terminalVisible) TerminalPanel(vm)
            QuickSymbolBar(onSymbol = { vm.onSymbol(it) })
        }
    }

    if (showNewDialog) {
        NewFileDialog(
            isFolder = newIsFolder,
            location = "/root/workspace/" + vm.relPath(vm.targetDir).let { if (it == ".") "" else it },
            onDismiss = { showNewDialog = false },
            onCreate = { name ->
                if (newIsFolder) vm.createFolder(name) else vm.createFile(name)
                showNewDialog = false
            }
        )
    }

    if (vm.showEnvPanel) EnvPanel(vm)
}

@Composable
fun TopBar(vm: IdeViewModel, onMenu: () -> Unit) {
    val tab = vm.tabs.getOrNull(vm.selectedTab)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelColor)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "文件") }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tab?.file?.name ?: "Mobile IDE",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                color = Color.White
            )
            Text(
                text = if (vm.rootfsReady) "Ubuntu 24.04 · PRoot 就绪" else "Ubuntu 未初始化",
                fontSize = 11.sp,
                color = if (vm.rootfsReady) AccentGreen else UbuntuOrange
            )
        }
        TextButton(onClick = { vm.saveCurrent(showToast = true) }) { Text("保存") }
        IconButton(onClick = { vm.showEnvPanel = true }) {
            Icon(Icons.Filled.Settings, contentDescription = "环境管理")
        }
        TextButton(onClick = { vm.terminalVisible = !vm.terminalVisible }) {
            Text(">_", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        if (vm.running) {
            Button(
                onClick = { vm.stopRun() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9534F)),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text("停止", color = Color.White) }
        } else {
            Button(
                onClick = { vm.runCurrent() },
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("运行")
            }
        }
    }
}

@Composable
fun EditorTabs(vm: IdeViewModel) {
    ScrollableTabRow(
        selectedTabIndex = vm.selectedTab.coerceIn(0, (vm.tabs.size - 1).coerceAtLeast(0)),
        edgePadding = 0.dp,
        containerColor = Color(0xFF232427),
        contentColor = Color.White
    ) {
        vm.tabs.forEachIndexed { i, t ->
            Tab(
                selected = i == vm.selectedTab,
                onClick = { vm.selectTab(i) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.file.name, maxLines = 1, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "✕",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { vm.closeTab(i) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            )
        }
    }
}

// =====================================================================
//  Sora Editor 封装
// =====================================================================
@Composable
fun SoraEditor(vm: IdeViewModel, tab: EditorTab, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CodeEditor(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setColorScheme(SchemeDarcula())
                setTypefaceText(Typeface.MONOSPACE)
                setTextSize(14f)
                setLineNumberEnabled(true)
                setScalable(true)
                setWordwrap(false)
                vm.editor = this
            }
        },
        onRelease = { ed ->
            if (vm.editor === ed) {
                vm.syncEditorToTab()
                vm.editor = null
            }
            ed.release()
        },
        update = { ed ->
            vm.editor = ed
            if (ed.tag != tab.id) {
                ed.tag = tab.id
                ed.setEditorLanguage(languageFor(tab.file.extension))
                ed.setText(tab.content)
            }
        }
    )
}

// =====================================================================
//  快捷符号栏（带震动反馈）
// =====================================================================
@Composable
fun QuickSymbolBar(onSymbol: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val symbols = listOf(
        "Tab", "{", "}", "(", ")", "[", "]", ";", "#", "*", "\"", "'",
        "=", "&", "|", "<", ">", "/", "\\", "←", "↓", "↑", "→"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF18191B))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        symbols.forEach { s ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .sizeIn(minWidth = 40.dp, minHeight = 38.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(PanelColor)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        onSymbol(s)
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(s, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            }
        }
    }
}

// =====================================================================
//  左侧抽屉文件管理器
// =====================================================================
@Composable
fun FileDrawer(
    vm: IdeViewModel,
    onOpen: (File) -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("工作区", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
        Text("映射到 Ubuntu: /root/workspace", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onNewFile) { Text("+ 文件") }
            TextButton(onClick = onNewFolder) { Text("+ 文件夹") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.refreshFiles() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
        val loc = vm.relPath(vm.targetDir)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "新建位置: /root/workspace/" + if (loc == ".") "" else loc,
                fontSize = 11.sp,
                color = AccentGreen,
                modifier = Modifier.weight(1f)
            )
            if (loc != ".") {
                TextButton(onClick = { vm.targetDir = vm.runner.workspaceDir }) {
                    Text("根目录", fontSize = 11.sp)
                }
            }
        }
        HorizontalDivider(color = PanelColor)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(vm.fileNodes, key = { it.file.absolutePath }) { node ->
                val selectedDir = node.isDir && node.file.absolutePath == vm.targetDir.absolutePath
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selectedDir) Color(0xFF2F3A2F) else Color.Transparent)
                        .clickable {
                            if (node.isDir) vm.targetDir = node.file else onOpen(node.file)
                        }
                        .padding(start = (6 + node.depth * 14).dp, end = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = (if (node.isDir) "📁 " else "📄 ") + node.file.name,
                        color = if (node.isDir) Color(0xFFE5C07B) else Color(0xFFDFE1E5),
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                    )
                    IconButton(onClick = { pendingDelete = node.file }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除确认") },
            text = { Text("确定删除 ${vm.relPath(f)} 吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteFile(f); pendingDelete = null }) {
                    Text("删除", color = Color(0xFFD9534F))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
fun NewFileDialog(
    isFolder: Boolean,
    location: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf(if (isFolder) "src" else "main") }
    var ext by remember { mutableStateOf(".c") }
    fun finalName(): String = if (isFolder || name.contains('.')) name else name + ext

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isFolder) "新建文件夹" else "新建代码文件") },
        text = {
            Column {
                Text("位置: $location", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(if (isFolder) "文件夹名" else "文件名（可不带后缀）") }
                )
                if (!isFolder) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        NEW_FILE_EXTS.forEach { e ->
                            FilterChip(
                                selected = ext == e,
                                onClick = { ext = e },
                                label = { Text(e) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("将创建: ${finalName()}", fontSize = 12.sp, color = AccentGreen)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(finalName()) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// =====================================================================
//  底部终端抽屉：运行输出 + 交互式 Ubuntu Shell
// =====================================================================
@Composable
fun TerminalPanel(vm: IdeViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (expanded) 460.dp else 270.dp)
            .background(Color(0xFF16171A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(PanelColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelTabButton("代码运行输出", vm.terminalTab == 0) { vm.terminalTab = 0 }
            PanelTabButton("Ubuntu 终端", vm.terminalTab == 1) { vm.terminalTab = 1 }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "▼" else "▲") }
            IconButton(onClick = { vm.terminalVisible = false }) {
                Icon(Icons.Filled.Close, contentDescription = "收起")
            }
        }
        if (vm.terminalTab == 0) RunOutputTab(vm) else ShellTab(vm)
    }
}

@Composable
fun PanelTabButton(title: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            fontSize = 13.sp,
            color = if (selected) Color.White else Color.Gray,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .height(2.dp)
                .width(48.dp)
                .background(if (selected) AccentGreen else Color.Transparent)
        )
    }
}

@Composable
fun RunOutputTab(vm: IdeViewModel) {
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val status = if (vm.running) "● 运行中 %.1f s".format(vm.elapsedMs / 1000.0)
            else (vm.lastExit ?: "就绪")
            Text(
                status,
                fontSize = 12.sp,
                color = if (vm.running) AccentGreen else Color.LightGray,
                modifier = Modifier.weight(1f)
            )
            if (vm.running) {
                TextButton(onClick = { vm.stopRun() }) {
                    Text("Kill Process", color = Color(0xFFFF6B6B))
                }
            }
            TextButton(onClick = { vm.runOutput.clear() }) { Text("清空") }
        }
        ConsoleView(vm.runOutput.text, Modifier.weight(1f).fillMaxWidth())
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Stdin 标准输入（回车发送）", fontSize = 12.sp) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.sendStdin(input); input = "" })
            )
            TextButton(onClick = { vm.sendStdin(input); input = "" }, enabled = vm.running) { Text("发送") }
            TextButton(onClick = { vm.sendEof() }, enabled = vm.running) { Text("EOF") }
        }
    }
}

@Composable
fun ShellTab(vm: IdeViewModel) {
    var cmd by remember { mutableStateOf("") }
    val quick = listOf("uname -a", "cat /etc/os-release", "ls -la", "gcc -v", "python3 -V", "apt update", "df -h")
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (vm.shellAlive) "● 已连接" else "○ 未启动",
                fontSize = 12.sp,
                color = if (vm.shellAlive) AccentGreen else Color.Gray,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            quick.forEach { q ->
                AssistChip(
                    onClick = { vm.execShell(q) },
                    label = { Text(q, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            TextButton(onClick = { vm.restartShell() }) { Text("重启 Shell") }
            TextButton(onClick = { vm.shellOutput.clear() }) { Text("清空") }
        }
        ConsoleView(vm.shellOutput.text, Modifier.weight(1f).fillMaxWidth())
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("# ", color = AccentGreen, fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入 Ubuntu 命令，如 apt install htop", fontSize = 12.sp) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.execShell(cmd); cmd = "" })
            )
            TextButton(onClick = { vm.execShell(cmd); cmd = "" }) { Text("执行") }
        }
    }
}

@Composable
fun ConsoleView(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) {
        var prevMax = 0
        snapshotFlow { scroll.maxValue }.collect { m ->
            if (scroll.value >= prevMax - 80) scroll.scrollTo(m)
            prevMax = m
        }
    }
    Box(modifier = modifier.background(ConsoleBg).padding(6.dp)) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
                color = ConsoleFg,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

// =====================================================================
//  Ubuntu 环境初始化 / 管理面板
// =====================================================================
@Composable
fun EnvPanel(vm: IdeViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importRootfs(uri)
    }
    Dialog(
        onDismissRequest = { if (!vm.bootstrapBusy) vm.showEnvPanel = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ubuntu 环境管理",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { vm.showEnvPanel = false },
                        enabled = !vm.bootstrapBusy
                    ) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = PanelColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (vm.rootfsReady) "✅ Ubuntu 24.04 (arm64) 已就绪" else "⚠️ 尚未初始化 Ubuntu rootfs",
                            color = if (vm.rootfsReady) AccentGreen else UbuntuOrange,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "rootfs: ${vm.runner.rootfsDir.absolutePath}",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Text(
                            "PRoot: " + if (vm.runner.prootBinary.exists()) "已找到 libproot.so" else "缺少 libproot.so（请用 GitHub Actions 构建）",
                            fontSize = 11.sp,
                            color = if (vm.runner.prootBinary.exists()) Color.Gray else Color(0xFFFF6B6B)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("下载源", fontSize = 13.sp, color = Color.LightGray)
                Row {
                    FilterChip(
                        selected = vm.useTuna,
                        onClick = { vm.useTuna = true },
                        label = { Text("清华 TUNA 镜像") },
                        enabled = !vm.bootstrapBusy,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    FilterChip(
                        selected = !vm.useTuna,
                        onClick = { vm.useTuna = false },
                        label = { Text("Ubuntu 官方源") },
                        enabled = !vm.bootstrapBusy
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.downloadRootfs() }, enabled = !vm.bootstrapBusy) {
                        Text(if (vm.rootfsReady) "重新下载初始化" else "下载并初始化")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            picker.launch(
                                arrayOf(
                                    "application/gzip",
                                    "application/x-gzip",
                                    "application/x-compressed-tar",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        enabled = !vm.bootstrapBusy
                    ) { Text("导入本地 .tar.gz") }
                }
                if (vm.bootstrapBusy) {
                    Spacer(Modifier.height(6.dp))
                    if (vm.bootstrapProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { vm.bootstrapProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(vm.bootstrapStatus, fontSize = 12.sp, color = Color.LightGray)
                } else if (vm.bootstrapStatus.isNotEmpty()) {
                    Text(vm.bootstrapStatus, fontSize = 12.sp, color = Color.LightGray)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = PanelColor)
                Spacer(Modifier.height(8.dp))
                Text("一键安装语言环境", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    LANG_PACKS.forEach { (label, cmd) ->
                        OutlinedButton(
                            onClick = { vm.runEnvCommand(label, cmd) },
                            enabled = vm.rootfsReady && !vm.envRunning && !vm.bootstrapBusy,
                            modifier = Modifier.padding(end = 6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text(label, fontSize = 12.sp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (vm.envRunning) "● 安装中…" else "日志",
                        fontSize = 12.sp,
                        color = if (vm.envRunning) AccentGreen else Color.Gray,
                        modifier = Modifier.weight(1f)
                    )
                    if (vm.envRunning) {
                        TextButton(onClick = { vm.stopEnv() }) { Text("停止", color = Color(0xFFFF6B6B)) }
                    }
                    TextButton(onClick = { vm.envLog.clear() }) { Text("清空") }
                }
                ConsoleView(vm.envLog.text, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
