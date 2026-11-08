package com.uvk1.map

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Pad chrome around the system status / navigation bars (edge-to-edge). */
fun View.applySystemBarInsets(
    top: Boolean = false,
    bottom: Boolean = false,
    horizontal: Boolean = true,
) {
    val baseL = paddingLeft
    val baseT = paddingTop
    val baseR = paddingRight
    val baseB = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(
            left = if (horizontal) baseL + bars.left else baseL,
            top = if (top) baseT + bars.top else baseT,
            right = if (horizontal) baseR + bars.right else baseR,
            bottom = if (bottom) baseB + bars.bottom else baseB,
        )
        insets
    }
    requestApplyInsets()
}

fun View.applyBottomInsetAsMargin() {
    val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val baseBottom = lp.bottomMargin
    val baseLeft = lp.leftMargin
    val baseRight = lp.rightMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val m = v.layoutParams as ViewGroup.MarginLayoutParams
        m.leftMargin = baseLeft + bars.left
        m.rightMargin = baseRight + bars.right
        m.bottomMargin = baseBottom + bars.bottom
        v.layoutParams = m
        insets
    }
    requestApplyInsets()
}
