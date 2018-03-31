package com.blindknot.rockfm

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.os.Bundle
import android.os.AsyncTask
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.google.android.things.contrib.driver.button.Button
import com.google.android.things.contrib.driver.apa102.Apa102
import com.google.android.things.contrib.driver.button.ButtonInputDriver
import com.google.android.things.contrib.driver.ht16k33.AlphanumericDisplay
import com.google.android.things.pio.Gpio
import com.google.android.things.pio.PeripheralManager

class RadioPlayerActivity : Activity() {

    companion object {
        private val TAG = RadioPlayerActivity::class.java.simpleName

        private const val stationStream = "http://rockfm.cope.stream.flumotion.com/cope/rockfm/playlist.m3u8"
        private const val stationName = "ROCK FM"

        private enum class Rpi3(val io: String) {
            BCM6("BCM6"),
            BCM16("BCM16"),
            BCM19("BCM19"),
            BCM20("BCM20"),
            BCM21("BCM21"),
            BCM26("BCM26"),
            I2C1("I2C1"),
            SPI("SPI0.0"),
        }

        private const val displaySize = 4

        private const val ledStripBrightness = 1
        private const val ledStripSize = 7
    }

    private lateinit var buttonVolumeUp: ButtonInputDriver
    private lateinit var buttonVolumeDown: ButtonInputDriver
    private lateinit var buttonSoundMute: ButtonInputDriver

    private lateinit var ledUp: Gpio
    private lateinit var ledDown: Gpio
    private lateinit var ledMute: Gpio

    private var display: AlphanumericDisplay? = null
    private var ledStrip: Apa102? = null

    private lateinit var audio: AudioManager
    private lateinit var mediaPlayer: MediaPlayer
    private var scroller: Scroller? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupInputButtons()
        setupLeds()
        setupDisplay()
        setupLedStrip()

        mediaPlayer = MediaPlayer()
        mediaPlayer.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())

        Player().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, stationStream)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer.release()
        releaseInputButtons()
        releaseLeds()
        releaseDisplay()
        releaseLedStrip()
    }

    /**
     * Handle on button pressed event.
     * Depending on the button it might set the volume up, down or mute.
     *
     * @param keyCode The value in event.getKeyCode()
     * @param event Description of the key event
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_C || keyCode == KeyEvent.KEYCODE_B) {
            var volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (keyCode == KeyEvent.KEYCODE_C && volume < audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) { // Handle volume up
                volume++
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                updateLed(true, ledUp)
                if (volume == 1) handleMuteStates(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                Log.d(TAG, "Volume up: $volume")
            } else if (keyCode == KeyEvent.KEYCODE_B && volume > 0) { // Handle volume down
                volume--
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                updateLed(true, ledDown)
                if (volume == 0) handleMuteStates(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                Log.d(TAG, "Volume down: $volume")
            }
            updateLedStrip(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_A) { // Handle sound mute and un-mute
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            updateLed(true, ledMute)
            handleMuteStates(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            updateLedStrip(audio.getStreamVolume(AudioManager.STREAM_MUSIC),  audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Handle on button released event to restore pre-button pressed states.
     *
     * @param keyCode The value in event.getKeyCode()
     * @param event Description of the key event
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_C -> updateLed(false, ledUp)
            KeyEvent.KEYCODE_B -> updateLed(false, ledDown)
            KeyEvent.KEYCODE_A -> updateLed(false, ledMute)
        }
        return true
    }

    /**
     * Helper method for common operations when handling (un)muted states.
     *
     * @param volume The sound volume value
     */
    private fun handleMuteStates(volume: Int) {
        updateDisplay(if (volume > 0) stationName else "MUTE")
        Log.d(TAG, "${if (volume > 0) "Un-mute" else "Mute"} sound")
    }

    /**
     * Register the GPIO buttons on the Rainbow HAT that generates key press actions.
     */
    private fun setupInputButtons() {
        try {
            buttonVolumeUp = ButtonInputDriver(
                    Rpi3.BCM16.io,
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_C)
            buttonVolumeUp.register()
            buttonVolumeDown = ButtonInputDriver(
                    Rpi3.BCM20.io,
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_B)
            buttonVolumeDown.register()
            buttonSoundMute = ButtonInputDriver(
                    Rpi3.BCM21.io,
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_A)
            buttonSoundMute.register()
            Log.d(TAG, "Initialized GPIO Buttons")
        } catch (e: IOException) {
            throw RuntimeException("Error initializing GPIO buttons", e)
        }
    }

    /**
     * Disable the GPIO buttons.
     */
    private fun releaseInputButtons() {
        try {
            buttonVolumeUp.close()
            buttonVolumeDown.close()
            buttonSoundMute.close()
        } catch (e: IOException) {
            throw RuntimeException("Error releasing GPIO buttons", e)
        }
    }

    /**
     * Setup GPIO leds, button leds on the Rainbow HAT.
     * 
     * TODO: make leds optional
     */
    private fun setupLeds() {
        try {
            val pioService = PeripheralManager.getInstance()

            ledUp = pioService.openGpio(Rpi3.BCM26.io).apply {
                setEdgeTriggerType(Gpio.EDGE_NONE)
                setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW)
                setActiveType(Gpio.ACTIVE_HIGH)
            }

            ledDown = pioService.openGpio(Rpi3.BCM19.io).apply {
                setEdgeTriggerType(Gpio.EDGE_NONE)
                setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW)
                setActiveType(Gpio.ACTIVE_HIGH)
            }

            ledMute = pioService.openGpio(Rpi3.BCM6.io).apply {
                setEdgeTriggerType(Gpio.EDGE_NONE)
                setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW)
                setActiveType(Gpio.ACTIVE_HIGH)
            }

            Log.d(TAG, "Initialized GPIO leds")
        } catch (e: IOException) {
            throw RuntimeException("Error initializing leds", e)
        }
    }

    /**
     * Disable the GPIO leds.
     */
    private fun releaseLeds() {
        try {
            ledUp.value = false
            ledUp.close()
            ledDown.value = false
            ledDown.close()
            ledMute.value = false
            ledMute.close()
        } catch (e: IOException) {
            throw RuntimeException("Error releasing leds", e)
        }
    }

    /**
     * Setup I2C1 display, 4 character digital display on the Rainbow HAT.
     */
    private fun setupDisplay() {
        try {
            display = AlphanumericDisplay(Rpi3.I2C1.io)
            display?.let {
                it.setEnabled(true)
                updateDisplay(stationName)
            }
            Log.d(TAG, "Initialized I2C Display")
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing I2C display", e)
            Log.d(TAG, "Display disabled")
            display = null // Display is optional
        }
    }

    /**
     * Disable the I2C1 display.
     */
    private fun releaseDisplay() {
        display?.let {
            try {
                it.clear()
                it.setEnabled(false)
                it.close()
            } catch (e: IOException) {
                throw RuntimeException("Error releasing I2C display", e)
            }
        }
    }

    /**
     * Setup the SPI led strip, 7 led strip on the Rainbow HAT.
     */
    private fun setupLedStrip() {
        try {
            ledStrip = Apa102(Rpi3.SPI.io, Apa102.Mode.BGR)
            ledStrip?.brightness = ledStripBrightness
            updateLedStrip(audio.getStreamVolume(AudioManager.STREAM_MUSIC),  audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            Log.d(TAG, "Initialized SPI led strip")
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing SPI led strip", e)
            Log.d(TAG, "Led strip disabled")
            ledStrip = null // Led strip is optional
        }
    }

    /**
     * Disable the SPI led strip.
     */
    private fun releaseLedStrip() {
        ledStrip?.let {
            try {
                it.brightness = 0
                it.write(IntArray(ledStripSize))
                it.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error releasing SPI led strip", e)
            }
        }
    }

    /**
     * Update GPIO led state.
     *
     * @param state Boolean signaling whether the led is on (true) or off (false)
     * @param led Which led instance to use
     */
    private fun updateLed(state: Boolean, led: Gpio) {
        try {
            led.value = state
        } catch (e: IOException) {
            Log.e(TAG, "Error updating LED", e)
        }
    }

    /**
     * Update the I2C1 display text. It will apply a text scroll effect if text is larger than
     * display size.
     *
     * @param text Test to display
     */
    private fun updateDisplay(text: String) {
        display?.let {
            it.clear()
            scroller?.cancel (true)
            if (text.length > displaySize) {
                scroller = Scroller()
                scroller?.execute(text)
            } else {
                it.display(text)
            }
        }
    }

    /**
     * Display sound volume information on the led strip.
     *
     * @param value Value to be represented on the led strip
     * @param maxValue Max value supported
     */
    private fun updateLedStrip(value: Int, maxValue: Int) {
        ledStrip?.let {
            val affectedLeds = value.toFloat().div(maxValue.toFloat()).times(ledStripSize).toInt()
            val rainbow = IntArray(ledStripSize, { _ -> 0 })
            for (i in ledStripSize.minus(affectedLeds) until rainbow.size) {
                val hsv = floatArrayOf(i * 360f / rainbow.size, 1.0f, 1.0f)
                rainbow[i] = Color.HSVToColor(255, hsv)
            }
            it.write(rainbow)
        }
    }

    /**
     * The text scroller class as an async task.
     */
    private inner class Scroller : AsyncTask<String, Void, Boolean>() {
        override fun doInBackground(vararg strings: String): Boolean? {
            val scrollText = "   ${strings[0]} "
            while (true) {
                for (i in 0 until scrollText.length.minus(1)) {
                    display?.display(scrollText.substring(i))
                    TimeUnit.MILLISECONDS.sleep(400)
                }
            }
        }
    }

    /**
     * The media player class as an async task.
     *
     * TODO: Attempt to reconnect if stream or connection fail
     */
    private inner class Player : AsyncTask<String, Void, Boolean>() {
        override fun doInBackground(vararg strings: String): Boolean? {
            try {
                mediaPlayer.setDataSource(strings[0])
                mediaPlayer.setOnPreparedListener {
                    it.start()
                }
                mediaPlayer.setOnCompletionListener {
                    it.stop()
                    it.reset()
                }
                mediaPlayer.prepare()
            } catch (e: Exception) {
                Log.e(TAG, e.message)
                return false
            }

            return true
        }

        override fun onPostExecute(aBoolean: Boolean?) {
            super.onPostExecute(aBoolean)
            updateDisplay(stationName)
        }

        override fun onPreExecute() {
            super.onPreExecute()
            updateDisplay("WAIT")
        }
    }

}
