package io.andchamber.app.testhost

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Target-process host only; deliberately absent from release variants. */
class WebViewRegressionActivity : Activity() {
    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }
}
