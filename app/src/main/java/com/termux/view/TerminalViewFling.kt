@file:JvmName("TerminalViewFling")

package com.termux.view

/**
 * App-side fling for TerminalView's native scrollback.
 *
 * TerminalView's own onFling feeds the pixel velocity (scaled by 0.25) into a
 * Scroller that animates [TerminalView.mTopRow] in row units, so with a
 * typical ~40px row the fling runs an order of magnitude faster than the
 * finger. This reimplements it with the velocity converted to rows per
 * second. It lives in com.termux.view because mTopRow, mScroller and
 * doScroll are package-private (the vendored terminal-view is not modified).
 */

/** Stop an in-progress fling so a new touch takes over immediately. */
fun abortFling(view: TerminalView) {
    view.mScroller.forceFinished(true)
}

/** Start a scrollback fling from [velocityY] (pixels/second, GestureDetector sign). */
fun flingScrollback(view: TerminalView, velocityY: Float) {
    if (view.mEmulator == null) return
    if (!view.mScroller.isFinished()) return
    val rowVelocity = velocityY / view.mRenderer.fontLineSpacing
    view.mScroller.fling(
        0, view.mTopRow,
        0, -rowVelocity.toInt(),
        0, 0,
        -view.mEmulator.screen.activeTranscriptRows, 0,
    )
    view.post(object : Runnable {
        override fun run() {
            val emulator = view.mEmulator
            // The pane switched into an app that owns scrolling itself —
            // mTopRow animation no longer makes sense.
            if (emulator == null || emulator.isMouseTrackingActive || emulator.isAlternateBufferActive) {
                view.mScroller.abortAnimation()
                return
            }
            if (view.mScroller.isFinished()) return
            val more = view.mScroller.computeScrollOffset()
            // null MotionEvent is safe: doScroll only reads it on the
            // mouse-tracking/alt-buffer branches excluded above.
            view.doScroll(null, view.mScroller.currY - view.mTopRow)
            if (more) view.post(this)
        }
    })
}
