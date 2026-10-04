package io.github.proify.lyricon.statusbarlyric

import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.proify.lyricon.lyric.style.BasicStyle
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.lyric.style.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "port-mdpi")
class CapsuleWidthTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun style() = LyricStyle(BasicStyle(
        width = 220f, widthInLand = 300f,
        widthInColorOSCapsuleMode = 140f, widthInColorOSCapsuleModeLand = 160f
    ))

    private fun layout(parent: ViewGroup) {
        parent.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 1000, 100)
    }

    @Test fun clockFrameLayoutKeepsItsParamsAndActuallyMeasuresTheNewWidth() {
        val lyric = StatusBarLyric(context, style(), null)
        val parent = FrameLayout(context)
        val params = FrameLayout.LayoutParams(220, 100, Gravity.CENTER_VERTICAL)
        parent.addView(lyric, params)
        lyric.visibility = View.VISIBLE
        lyric.setOplusCapsuleVisibility(true)
        layout(parent)
        assertSame(params, lyric.layoutParams)
        assertEquals(140, lyric.measuredWidth)
        assertEquals(Gravity.CENTER_VERTICAL, params.gravity)
        assertTrue(lyric.logoView.isOplusCapsuleShowing)
        assertFalse(lyric.isLayoutRequested)
        lyric.setOplusCapsuleVisibility(true)
        assertFalse(lyric.isLayoutRequested)
        lyric.setOplusCapsuleVisibility(false)
        layout(parent)
        assertEquals(220, lyric.measuredWidth)
        assertFalse(lyric.logoView.isOplusCapsuleShowing)
    }

    @Test fun initialReplayAndStyleUpdatesWorkBeforeAndAfterChangingParentType() {
        val style = style()
        val lyric = StatusBarLyric(context, style, null)
        lyric.setOplusCapsuleVisibility(true)
        val linear = LinearLayout(context)
        linear.addView(lyric)
        lyric.visibility = View.VISIBLE
        layout(linear)
        assertEquals(140, lyric.measuredWidth)
        linear.removeView(lyric)
        val frame = FrameLayout(context)
        frame.addView(lyric)
        val params = lyric.layoutParams
        style.basicStyle = style.basicStyle.copy(
            widthInColorOSCapsuleMode = 130f, margins = RectF(2f, 3f, 4f, 5f)
        )
        lyric.updateStyle(style)
        lyric.visibility = View.VISIBLE
        layout(frame)
        assertSame(params, lyric.layoutParams)
        assertEquals(130, lyric.measuredWidth)
        assertEquals(2, (params as FrameLayout.LayoutParams).leftMargin)
        assertEquals(5, params.bottomMargin)
    }

    @Test fun orientationKeepsTheExistingNormalAndCapsuleWidthSettings() {
        val lyric = StatusBarLyric(context, style(), null)
        val parent = FrameLayout(context)
        parent.addView(lyric)
        lyric.visibility = View.VISIBLE
        lyric.setOplusCapsuleVisibility(true)
        RuntimeEnvironment.setQualifiers("land-mdpi")
        lyric.dispatchConfigurationChanged(Configuration(context.resources.configuration))
        layout(parent)
        assertEquals(160, lyric.measuredWidth)
        lyric.setOplusCapsuleVisibility(false)
        layout(parent)
        assertEquals(300, lyric.measuredWidth)
    }
}
