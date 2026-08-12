package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * A long-running PRoot shell process that persists across commands.
 * Agent commands are sent via stdin and output is captured using unique
 * end-of-command markers, similar to iOS ISHKernel.executeCommandAndWait.
 *
 * This ensures that environment variables, working directory, installed
 * packages, and running services all persist between commands within the
 * same session.
 */
class PersistentShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,  // linuxPath -> hostPath
) {

    companion object {
        private const val TAG = "PersistentShell"
        private const val MIN_PROOT_BYTES = 1024 * 1024L
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    private val isStarting = AtomicBoolean(false)

    /** Pending command callback — only one command at a time. */
    @Volatile
    private var pendingCallback: CommandCallback? = null

    val isAlive: Boolean
        get() = process?.isAlive == true

    /** [diag] Read back the mount this shell was started with (frozen at boot). */
    fun debugBindMount(linuxPath: String): String? = sessionBindMounts[linuxPath]

    private class CommandCallback(
        val marker: String,
        val output: StringBuilder = StringBuilder(),
        val lineCallback: ((String) -> Unit)?,
        var onComplete: ((String, Int) -> Unit)? = null,
    )

    /**
     * Ensure the persistent shell process is running.
     * Idempotent — returns immediately if already alive.
     */
    suspend fun ensureStarted() {
        if (isAlive) return
        if (!isStarting.compareAndSet(false, true)) {
            // Another coroutine is already starting — wait for it
            while (isStarting.get() && !isAlive) {
                kotlinx.coroutines.delay(50)
            }
            return
        }

        try {
            withContext(Dispatchers.IO) { startProcess() }
        } finally {
            isStarting.set(false)
        }
    }

    /**
     * Resolve the proot binary to exec. Prefers a copy staged from
     * assets/proot-aarch64 (noCompress, byte-identical to the binary built by
     * deps/build_proot.sh) into filesDir — the assets copy is fully under our
     * control and never passes through AGP packaging / installd extraction.
     * Falls back to nativeLibraryDir/libproot.so (the jniLibs twin) if the
     * asset is missing. Returns null only when neither is usable.
     */
    private fun resolveProotBinary(rootfsManager: RootfsManager): File? {
        val staged = File(context.filesDir, "proot-aarch64")
        if (!staged.exists() || staged.length() < MIN_PROOT_BYTES) {
            try {
                context.assets.open("proot-aarch64").use { input ->
                    staged.outputStream().use { out -> input.copyTo(out) }
                }
                staged.setExecutable(true, false)
                Log.i(TAG, "Staged proot from assets: $staged (${staged.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot stage proot from assets (${e.message}); falling back to jniLibs")
            }
        }
        if (staged.exists() && staged.length() >= MIN_PROOT_BYTES) {
            return staged
        }
        val jni = rootfsManager.prootBinary
        if (jni.exists() && jni.canExecute() && jni.length() >= MIN_PROOT_BYTES) {
            Log.i(TAG, "Using jniLibs proot: $jni (${jni.length()} bytes)")
            return jni
        }
        Log.e(TAG, "No usable proot binary: staged=$staged exists=${staged.exists()} " +
            "size=${staged.length()}; jni=$jni exists=${jni.exists()} size=${jni.length()}")
        return null
    }

    private fun startProcess() {
        Log.i(TAG, "Starting persistent shell process")

        val rootfsManager = RootfsManager.getInstance(context)

        // Sanity-check the rootfs before launch: a corrupt/truncated asset
        // extraction yields a rootfs without /bin/sh and proot would fail
        // silently at exec time ("can't execve /bin/sh").
        val rootShell = File(rootfsManager.rootfsDir, "bin/sh")
        if (!rootShell.exists()) {
            Log.e(
                TAG,
                "Rootfs appears broken: $rootShell missing (rootfsDir=${rootfsManager.rootfsDir} " +
                    "isInstalled=${rootfsManager.isInstalled}). Reinstall the rootfs from " +
                    "Settings → Root filesystem."
            )
        }

        val prootBin = resolveProotBinary(rootfsManager)
        if (prootBin == null) {
            Log.e(TAG, "Aborting shell start: no usable proot binary")
            return
        }

        val cmd = mutableListOf<String>()
        cmd.add(prootBin.absolutePath)
        cmd.add("-0")
        // T141: see PRootKernel.buildProotCommand for rationale — translates
        // hardlinks to symlinks so apk install of binutils/gcc works.
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")

        // Apply this session's bind mounts (session-specific, not global)
        for ((linuxPath, hostPath) in sessionBindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
        }

        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        cmd.add("/bin/sh")

        val debugOffload = com.openminis.app.BuildConfig.DEBUG

        val processBuilder = ProcessBuilder(cmd)
        // In debug builds we want proot's native_offload stderr logs in
        // logcat, not merged into shell stdout (which would break the
        // __MINIS_DONE__ marker detection). Release keeps the original
        // merged behavior so no stderr output is lost silently.
        processBuilder.redirectErrorStream(!debugOffload)

        val env = processBuilder.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        }
        if (PRootKernel.prootLoaderPath.isNotEmpty()) {
            env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        }
        if (PRootKernel.prootLoader32Path.isNotEmpty()) {
            env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        }
        env["TERM"] = "dumb"
        env["PS1"] = ""  // Suppress prompt to avoid polluting output
        // Timezone: customEnvironment["TZ"] is seeded at PRootKernel.boot(),
        // but refresh it here in case the system timezone changed between boot
        // and now.
        env["TZ"] = PRootKernel.posixTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        // T340: forward the chat session id to native_offload handlers via
        // proot env. NativeOffloadServer reads this off `request.env` and
        // hands it to OffloadPermissionManager so ASK_ONCE grants/denials
        // are scoped per chat session, not globally.
        env["MINIS_CHAT_SESSION_ID"] = sessionId

        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }

        val p: Process
        try {
            p = processBuilder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start persistent shell process: ${e.message}", e)
            throw e
        }
        process = p
        stdinWriter = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

        // Start background reader thread
        Thread({
            readLoop(p)
        }, "PersistentShell-reader").apply {
            isDaemon = true
            start()
        }

        // In debug, drain stderr separately into logcat.
        if (debugOffload) {
            Thread({
                val br = p.errorStream.bufferedReader(StandardCharsets.UTF_8)
                try {
                    for (line in br.lineSequence()) Log.d("PRootStderr", line)
                } catch (_: Exception) {}
            }, "PersistentShell-stderr").apply {
                isDaemon = true
                start()
            }
        }

        // Wait briefly for shell to initialize
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {}

        if (!p.isAlive) {
            val exit = runCatching { p.exitValue() }.getOrNull()
            Log.e(
                TAG,
                "Persistent shell died during startup (exit=$exit) — " +
                    "see [shell]/PRootStderr logs above for the proot error"
            )
        } else {
            Log.d(TAG, "Persistent shell alive after startup")
        }
        Log.i(TAG, "Persistent shell started")
    }

    private fun readLoop(p: Process) {
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                val text = String(buffer, 0, n, StandardCharsets.UTF_8)

                val cb = pendingCallback
                if (cb != null) {
                    // Check if this chunk contains the end marker
                    val markerExitPattern = "__MINIS_DONE_${cb.marker}_EXIT_"
                    val markerIdx = text.indexOf(markerExitPattern)

                    if (markerIdx >= 0) {
                        // Extract output before marker
                        val beforeMarker = text.substring(0, markerIdx)
                        cb.output.append(beforeMarker)
                        if (cb.lineCallback != null) {
                            feedLines(beforeMarker, cb.lineCallback)
                        }

                        // Extract exit code from marker line
                        val afterMarker = text.substring(markerIdx)
                        val exitCode = parseExitCode(afterMarker, cb.marker)

                        // Signal completion
                        cb.onComplete?.invoke(cb.output.toString(), exitCode)
                        pendingCallback = null
                    } else {
                        cb.output.append(text)
                        if (cb.lineCallback != null) {
                            feedLines(text, cb.lineCallback)
                        }
                    }
                } else if (text.isNotBlank()) {
                    // No pending command — this is proot startup chatter or
                    // stderr (release merges stderr into stdout). Log it so a
                    // shell that dies during startup leaves a diagnostic trail
                    // instead of a silent "process exited".
                    Log.d(TAG, "[shell] ${text.trim().take(4000)}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        // Process exited
        val cb = pendingCallback
        if (cb != null) {
            cb.onComplete?.invoke(cb.output.toString(), -1)
            pendingCallback = null
        }

        process = null
        stdinWriter = null
        Log.i(TAG, "Persistent shell process exited")
    }

    private fun feedLines(text: String, callback: (String) -> Unit) {
        val lines = text.split('\n')
        for (i in lines.indices) {
            val line = lines[i].replace("\r", "")
            if (line.isNotEmpty() && (i < lines.size - 1 || text.endsWith('\n'))) {
                callback(line)
            } else if (line.isNotEmpty() && i == lines.size - 1) {
                // Partial line — still feed it for real-time updates
                callback(line)
            }
        }
    }

    private fun parseExitCode(text: String, marker: String): Int {
        // Pattern: __MINIS_DONE_{marker}_EXIT_{code}__
        val regex = Regex("__MINIS_DONE_${Regex.escape(marker)}_EXIT_(\\d+)__")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * Execute a command in the persistent shell and wait for completion.
     *
     * Wraps the command with a unique marker to detect output boundaries:
     *   {command}; echo "__MINIS_DONE_{marker}_EXIT_$?__"
     *
     * @return Pair of (output, exitCode)
     */
    suspend fun executeCommand(
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> {
        ensureStarted()

        val writer = stdinWriter
        if (writer == null || !isAlive) {
            return Pair("[Shell not running]", -1)
        }

        val marker = UUID.randomUUID().toString().take(8)
        val wrappedCommand = "$command\necho \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n"

        return withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine { cont ->
                    val cb = CommandCallback(
                        marker = marker,
                        lineCallback = lineCallback,
                    )
                    cb.onComplete = { output, exitCode ->
                        if (cont.isActive) {
                            cont.resume(Pair(output, exitCode))
                        }
                    }
                    pendingCallback = cb

                    cont.invokeOnCancellation {
                        pendingCallback = null
                    }

                    try {
                        writer.write(wrappedCommand)
                        writer.flush()
                    } catch (e: Exception) {
                        pendingCallback = null
                        if (cont.isActive) {
                            cont.resume(Pair("[Write error: ${e.message}]", -1))
                        }
                    }
                }
            }

            if (result == null) {
                // Timeout — cancel pending, but don't kill the shell
                pendingCallback = null
                Pair("[Command timed out after ${timeout / 1000}s]", 124)
            } else {
                result
            }
        }
    }

    /**
     * Apply environment variables to the running shell.
     *
     * The shell is long-lived and reused across commands, so a stale `export`
     * from a previous turn lingers until something overwrites it. Caller can
     * supply [previousKeys] — the set of variable names injected on the prior
     * call — so any name absent from the new [envVars] is `unset` first. That
     * gives whole-snapshot semantics matching the per-command isolation iOS
     * gets for free with one `/bin/sh` process per command.
     *
     * Empty default for [previousKeys] preserves the original behaviour for
     * system broadcasts (TZ, proxy) that are only meant to overlay specific
     * keys, never wipe the user's env-var snapshot.
     */
    suspend fun applyEnvironment(
        envVars: Map<String, String>,
        previousKeys: Set<String> = emptySet(),
    ) {
        if (!isAlive) return
        val writer = stdinWriter ?: return
        withContext(Dispatchers.IO) {
            try {
                // Build export/unset commands
                val commands = mutableListOf<String>()
                for ((key, value) in envVars) {
                    commands.add("export $key=${shellQuote(value)}")
                }
                for (key in previousKeys) {
                    if (key !in envVars) {
                        commands.add("unset $key")
                    }
                }
                val script = commands.joinToString(" && ")
                if (script.isNotEmpty()) {
                    writer.write("$script\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "applyEnvironment failed: ${e.message}")
            }
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}