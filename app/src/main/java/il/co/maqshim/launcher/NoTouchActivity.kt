package il.co.maqshim.launcher

import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity

/**
 * Base class for every screen: keypad only, no touch and no transition animations.
 */
abstract class NoTouchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        disableTransitions()
    }

    override fun finish() {
        super.finish()
        disableTransitions()
    }

    @Suppress("DEPRECATION")
    protected fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    // Touch is swallowed completely: no taps, no swipes, no scrolling by finger, no mouse.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = true
    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean = true
    override fun onTouchEvent(event: MotionEvent?): Boolean = true
}
