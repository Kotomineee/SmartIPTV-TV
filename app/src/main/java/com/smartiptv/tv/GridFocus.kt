package com.smartiptv.tv

import android.view.View

/** Adapter ranges containing cards, excluding full-width section headings. */
data class FocusSection(val first: Int, val count: Int)

object GridFocus {
    fun target(position: Int, columns: Int, sections: List<FocusSection>, direction: Int): Int? {
        if (columns < 1) return null
        val groups = sections.filter { it.count > 0 }
        val groupIndex = groups.indexOfFirst { position >= it.first && position - it.first < it.count }
        if (groupIndex < 0) return null
        val group = groups[groupIndex]
        val index = position - group.first
        val column = index % columns
        val row = index / columns
        return when (direction) {
            View.FOCUS_LEFT -> if (column > 0) position - 1 else null
            View.FOCUS_RIGHT -> if (column + 1 < columns && index + 1 < group.count) position + 1 else null
            View.FOCUS_UP -> if (row > 0) position - columns else groups.getOrNull(groupIndex - 1)?.let {
                it.first + minOf((it.count - 1) / columns * columns + column, it.count - 1)
            }
            View.FOCUS_DOWN -> if ((row + 1) * columns < group.count) group.first + minOf((row + 1) * columns + column, group.count - 1)
                else groups.getOrNull(groupIndex + 1)?.let { it.first + minOf(column, it.count - 1) }
            else -> null
        }
    }
}
