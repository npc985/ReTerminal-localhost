package com.rk.terminal.ui.screens.terminal

import android.content.Context
import com.rk.libcommons.alpineHomeDir
import com.rk.libcommons.child
import com.rk.libcommons.createFileIfNot
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localDir
import com.rk.libcommons.localLibDir
import com.rk.terminal.App.Companion.getTempDir
import com.rk.terminal.BuildConfig
import com.rk.settings.Settings
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

object MkSession {
    fun createSession(
        context: Context,
        sessionClient: TerminalSessionClient,
        sessionId: String,
        workingMode: Int,
        pendingCommand: PendingCommand? = null
    ): TerminalSession {
        with(context) {
            val envVariables = mapOf(
                "ANDROID_ART_ROOT" to System.getenv("ANDROID_ART_ROOT"),
                "ANDROID_DATA" to System.getenv("ANDROID_DATA"),
                "ANDROID_I18N_ROOT" to System.getenv("ANDROID_I18N_ROOT"),
                "ANDROID_ROOT" to System.getenv("ANDROID_ROOT"),
                "ANDROID_RUNTIME_ROOT" to System.getenv("ANDROID_RUNTIME_ROOT"),
                "ANDROID_TZDATA_ROOT" to System.getenv("ANDROID_TZDATA_ROOT"),
                "BOOTCLASSPATH" to System.getenv("BOOTCLASSPATH"),
                "DEX2OATBOOTCLASSPATH" to System.getenv("DEX2OATBOOTCLASSPATH"),
                "EXTERNAL_STORAGE" to System.getenv("EXTERNAL_STORAGE")
            )

            val workingDir = pendingCommand?.workingDir ?: alpineHomeDir().path

            val useChroot = Rootfs.execMode.value == ExecMode.CHROOT

            val initFile: File = localBinDir().child("init-host")
            if (initFile.exists().not()) {
                initFile.createFileIfNot()
                assets.open("init-host.sh").bufferedReader().use { it.readText() }.let {
                    initFile.writeText(it)
                }
            }

            val initChrootFile: File = localBinDir().child("init-host-chroot")
            if (useChroot && initChrootFile.exists().not()) {
                initChrootFile.createFileIfNot()
                assets.open("init-host-chroot.sh").bufferedReader().use { it.readText() }.let {
                    initChrootFile.writeText(it)
                }
            }

            localBinDir().child("init").apply {
                if (exists().not()) {
                    createFileIfNot()
                    assets.open("init.sh").bufferedReader().use { it.readText() }.let {
                        writeText(it)
                    }
                }
            }

            localBinDir().child("rm").apply {
                if (exists().not()) {
                    createFileIfNot()
                    assets.open("rm-wrapper.sh").bufferedReader().use { it.readText() }.let {
                        writeText(it)
                    }
                    setExecutable(true)
                }
            }

            val env = mutableListOf(
                "PATH=${System.getenv("PATH")}:/sbin:${localBinDir().absolutePath}",
                "HOME=/sdcard",
                "PUBLIC_HOME=${getExternalFilesDir(null)?.absolutePath}",
                "COLORTERM=truecolor",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "BIN=${localBinDir()}",
                "DEBUG=${BuildConfig.DEBUG}",
                "PREFIX=${filesDir.parentFile!!.path}",
                "LD_LIBRARY_PATH=${localLibDir().absolutePath}",
                "LINKER=${if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"}",
                "NATIVE_LIB_DIR=${applicationInfo.nativeLibraryDir}",
                "PKG=${packageName}",
                "RISH_APPLICATION_ID=${packageName}",
                "PKG_PATH=${applicationInfo.sourceDir}",
                "PROOT_TMP_DIR=${getTempDir(this).child(sessionId).also { if (it.exists().not()) it.mkdirs() }}",
                "TMPDIR=${getTempDir(this).absolutePath}",
                "PROOT_LOADER=${applicationInfo.nativeLibraryDir}/libloader.so",
                "PROOT=${applicationInfo.nativeLibraryDir}/libproot.so",
                "CHROOT=${if (File("/system/bin/chroot").exists()) "/system/bin/chroot" else "/system/xbin/chroot"}",
                "USE_CHROOT=${if (useChroot) "1" else "0"}",
                "RETERM_LOGIN_SHELL=${Settings.login_shell}",
            )

            val loader32 = "${applicationInfo.nativeLibraryDir}/libloader32.so"
            if (File(loader32).exists()) {
                env.add("PROOT_LOADER_32=$loader32")
            }

            env.addAll(envVariables.map { "${it.key}=${it.value}" })

            localDir().child("stat").apply {
                if (exists().not()) {
                    writeText(TerminalUtils.stat)
                }
            }

            localDir().child("vmstat").apply {
                if (exists().not()) {
                    writeText(TerminalUtils.vmstat)
                }
            }

            pendingCommand?.env?.let {
                env.addAll(it)
            }

            val args: Array<String>
            val shell = if (pendingCommand == null) {
                args = if (workingMode == WorkingMode.ALPINE) {
                    val targetInit = if (useChroot) initChrootFile else initFile
                    arrayOf("-c",targetInit.absolutePath)
                } else {
                    arrayOf()
                }
                "/system/bin/sh"
            } else {
                args = pendingCommand.args
                pendingCommand.shell
            }

            return TerminalSession(
                shell,
                workingDir,
                args,
                env.toTypedArray(),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient,
            )
        }
    }

    fun buildCustomPendingCommand(context: Context, custom: CustomSession): PendingCommand {
        val scriptFile = File(custom.shellPath)
        val sysSh = File("/system/bin/sh")

        val shell: String
        val args: Array<String>

        if (sysSh.canExecute()) {
            shell = sysSh.absolutePath
            args = arrayOf("-c", scriptFile.absolutePath)
        } else {
            val proot = "${context.applicationInfo.nativeLibraryDir}/libproot.so"
            shell = proot
            args = arrayOf(
                "-r", "/",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sdcard",
                "-0",
                "sh", scriptFile.absolutePath
            )
        }

        return PendingCommand(
            shell = shell,
            args = args,
            workingDir = scriptFile.parentFile?.absolutePath ?: "/sdcard/ReTerminal",
            env = null
        )
    }

    fun buildScriptPendingCommand(
        context: Context,
        script: File,
        workingMode: Int,
        custom: CustomSession? = null
    ): PendingCommand {
        val workingDir = script.parentFile?.absolutePath
        return if (custom != null) {
            val sysSh = File("/system/bin/sh")
            if (sysSh.canExecute()) {
                PendingCommand(
                    shell = sysSh.absolutePath,
                    args = arrayOf("-c", "'${custom.shellPath}' '${script.absolutePath}'"),
                    workingDir = workingDir,
                    env = null
                )
            } else {
                val proot = "${context.applicationInfo.nativeLibraryDir}/libproot.so"
                PendingCommand(
                    shell = proot,
                    args = arrayOf(
                        "-r", "/",
                        "-b", "/dev",
                        "-b", "/proc",
                        "-b", "/sdcard",
                        "-0",
                        "sh", custom.shellPath, script.absolutePath
                    ),
                    workingDir = workingDir,
                    env = null
                )
            }
        } else if (workingMode == WorkingMode.ALPINE) {
            val initFile = context.localBinDir()
                .child(if (Rootfs.execMode.value == ExecMode.CHROOT) "init-host-chroot" else "init-host")
            PendingCommand(
                shell = "/system/bin/sh",
                args = arrayOf("-c",initFile.absolutePath,"sh",script.absolutePath),
                workingDir = workingDir,
                env = null
            )
        } else {
            PendingCommand(
                shell = "/system/bin/sh",
                args = arrayOf("-c", script.absolutePath),
                workingDir = workingDir,
                env = null
            )
        }
    }

    fun buildSshAlpinePendingCommand(context: Context): PendingCommand {
        // 1. 从 Settings 中读取配置 (彻底抛弃不安全的 /sdcard 文件读取)
        val ip = Settings.ssh_ip
        val port = Settings.ssh_port
        val user = Settings.ssh_user
        val password = Settings.ssh_password

        // 2. 输入安全过滤 (防止命令注入，例如用户输入 "22; rm -rf /")
        val safeIp = ip.filter { it.isLetterOrDigit() || it == '.' || it == ':' || it == '-' }
        val safePort = port.filter { it.isDigit() }
        val safeUser = user.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

        // 3. 设定 known_hosts 路径（存储在 App 私有目录，避免中间人攻击）
        val knownHostsFile = File(context.filesDir, "known_hosts")

        // 4. 构建 SSH 基础参数
        // 使用 accept-new 避免首次连接输入 yes，同时依然保留后续连接的主机密钥校验
        val sshArgs = "-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=${knownHostsFile.absolutePath}"

        // 5. 组装命令
        // 如果有密码，使用 sshpass -e 从环境变量读取，避免密码出现在 ps 命令行和磁盘中
        val sshCommand = if (password.isNotEmpty()) {
            "exec sshpass -e ssh $sshArgs $safeUser@$safeIp -p $safePort"
        } else {
            "exec ssh $sshArgs $safeUser@$safeIp -p $safePort"
        }

        // 6. 将命令写入专属的脚本文件，避开 -c 的解析 bug
        val scriptFile = File(context.filesDir, "ssh_alpine.sh")
        scriptFile.writeText("$sshCommand\n")

        // 7. 准备环境变量，将密码传递给 sshpass
        val envList = mutableListOf<String>()
        if (password.isNotEmpty()) {
            envList.add("SSHPASS=$password")
        }

        return PendingCommand(
            shell = "/system/bin/sh",
            args = arrayOf(scriptFile.absolutePath),
            workingDir = context.filesDir.absolutePath, 
            env = envList
        )
    }
}

data class PendingCommand(
    val shell: String,
    val args: Array<String>,
    val workingDir: String?,
    val env: List<String>?
)