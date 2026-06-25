package com.vicky.astitva.core

import java.io.DataOutputStream

object RootMotor {
    
    // Executes a root command directly from the Kotlin App
    fun executeRaw(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // High-speed Tap
    fun tap(x: Int, y: Int) {
        executeRaw("input tap $x $y")
    }

    // Long Tap
    fun longTap(x: Int, y: Int, duration: Int = 1000) {
        executeRaw("input swipe $x $y $x $y $duration")
    }

    // Type Text
    fun typeText(text: String) {
        // Replace spaces with %s for 'input text' command
        val formatted = text.replace(" ", "%s")
        executeRaw("input text $formatted")
    }

    // Key Events (Enter, Back, Home etc)
    fun keyEvent(code: Int) {
        executeRaw("input keyevent $code")
    }

    // High-speed Swipe
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 100) {
        executeRaw("input swipe $x1 $y1 $x2 $y2 $duration")
    }
}