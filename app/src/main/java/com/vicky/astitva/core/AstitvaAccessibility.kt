package com.vicky.astitva.core

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log

class AstitvaAccessibility : AccessibilityService() {

    interface AccessibilityListener {
        fun onUiUpdate()
    }

    companion object {
        var latestUITree: String = ""
        private var instance: AstitvaAccessibility? = null
        private var listener: AccessibilityListener? = null
        private val handler = Handler(Looper.getMainLooper())
        private var debounceRunnable: Runnable? = null

        fun setListener(l: AccessibilityListener?) {
            listener = l
        }

        fun forceDumpUI(): String {
            val root = instance?.rootInActiveWindow
            if (root != null) {
                val sb = StringBuilder()
                sb.append("<ui_tree>")
                dumpNode(root, sb, 0)
                sb.append("\n</ui_tree>")
                latestUITree = sb.toString()
                root.recycle()
                return latestUITree
            }
            return "<ui_tree error=\"Root node unavailable\" />"
        }

        private fun dumpNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int): Boolean {
            if (node == null) return false

            val pkg = node.packageName?.toString() ?: ""
            val cls = node.className?.toString() ?: ""
            val isWeb = pkg.contains("chrome", ignoreCase = true) ||
                        pkg.contains("browser", ignoreCase = true) ||
                        cls.contains("WebView", ignoreCase = true)

            if (!isWeb && !node.isVisibleToUser) return false

            val text = node.text?.toString()?.replace(Regex("[\\s\\n\"]+"), " ")?.trim() ?: ""
            val contentDesc = node.contentDescription?.toString()?.replace(Regex("[\\s\\n\"]+"), " ")?.trim() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val clazz = node.className?.toString()?.substringAfterLast('.') ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val isFocusable = node.isFocusable
            val isLongClickable = node.isLongClickable
            val isCheckable = node.isCheckable

            val isActionableOrInformative = isClickable || isEditable || isScrollable || isFocusable || isLongClickable || isCheckable ||
                                            text.isNotEmpty() || contentDesc.isNotEmpty() || viewId.isNotEmpty()

            val childrenSbs = mutableListOf<String>()
            var hasInterestingChild = false
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val childSb = StringBuilder()
                    val childIsInteresting = dumpNode(child, childSb, if (isActionableOrInformative) depth + 1 else depth)
                    if (childIsInteresting) {
                        hasInterestingChild = true
                        childrenSbs.add(childSb.toString())
                    }
                    child.recycle()
                }
            }

            val isInteresting = isActionableOrInformative || hasInterestingChild

            if (isInteresting) {
                val indent = "  ".repeat(depth)
                if (isActionableOrInformative) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    sb.append("\n${indent}<node class=\"$clazz\" text=\"$text\" desc=\"$contentDesc\" id=\"$viewId\" bounds=\"[${rect.left},${rect.top}][${rect.right},${rect.bottom}]\" clickable=\"$isClickable\" editable=\"$isEditable\">")
                    for (childStr in childrenSbs) {
                        sb.append(childStr)
                    }
                    sb.append("\n${indent}</node>")
                } else {
                    for (childStr in childrenSbs) {
                        sb.append(childStr)
                    }
                }
            }

            return isInteresting
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (listener == null) return

        // Debounce mechanism to avoid flooding with events
        debounceRunnable?.let { handler.removeCallbacks(it) }
        debounceRunnable = Runnable {
            Log.d("AstitvaAccessibility", "UI is stable, notifying listener.")
            listener?.onUiUpdate()
        }
        // Notify after a short period of no new events
        handler.postDelayed(debounceRunnable!!, 350) 
    }

    override fun onInterrupt() {
        Log.e("ASTITVA_UI", "Accessibility Service Interrupted")
        instance = null
        listener = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("ASTITVA_UI", "Accessibility Connected. UI Reading Core Active.")
    }
}

