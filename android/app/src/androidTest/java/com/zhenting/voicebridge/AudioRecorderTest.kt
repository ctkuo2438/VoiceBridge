package com.zhenting.voicebridge

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.zhenting.voicebridge.data.audio.AndroidAudioRecorder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AudioRecorderTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun nativeRecorderProducesAnAacTrackInAnM4aFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = AndroidAudioRecorder(context)
        val failed = AtomicBoolean(false)
        try {
            recorder.start { failed.set(true) }
            SystemClock.sleep(1500)
            assertFalse(failed.get())
            val file = recorder.stop()
            try {
                assertTrue(file.length() > 0)
                assertEquals("m4a", file.extension)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    assertEquals(1, extractor.trackCount)
                    val format = extractor.getTrackFormat(0)
                    assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
                    assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } finally { extractor.release() }
            } finally { file.delete() }
        } finally { recorder.cancel() }
    }
}
