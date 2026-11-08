package com.uvk1.map

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory journal shared between the map and the Logs screen. */
object SessionLog {
    private const val MAX_STATUS = 200
    private const val MAX_TARGETS = 80

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private val statusLines = CopyOnWriteArrayList<String>()
    private val targetLines = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addStatus(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        statusLines.add(0, line)
        while (statusLines.size > MAX_STATUS) statusLines.removeAt(statusLines.lastIndex)
        notifyChanged()
    }

    fun addTarget(line: String) {
        targetLines.add(0, line)
        while (targetLines.size > MAX_TARGETS) targetLines.removeAt(targetLines.lastIndex)
        notifyChanged()
    }

    fun clear() {
        statusLines.clear()
        targetLines.clear()
        notifyChanged()
    }

    fun statusText(): String = statusLines.joinToString("\n").ifBlank { "" }

    fun targetsText(): String = targetLines.joinToString("\n").ifBlank { "" }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (l in listeners) {
            runCatching { l.invoke() }
        }
    }
}
