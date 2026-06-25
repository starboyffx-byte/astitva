package com.vicky.astitva.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import android.location.Location
import java.io.File
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest


object AgenticTools {

    var appContext: Context? = null

    @SuppressLint("Range")
    fun readLatestSms(context: Context, limit: Int = 3): String {
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("address", "body", "date")
        val sb = StringBuilder()
        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, "date DESC LIMIT $limit")
            if (cursor != null && cursor.moveToFirst()) {
                var count = 1
                do {
                    val address = cursor.getString(cursor.getColumnIndexOrThrow("address"))
                    val body = cursor.getString(cursor.getColumnIndexOrThrow("body"))
                    sb.append("SMS $count from $address: $body\n")
                    count++
                } while (cursor.moveToNext())
                cursor.close()
            } else return "No recent SMS found."
        } catch (e: Exception) { return "Error reading SMS: ${e.message}" }
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(context: Context): String {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null
            for (provider in providers) {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.accuracy < bestLocation.accuracy) bestLocation = l
            }
            return if (bestLocation != null) "Lat: ${bestLocation.latitude}, Lon: ${bestLocation.longitude}" else "Location unavailable."
        } catch (e: Exception) { return "Error: ${e.message}" }
    }

    fun readNotifications(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys notification --noredact | grep -E 'tickerText=|android.title=|android.text=' | tail -n 10"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (output.isNotBlank()) "Notifications:\n$output" else "No notifications found."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun makeCall(context: Context, number: String): String {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            "Calling $number"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun endCall(): String {
        return if (RootMotor.executeRaw("input keyevent 6")) "End call sent." else "Failed to end call."
    }

    fun findPackageAndOpen(context: Context, appName: String): String {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes("pm list packages\nexit\n")
            os.flush()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val packages = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                packages.add(line!!.substringAfter("package:").trim())
            }
            var targetPkg = packages.find { it.split('.').last().equals(appName, ignoreCase = true) }
            if (targetPkg == null) targetPkg = packages.find { it.contains(appName, ignoreCase = true) }
            if (targetPkg != null) {
                RootMotor.executeRaw("am start -n $(pm resolve-activity --components $targetPkg | grep -m1 'component=' | cut -d '=' -f2 | cut -d ' ' -f1) || monkey -p $targetPkg 1")
                return "Opening: $targetPkg"
            }
            return "App '$appName' not found."
        } catch (e: Exception) { return "Error: ${e.message}" }
    }

    fun searchPackages(query: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm list packages | grep -i \"$query\" | cut -d ':' -f2"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (output.isNotBlank()) "Matching Packages:\n$output" else "No packages found for '$query'."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun launchPackage(pkgName: String): String {
        return try {
            val cmd = "am start -n $(pm resolve-activity --components $pkgName | grep -m1 'component=' | cut -d '=' -f2 | cut -d ' ' -f1) || monkey -p $pkgName 1"
            RootMotor.executeRaw(cmd)
            "Launch command sent for $pkgName"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getSystemVitals(): String {
        return try {
            val mem = executeShell("head -n 3 /proc/meminfo")
            val ver = executeShell("cat /proc/version").take(60)
            "Vitals:\n$mem\nKernel: $ver"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun readFile(path: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat \"$path\""))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (output.length > 4000) output.take(4000) + "\n... (Truncated)" else output.ifBlank { "File is empty or not readable." }
        } catch (e: Exception) { "Read Error: ${e.message}" }
    }

    fun writeFile(path: String, content: String): String {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            val encoded = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
            os.writeBytes("echo '$encoded' | base64 -d > \"$path\"\nexit\n")
            os.flush()
            process.waitFor()
            "File written to $path"
        } catch (e: Exception) { "Write Error: ${e.message}" }
    }

    fun executeShell(command: String): String {
        return try {
            val cmdTrim = command.trim()
            // Identify commands that require absolute root privilege (system commands)
            var requiresRoot = cmdTrim.startsWith("dumpsys") || 
                               cmdTrim.startsWith("logcat") || 
                               cmdTrim.startsWith("find /data/data") || 
                               cmdTrim.startsWith("svc") || 
                               cmdTrim.startsWith("settings") || 
                               cmdTrim.startsWith("pm ") || 
                               cmdTrim.startsWith("am ") || 
                               cmdTrim.startsWith("su ") ||
                               cmdTrim.startsWith("reboot") ||
                               cmdTrim.startsWith("sudo ") ||
                               cmdTrim.startsWith("tsu ")

            var cmdToRun = command
            if (cmdTrim.startsWith("sudo ")) {
                requiresRoot = true
                cmdToRun = cmdTrim.substringAfter("sudo ").trim()
            } else if (cmdTrim.startsWith("tsu ")) {
                requiresRoot = true
                cmdToRun = cmdTrim.substringAfter("tsu ").trim()
            }

            val bashShell = "/system/bin/sh"
            val home = "/data/data/com.vicky.astitva/files"
            val envCmd = "export PATH=/system/bin:/system/xbin:\$PATH; " +
                         "export HOME=$home; " +
                         cmdToRun
                         
            val process = if (requiresRoot) {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-s", bashShell))
                val os = java.io.DataOutputStream(proc.outputStream)
                os.write(envCmd.toByteArray(Charsets.UTF_8))
                os.writeBytes("\nexit\n")
                os.flush()
                os.close()
                proc
            } else {
                Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", envCmd))
            }
            
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            (output + "\n" + error).trim().ifEmpty { "Executed (No Output)" }
        } catch (e: Exception) { "Shell Error: ${e.message}" }
    }

    fun scanPorts(host: String = "localhost"): String {
        val sb = StringBuilder("Ports for $host:\n")
        val commonPorts = listOf(21, 22, 80, 443, 3000, 8080)
        for (port in commonPorts) {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, port), 150)
                socket.close()
                sb.append("✓ $port: OPEN\n")
            } catch (e: Exception) { }
        }
        return if (sb.length > 20) sb.toString() else "No open ports."
    }

    fun inspectFileSystem(query: String): String {
        val cmd = "find /data/data -name \"*$query*\" 2>/dev/null | head -n 15"
        return executeShell(cmd)
    }

    fun analyzeLogcat(keyword: String): String {
        val cmd = "logcat -d | grep -i \"$keyword\" | tail -n 15"
        return executeShell(cmd)
    }

    // --- NEXT-LEVEL ENHANCEMENT TOOLS ---
    fun localOcr(): String = AstitvaAccessibility.forceDumpUI()
    fun browserExtract(): String = executeShell("uiautomator dump /sdcard/view.xml && grep -oP 'text=\"\\K[^\"]+' /sdcard/view.xml | head -n 50")
    fun browserClick(selector: String): String {
        val dump = executeShell("uiautomator dump /sdcard/view.xml && grep -i \"$selector\" /sdcard/view.xml")
        return if (dump.contains("bounds=\"")) {
            val bounds = dump.substringAfter("bounds=\"").substringBefore("\"")
            val coords = bounds.replace("[", "").replace("]", ",").split(",")
            val x = (coords[0].toInt() + coords[2].toInt()) / 2
            val y = (coords[1].toInt() + coords[3].toInt()) / 2
            RootMotor.tap(x, y); "Clicked '$selector' at $x,$y"
        } else "Element '$selector' not found."
    }
    fun generateKotlinPatch(path: String, code: String): String = writeFile(path, code)

    // --- CHROME CDP AGENTIC BROWSER TOOLS ---
    fun chromeGetDom(): String = executeKali("python3 /root/chrome_agent.py --get-dom")
    fun chromeClick(x: String, y: String): String = executeKali("python3 /root/chrome_agent.py --click $x $y")
    fun chromeType(selector: String, text: String): String {
        val base64Text = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
        val cmd = "python3 /root/chrome_agent.py --type-b64 \"$selector\" \"$base64Text\""
        return executeKali(cmd)
    }
    fun chromeEval(code: String): String {
        val base64Code = android.util.Base64.encodeToString(code.toByteArray(), android.util.Base64.NO_WRAP)
        val cmd = "python3 /root/chrome_agent.py --eval-b64 \"$base64Code\""
        return executeKali(cmd)
    }
    fun chromeListTabs(): String = executeKali("python3 /root/chrome_agent.py --list")

    /**
     * Captures a silent image from the specified camera (0 for Rear, 1 for Front).
     * Saves it to the cache for AI analysis.
     */
    fun captureCameraImage(context: Context, cameraId: Int = 0): String {
        return CameraHelper.captureFrame(context, cameraId)
    }

    fun executeAstitvaShell(context: Context, command: String): String {
        return executeShell(command)
    }

    fun executeKali(command: String): String {
        return try {
            val cmdTrim = command.trim()
            if (cmdTrim.isEmpty()) return "Error: Command is empty."

            // Auto-clean: clear logs larger than 20MB or older than 1 day in chroot /tmp to preserve space
            val cleanupCmd = "find /tmp -type f -name '*.log' -size +20M -exec truncate -s 0 {} \\;; " +
                             "find /tmp -type f -name '*.log' -mtime +1 -delete 2>/dev/null; "

            val isBg = cmdTrim.endsWith("&")
            var finalCmd = cmdTrim
            if (isBg) {
                val cleanCmd = cmdTrim.substring(0, cmdTrim.length - 1).trim()
                // Overwrite the background log with > to reset log size on new run
                finalCmd = "$cleanupCmd echo '=== Starting Background Command: $cleanCmd ===' > /tmp/kali_bg_exec.log; " +
                           "nohup $cleanCmd >> /tmp/kali_bg_exec.log 2>&1 &"
            } else {
                finalCmd = "$cleanupCmd $cmdTrim"
            }

            val cmdWithEnv = "export DEBIAN_FRONTEND=noninteractive; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " + finalCmd
            val base64Cmd = android.util.Base64.encodeToString(cmdWithEnv.toByteArray(), android.util.Base64.NO_WRAP)
            
            // Setup Mounts dynamically if missing
            val setupMounts = "if [ ! -d /data/local/debian/kali-arm64/proc/1 ]; then " +
                              "mount --bind /dev /data/local/debian/kali-arm64/dev; " +
                              "mount --bind /dev/pts /data/local/debian/kali-arm64/dev/pts; " +
                              "mount --bind /proc /data/local/debian/kali-arm64/proc; " +
                              "mount --bind /sys /data/local/debian/kali-arm64/sys; " +
                              "mount --bind /sdcard /data/local/debian/kali-arm64/mnt; " +
                              "echo 'nameserver 8.8.8.8' > /data/local/debian/kali-arm64/etc/resolv.conf; " +
                              "fi; "
            
            // Decode and pipe to Kali chroot bash under PID 1 context
            val chrootWrapper = setupMounts + "echo '$base64Cmd' | base64 -d | chroot /data/local/debian/kali-arm64 /bin/bash"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-t", "1", "-c", chrootWrapper))
            
            val outSb = StringBuilder()
            val errSb = StringBuilder()
            
            val outThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            outSb.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {}
            }
            
            val errThread = Thread {
                try {
                    process.errorStream.bufferedReader().use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            errSb.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {}
            }
            
            outThread.start()
            errThread.start()
            
            // 30 seconds timeout to prevent freezing/lag
            val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                outThread.join(1000)
                errThread.join(1000)
                val currentOut = (outSb.toString() + "\n" + errSb.toString()).trim()
                return "Warning: Command timed out after 30 seconds to prevent application freezing.\n" +
                       "If this is a long-running command (like msfconsole, frida, or server tools), " +
                       "please append ' &' to the end of the command to run it smoothly in the background.\n" +
                       "Partial Output:\n$currentOut"
            }
            
            outThread.join(1000)
            errThread.join(1000)
            
            val finalOutput = (outSb.toString() + "\n" + errSb.toString()).trim()
            if (isBg) {
                return "Background process started successfully.\n" +
                       "You can monitor the output at any time using: KALI_EXEC cat /tmp/kali_bg_exec.log"
            }
            
            return finalOutput.ifEmpty { "Success (No Output)" }
        } catch (e: Exception) {
            "Kali Exec Error: ${e.message}"
        }
    }

    fun initializeEnvironment(context: Context): String {
        appContext = context.applicationContext
        return try {
            val binDir = File(context.filesDir, "binaries")
            if (!binDir.exists()) binDir.mkdirs()
            
            // Copy binaries from assets/binaries to internal storage
            val assetManager = context.assets
            val files = assetManager.list("binaries") ?: emptyArray()
            for (filename in files) {
                val outFile = File(binDir, filename)
                assetManager.open("binaries/$filename").use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            // Setting executable permissions for all files in binaries folder using native API (runs without su/root)
            binDir.listFiles()?.forEach { file ->
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
            "Environment Initialized at ${binDir.absolutePath} with ${files.size} binaries."
        } catch (e: Exception) { "Init Error: ${e.message}" }
    }

    /**
     * Executes security-related commands for Red Teaming and Bug Bounty.
     * Supports nmap, frida, sqlmap, etc., via Termux/Root shell.
     */
    fun executeSecurityTool(toolName: String, target: String, params: String): String {
        // Points to internal binary path if bundled in APK assets
        val binPath = "/data/data/com.vicky.astitva/files/binaries/"
        val command = when (toolName.lowercase()) {
            "nmap" -> "${binPath}nmap $params $target"
            "frida" -> "${binPath}frida -U -f $target --no-pause"
            "sqlmap" -> "python3 ${binPath}sqlmap.py -u \"$target\" $params"
            "mitmproxy" -> "${binPath}mitmdump -p 8080 $params"
            "metasploit" -> "${binPath}msfconsole -q -x \"use $target; set RHOSTS $params; run\""
            "ffuf" -> "${binPath}ffuf -u $target -w ${binPath}wordlist.txt $params"
            else -> "echo 'Tool $toolName not configured'"
        }
        return executeShell(command)
    }

    fun installSecuritySuite(): String {
        val installCmd = "pkg update && pkg install -y nmap python openssh frida-tools && pip install sqlmap mitmproxy"
        return executeShell(installCmd)
    }

    fun setVolume(context: Context, percentage: Int): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val targetVolume = (maxVolume * percentage) / 100
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVolume, android.media.AudioManager.FLAG_SHOW_UI)
            "Volume set to $percentage% (Value $targetVolume/$maxVolume)"
        } catch (e: java.lang.Exception) { "Volume Error: ${e.message}" }
    }

    fun setBrightness(percentage: Int): String {
        val val255 = (255 * percentage) / 100
        val cmd = "settings put system screen_brightness $val255"
        return if (RootMotor.executeRaw(cmd)) "Brightness set to $percentage%" else "Failed to set brightness."
    }

    fun toggleWifi(enable: Boolean): String {
        val state = if (enable) "enable" else "disable"
        val cmd = "svc wifi $state"
        return if (RootMotor.executeRaw(cmd)) "Wi-Fi toggled $state" else "Failed to toggle Wi-Fi."
    }

    fun toggleBluetooth(enable: Boolean): String {
        val state = if (enable) "enable" else "disable"
        val cmd = "cmd bluetooth_manager $state"
        return if (RootMotor.executeRaw(cmd)) "Bluetooth toggled $state" else "Failed to toggle Bluetooth."
    }

    fun mediaControl(action: String): String {
        val keycode = when (action.lowercase()) {
            "play", "resume" -> 126
            "pause" -> 127
            "next" -> 87
            "prev", "previous" -> 88
            "stop" -> 86
            else -> return "Unknown media action: $action"
        }
        return if (RootMotor.executeRaw("input keyevent $keycode")) "Media control $action executed." else "Failed to execute media control."
    }

    fun sendMessage(context: Context, target: String, message: String): String {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$target: $message")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Send Message via...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Message send prompt opened."
        } catch (e: java.lang.Exception) { "Error: ${e.message}" }
    }

    fun executeKernelEvent(device: String, type: Int, code: Int, value: Int): String {
        val cmd = "sendevent /dev/input/$device $type $code $value"
        return if (RootMotor.executeRaw(cmd)) "Kernel event sent: $cmd" else "Failed to send kernel event."
    }

    fun executeKernelTouch(device: String, x: Int, y: Int): String {
        val cmds = arrayOf(
            "sendevent /dev/input/$device 3 57 1",       
            "sendevent /dev/input/$device 3 53 $x",      
            "sendevent /dev/input/$device 3 54 $y",      
            "sendevent /dev/input/$device 1 330 1",      
            "sendevent /dev/input/$device 0 0 0",        
            "sendevent /dev/input/$device 3 57 -1",      
            "sendevent /dev/input/$device 1 330 0",      
            "sendevent /dev/input/$device 0 0 0"         
        )
        var success = true
        for (cmd in cmds) {
            if (!RootMotor.executeRaw(cmd)) {
                success = false
            }
        }
        return if (success) "Kernel raw touch simulated at $x,$y on /dev/input/$device" else "Failed to simulate kernel touch."
    }

    fun runLocalInference(scriptPath: String, param: String): String {
        val cmd = "python3 \"$scriptPath\" \"$param\""
        return executeShell(cmd)
    }

    private var cachedTermuxUid: String? = null

    private fun getTermuxUid(): String {
        cachedTermuxUid?.let { return it }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "stat -c %u /data/data/com.termux"))
            val output = process.inputStream.bufferedReader().readText().trim()
            if (output.isNotEmpty() && output.all { it.isDigit() }) {
                cachedTermuxUid = output
                output
            } else {
                "10403" // Standard Termux fallback UID
            }
        } catch (e: Exception) {
            "10403"
        }
    }


    private data class CodeLangConfig(
        val ext: String,
        val compileCmd: String,
        val runCmd: String,
        val installCmd: String,
        val binaryCheck: String
    )

    fun executeCode(language: String, code: String): String {
        val workspace = File("/data/data/com.termux/files/home/astitva_workspace")
        if (!workspace.exists()) workspace.mkdirs()

        val lang = language.lowercase().trim()
        val config = when (lang) {
            "python", "py" -> CodeLangConfig(".py", "", "python3 {file}", "pkg install -y python", "python3")
            "javascript", "js", "node" -> CodeLangConfig(".js", "", "node {file}", "pkg install -y nodejs", "node")
            "typescript", "ts" -> CodeLangConfig(".ts", "", "ts-node {file}", "pkg install -y nodejs && npm install -g typescript ts-node", "ts-node")
            "rust", "rs" -> CodeLangConfig(".rs", "rustc {file} -o {out}", "{out}", "pkg install -y rust", "rustc")
            "c" -> CodeLangConfig(".c", "clang {file} -o {out}", "{out}", "pkg install -y clang", "clang")
            "cpp", "c++" -> CodeLangConfig(".cpp", "clang++ {file} -o {out}", "{out}", "pkg install -y clang", "clang++")
            "java" -> CodeLangConfig(".java", "javac {file}", "java -cp {dir} {className}", "pkg install -y openjdk-17", "javac")
            "bash", "sh" -> CodeLangConfig(".sh", "chmod +x {file}", "bash {file}", "", "bash")
            else -> return "Error: Unsupported language '$language'. Supported: python, javascript, typescript, rust, c, cpp, java, bash"
        }

        val file = File(workspace, "temp_code${config.ext}")
        try {
            file.writeText(code)
        } catch (e: Exception) {
            return "Error writing code to file: ${e.message}"
        }

        val outPath = File(workspace, "temp_code.out").absolutePath

        // If OpenJDK is used, we can execute single file java program directly via `java MyFile.java`
        val actualRunCmd = if (lang == "java") "java ${file.absolutePath}" else config.runCmd

        // Check if the interpreter/compiler binary is installed
        val checkResult = executeShell("which ${config.binaryCheck}")
        if (checkResult.contains("not found") || checkResult.isBlank()) {
            if (config.installCmd.isNotEmpty()) {
                val installResult = executeShell(config.installCmd)
                if (installResult.contains("Error") || installResult.contains("failed")) {
                    return "Error: Language binary '${config.binaryCheck}' not found and auto-installation failed: $installResult"
                }
            } else {
                return "Error: Language binary '${config.binaryCheck}' not found."
            }
        }

        // Perform compilation if compileCmd is not empty
        if (config.compileCmd.isNotEmpty() && lang != "java") {
            val formattedCompile = config.compileCmd
                .replace("{file}", file.absolutePath)
                .replace("{out}", outPath)
            val compileResult = executeShell(formattedCompile)
            if (compileResult.isNotBlank() && (compileResult.contains("error") || compileResult.contains("fatal") || compileResult.contains("Shell Error"))) {
                return "Compilation Error:\n$compileResult"
            }
        }

        // Run the code
        val finalRunCmd = actualRunCmd
            .replace("{file}", file.absolutePath)
            .replace("{out}", outPath)
            .replace("{dir}", workspace.absolutePath)
        
        return executeShell(finalRunCmd)
    }

    fun calculateSHA1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        val sha1Bytes = digest.digest()
        val result = StringBuilder()
        for (b in sha1Bytes) {
            result.append(String.format("%02x", b))
        }
        return result.toString()
    }

    fun zipDirectory(sourceDirPath: String, zipFilePath: String) {
        val sourceDir = File(sourceDirPath)
        ZipOutputStream(FileOutputStream(zipFilePath)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = sourceDir.toURI().relativize(file.toURI()).path
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    fun deployToVercel(projectPath: String, token: String): String {
        try {
            val sourceDir = File(projectPath)
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                return "Error: Project path $projectPath does not exist or is not a directory."
            }
            
            val filesList = mutableListOf<File>()
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    filesList.add(file)
                }
            }
            
            if (filesList.isEmpty()) {
                return "Error: No files found in $projectPath to deploy."
            }
            
            val vercelFilesArray = JSONArray()
            
            for (file in filesList) {
                val sha = calculateSHA1(file)
                val relativePath = sourceDir.toURI().relativize(file.toURI()).path
                val size = file.length()
                
                // Upload file to Vercel
                val uploadUrl = "https://api.vercel.com/v2/files"
                val conn = URL(uploadUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("x-vercel-digest", sha)
                conn.setRequestProperty("Content-Length", size.toString())
                conn.doOutput = true
                
                file.inputStream().use { input ->
                    conn.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                
                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val errMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    return "Error uploading file $relativePath (SHA: $sha) to Vercel: Code $responseCode - $errMsg"
                }
                
                val item = JSONObject().apply {
                    put("file", relativePath)
                    put("sha", sha)
                    put("size", size)
                }
                vercelFilesArray.put(item)
            }
            
            // Create Deployment
            val deployUrl = "https://api.vercel.com/v13/deployments"
            val conn = URL(deployUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val deployBody = JSONObject().apply {
                put("name", "astitva-deployment-" + System.currentTimeMillis())
                put("files", vercelFilesArray)
                put("projectSettings", JSONObject().apply {
                    put("framework", null)
                })
            }
            
            OutputStreamWriter(conn.outputStream).use { it.write(deployBody.toString()) }
            
            val responseCode = conn.responseCode
            return if (responseCode == 200 || responseCode == 201) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResp = JSONObject(resp)
                val url = jsonResp.optString("url", "")
                "Deployment Successful!\nURL: https://$url\nID: ${jsonResp.optString("id", "")}"
            } else {
                val errMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                "Deployment Failed on Vercel: Code $responseCode - $errMsg"
            }
        } catch (e: Exception) {
            return "Deployment Exception: ${e.message}"
        }
    }

    fun deployToNetlify(projectPath: String, token: String, siteId: String? = null): String {
        try {
            val sourceDir = File(projectPath)
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                return "Error: Project path $projectPath does not exist or is not a directory."
            }
            
            var resolvedSiteId = siteId?.trim() ?: ""
            var siteUrl = ""
            
            // Create a site if no siteId is provided
            if (resolvedSiteId.isEmpty()) {
                val createUrl = "https://api.netlify.com/api/v1/sites"
                val conn = URL(createUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                OutputStreamWriter(conn.outputStream).use { it.write("{}") }
                
                val responseCode = conn.responseCode
                if (responseCode == 200 || responseCode == 201) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResp = JSONObject(resp)
                    resolvedSiteId = jsonResp.getString("id")
                    siteUrl = jsonResp.optString("ssl_url", jsonResp.optString("url", ""))
                } else {
                    val errMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    return "Error creating site on Netlify: Code $responseCode - $errMsg"
                }
            }
            
            // Zip the directory
            val tempZip = File(sourceDir.parentFile, "deploy_temp_${System.currentTimeMillis()}.zip")
            zipDirectory(projectPath, tempZip.absolutePath)
            
            if (!tempZip.exists() || tempZip.length() == 0L) {
                return "Error: Zipped project file is empty or could not be created."
            }
            
            // Deploy the ZIP
            val deployUrl = "https://api.netlify.com/api/v1/sites/$resolvedSiteId/deploys"
            val conn = URL(deployUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/zip")
            conn.setRequestProperty("Content-Length", tempZip.length().toString())
            conn.doOutput = true
            
            tempZip.inputStream().use { input ->
                conn.outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            
            val responseCode = conn.responseCode
            // Cleanup temp zip
            tempZip.delete()
            
            return if (responseCode == 200 || responseCode == 201) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResp = JSONObject(resp)
                val deployUrlResult = jsonResp.optString("ssl_url", jsonResp.optString("url", siteUrl))
                "Netlify Deployment Successful!\nURL: $deployUrlResult\nSite ID: $resolvedSiteId"
            } else {
                val errMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                "Netlify Deployment Failed: Code $responseCode - $errMsg"
            }
        } catch (e: Exception) {
            return "Netlify Deployment Exception: ${e.message}"
        }
    }

    fun createGitHubRepo(repoName: String, token: String): String {
        try {
            val url = "https://api.github.com/user/repos"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("User-Agent", "AstitvaOS")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val body = JSONObject().apply {
                put("name", repoName)
                put("private", false)
            }
            
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val responseCode = conn.responseCode
            return if (responseCode == 201) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                "GitHub Repo Created Successfully!\nClone URL: ${json.getString("clone_url")}\nHTML URL: ${json.getString("html_url")}"
            } else {
                val errMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                "Failed to create GitHub Repo: Code $responseCode - $errMsg"
            }
        } catch (e: Exception) {
            return "GitHub Create Repo Exception: ${e.message}"
        }
    }

    fun commitToGitHub(repoWithOwner: String, path: String, content: String, token: String): String {
        try {
            // Check if file exists to get SHA
            val checkUrl = "https://api.github.com/repos/$repoWithOwner/contents/$path"
            var sha: String? = null
            
            try {
                val checkConn = URL(checkUrl).openConnection() as HttpURLConnection
                checkConn.requestMethod = "GET"
                checkConn.setRequestProperty("Authorization", "token $token")
                checkConn.setRequestProperty("User-Agent", "AstitvaOS")
                
                if (checkConn.responseCode == 200) {
                    val resp = checkConn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(resp)
                    sha = json.getString("sha")
                }
            } catch (e: Exception) {
                // File probably doesn't exist yet, which is fine
            }
            
            // Commit file
            val commitConn = URL(checkUrl).openConnection() as HttpURLConnection
            commitConn.requestMethod = "PUT"
            commitConn.setRequestProperty("Authorization", "token $token")
            commitConn.setRequestProperty("User-Agent", "AstitvaOS")
            commitConn.setRequestProperty("Content-Type", "application/json")
            commitConn.doOutput = true
            
            val base64Content = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
            
            val body = JSONObject().apply {
                put("message", "Updated $path via Astitva OS")
                put("content", base64Content)
                if (sha != null) {
                    put("sha", sha)
                }
            }
            
            OutputStreamWriter(commitConn.outputStream).use { it.write(body.toString()) }
            val responseCode = commitConn.responseCode
            return if (responseCode == 200 || responseCode == 201) {
                "GitHub Commit Successful for $path!"
            } else {
                val errMsg = commitConn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                "Failed GitHub Commit: Code $responseCode - $errMsg"
            }
        } catch (e: Exception) {
            return "GitHub Commit Exception: ${e.message}"
        }
    }

    fun getFigmaFile(fileKey: String, token: String): String {
        try {
            val url = "https://api.figma.com/v1/files/$fileKey"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Figma-Token", token)
            
            val responseCode = conn.responseCode
            return if (responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                if (resp.length > 5000) resp.take(5000) + "\n... (Truncated Figma JSON)" else resp
            } else {
                val errMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                "Failed to fetch Figma file: Code $responseCode - $errMsg"
            }
        } catch (e: Exception) {
            return "Figma Fetch Exception: ${e.message}"
        }
    }

    fun executeMcpRequest(serverUrl: String, method: String, paramsJson: String): String {
        try {
            val conn = URL(serverUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", JSONObject(paramsJson))
                put("id", 1)
            }
            
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val responseCode = conn.responseCode
            return if (responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                "MCP Call Failed: Code $responseCode - $errMsg"
            }
        } catch (e: Exception) {
            return "MCP Call Exception: ${e.message}"
        }
    }

    fun getActionMap(context: Context): Map<String, (String) -> String> {
        return mapOf(
            "tap" to { params ->
                val p = params.split(",")
                if (p.size >= 2) { RootMotor.tap(p[0].trim().toInt(), p[1].trim().toInt()); "Tapped $params" }
                else "Error: Tap needs x,y"
            },
            "type" to { params -> executeShell("input text \"$params\""); "Typed: $params" },
            "scan" to { params -> executeSecurityTool("nmap", params, "-sV") },
            "exploit" to { params ->
                val parts = params.split(";")
                if (parts.size >= 2) executeSecurityTool("metasploit", parts[1], parts[0])
                else "Error: Need 'module;target'"
            },
            "intercept" to { params -> executeSecurityTool("mitmproxy", "", params) },
            "install_tools" to { _ -> installSecuritySuite() },
            "describe_scene" to { params -> captureCameraImage(context, params.toIntOrNull() ?: 0) },
            "analyze_object" to { params -> captureCameraImage(context, 0) + " | Analyzing object..." },
            "open_app" to { params -> findPackageAndOpen(context, params) },
            "read_sms" to { _ -> readLatestSms(context) },
            "get_location" to { _ -> getCurrentLocation(context) },
            "execute_code" to { params -> 
                val parts = params.split("|", limit = 2)
                if (parts.size >= 2) executeCode(parts[0], parts[1]) else "Error: Need language|code"
            },
            "deploy_vercel" to { params ->
                val prefs = context.getSharedPreferences("AstitvaConfig", Context.MODE_PRIVATE)
                val token = prefs.getString("vercel_token", "") ?: ""
                if (token.isEmpty()) "Error: Vercel Token is not configured. Please open SETTINGS."
                else deployToVercel(params, token)
            },
            "deploy_netlify" to { params ->
                val prefs = context.getSharedPreferences("AstitvaConfig", Context.MODE_PRIVATE)
                val token = prefs.getString("netlify_token", "") ?: ""
                val siteId = prefs.getString("netlify_site_id", "") ?: ""
                if (token.isEmpty()) "Error: Netlify Token is not configured. Please open SETTINGS."
                else deployToNetlify(params, token, siteId.ifEmpty { null })
            },
            "github_create_repo" to { params ->
                val prefs = context.getSharedPreferences("AstitvaConfig", Context.MODE_PRIVATE)
                val token = prefs.getString("github_token", "") ?: ""
                if (token.isEmpty()) "Error: GitHub Token is not configured. Please open SETTINGS."
                else createGitHubRepo(params, token)
            },
            "github_commit" to { params ->
                val prefs = context.getSharedPreferences("AstitvaConfig", Context.MODE_PRIVATE)
                val token = prefs.getString("github_token", "") ?: ""
                if (token.isEmpty()) "Error: GitHub Token is not configured. Please open SETTINGS."
                else {
                    val p = params.split("|", limit = 3)
                    if (p.size >= 3) commitToGitHub(p[0], p[1], p[2], token)
                    else "Error: Invalid Format. Use: repoWithOwner|path|content"
                }
            },
            "figma_get_file" to { params ->
                val prefs = context.getSharedPreferences("AstitvaConfig", Context.MODE_PRIVATE)
                val token = prefs.getString("figma_token", "") ?: ""
                if (token.isEmpty()) "Error: Figma Token is not configured. Please open SETTINGS."
                else getFigmaFile(params, token)
            },
            "call_mcp" to { params ->
                val p = params.split("|", limit = 3)
                if (p.size >= 3) executeMcpRequest(p[0], p[1], p[2])
                else "Error: Invalid Format. Use: serverUrl|method|paramsJson"
            },
            "chrome_get_dom" to { _ -> chromeGetDom() },
            "chrome_click" to { params ->
                val p = params.split(",")
                if (p.size >= 2) chromeClick(p[0].trim(), p[1].trim())
                else "Error: chrome_click needs x,y"
            },
            "chrome_type" to { params ->
                val p = params.split("|", limit = 2)
                if (p.size >= 2) chromeType(p[0].trim(), p[1].trim())
                else "Error: chrome_type needs selector|text"
            },
            "chrome_eval" to { params -> chromeEval(params) },
            "chrome_list_tabs" to { _ -> chromeListTabs() },
            "kali_exec" to { params -> executeKali(params) },
            "execute_astitva_shell" to { params ->
                executeAstitvaShell(context, params)
            }
        )
    }
}