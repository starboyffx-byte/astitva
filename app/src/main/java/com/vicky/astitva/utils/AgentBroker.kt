package com.vicky.astitva.utils

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pub-Sub communication broker for inter-agent messaging.
 */
object AgentBroker {
    
    interface AgentListener {
        fun onMessageReceived(topic: String, message: String, sender: String)
    }
    
    private val listeners = CopyOnWriteArrayList<AgentListener>()
    private val handler = Handler(Looper.getMainLooper())
    
    fun subscribe(listener: AgentListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    fun unsubscribe(listener: AgentListener) {
        listeners.remove(listener)
    }
    
    fun publish(topic: String, message: String, sender: String = "System") {
        handler.post {
            for (listener in listeners) {
                listener.onMessageReceived(topic, message, sender)
            }
        }
    }
}