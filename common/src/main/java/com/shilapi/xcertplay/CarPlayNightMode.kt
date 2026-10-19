package com.shilapi.xcertplay

import java.util.Calendar

/** Stable preference values; never persist enum ordinals. */
enum class CarPlayNightMode(val key: String) {
    SYSTEM("system"),
    AMBIENT("ambient"),
    DAY("day"),
    NIGHT("night"),
    SCHEDULE("schedule");

    companion object {
        fun fromKey(key: String?): CarPlayNightMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

data class CarPlayNightSchedule(val startMinute: Int = 18 * 60, val endMinute: Int = 6 * 60) {
    init {
        require(startMinute in 0 until 24 * 60 && endMinute in 0 until 24 * 60)
    }

    fun isNight(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}

internal data class NightTimeSnapshot(val minuteOfDay: Int, val millisUntilNextMinute: Long)

internal interface NightTimeSource {
    fun snapshot(): NightTimeSnapshot
}

internal object SystemNightTimeSource : NightTimeSource {
    override fun snapshot(): NightTimeSnapshot = Calendar.getInstance().let {
        // Appearance and the next tick must use the same instant across a minute boundary.
        NightTimeSnapshot(
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE),
            60_000L - it.get(Calendar.SECOND) * 1_000L - it.get(Calendar.MILLISECOND),
        )
    }
}

/** Main-thread controller. Only its output is sent to CarPlay; Android theme is never changed. */
internal class CarPlayNightModeController(
    private val light: LightSource,
    private val scheduler: Scheduler,
    initialNight: Boolean,
    private val onNightChanged: (Boolean) -> Unit,
    private val timeSource: NightTimeSource = SystemNightTimeSource,
) {
    interface LightSource {
        val available: Boolean
        fun start(onLux: (Float) -> Unit): Boolean
        fun stop()
    }

    interface Scheduler {
        fun postDelayed(task: Runnable, delayMillis: Long)
        fun remove(task: Runnable)
    }

    var night: Boolean = initialNight
        private set
    private var mode = CarPlayNightMode.SYSTEM
    private var systemNight = initialNight
    private var threshold = AmbientLightThreshold()
    private var delaySeconds = 2
    private var schedule = CarPlayNightSchedule()
    private var resumed = false
    private var listening = false
    private var ambientHasUsableReading = false
    private var usingAmbientFallback = false
    private var pending: Boolean? = null
    private val transition = Runnable {
        val target = pending
        pending = null
        if (resumed && listening && mode == CarPlayNightMode.AMBIENT && target != null) {
            applyNight(target)
        }
    }
    private val ambientFallback = Runnable {
        if (!resumed || mode != CarPlayNightMode.AMBIENT || ambientHasUsableReading) return@Runnable
        usingAmbientFallback = true
        applyAmbientFallback()
    }
    private val scheduleTick = object : Runnable {
        override fun run() {
            when {
                !resumed -> return
                mode == CarPlayNightMode.SCHEDULE -> applySchedule()
                mode == CarPlayNightMode.AMBIENT && usingAmbientFallback -> applyAmbientFallback()
            }
        }
    }

    fun configure(
        mode: CarPlayNightMode,
        systemNight: Boolean,
        threshold: AmbientLightThreshold = AmbientLightThreshold(),
        delaySeconds: Int = 2,
        schedule: CarPlayNightSchedule = CarPlayNightSchedule(),
    ) {
        stopListening()
        this.mode = mode
        this.systemNight = systemNight
        this.threshold = threshold
        this.delaySeconds = delaySeconds.coerceIn(0, 60)
        this.schedule = schedule
        applyMode()
    }

    fun resume(systemNight: Boolean) {
        this.systemNight = systemNight
        resumed = true
        applyMode()
    }

    fun pause() {
        resumed = false
        stopListening()
    }

    fun systemChanged(night: Boolean) {
        systemNight = night
        when {
            mode == CarPlayNightMode.SYSTEM -> applyNight(night)
            mode == CarPlayNightMode.AMBIENT &&
                (!light.available || (resumed && !listening)) -> beginAmbientFallback()
        }
    }

    private fun applyMode() {
        when (mode) {
            CarPlayNightMode.SYSTEM -> applyNight(systemNight)
            CarPlayNightMode.DAY -> applyNight(false)
            CarPlayNightMode.NIGHT -> applyNight(true)
            CarPlayNightMode.SCHEDULE -> applySchedule()
            CarPlayNightMode.AMBIENT -> {
                if (!light.available) {
                    beginAmbientFallback()
                } else if (resumed && !listening) {
                    listening = light.start(::onLux)
                    if (!listening) {
                        light.stop()
                        beginAmbientFallback()
                    } else {
                        scheduler.remove(ambientFallback)
                        scheduler.postDelayed(ambientFallback, AMBIENT_FALLBACK_GRACE_MILLIS)
                    }
                }
            }
        }
    }

    private fun onLux(lux: Float) {
        if (!resumed || !listening || mode != CarPlayNightMode.AMBIENT) return
        if (!lux.isFinite() || lux < 0f) {
            cancelPending()
            return
        }
        // Some Android 8 head units expose an on-change sensor that never emits a first sample.
        // Ignore a zero-only stream until the sensor proves it can produce a real reading.
        if (lux == 0f && !ambientHasUsableReading) {
            cancelPending()
            return
        }
        ambientHasUsableReading = true
        usingAmbientFallback = false
        scheduler.remove(ambientFallback)
        scheduler.remove(scheduleTick)
        val target = when {
            !night && lux < threshold.lux.toFloat() -> true
            night && lux >= threshold.lux.toFloat() -> false
            else -> null
        }
        if (target == pending) return
        cancelPending()
        pending = target
        // TYPE_LIGHT is commonly on-change: a stable reading need not emit again.
        if (target != null) {
            if (delaySeconds == 0) transition.run()
            else scheduler.postDelayed(transition, delaySeconds * 1_000L)
        }
    }

    private fun stopListening() {
        if (listening) light.stop()
        listening = false
        ambientHasUsableReading = false
        usingAmbientFallback = false
        cancelPending()
        scheduler.remove(ambientFallback)
        scheduler.remove(scheduleTick)
    }

    private fun applySchedule() {
        val time = timeSource.snapshot()
        applyNight(schedule.isNight(time.minuteOfDay))
        if (!resumed || mode != CarPlayNightMode.SCHEDULE) return
        scheduler.remove(scheduleTick)
        scheduler.postDelayed(scheduleTick, time.millisUntilNextMinute.coerceIn(1, 60_000))
    }

    private fun beginAmbientFallback() {
        scheduler.remove(ambientFallback)
        usingAmbientFallback = true
        applyAmbientFallback()
    }

    private fun applyAmbientFallback() {
        val time = timeSource.snapshot()
        applyNight(schedule.isNight(time.minuteOfDay))
        if (!resumed || mode != CarPlayNightMode.AMBIENT || ambientHasUsableReading) return
        scheduler.remove(scheduleTick)
        scheduler.postDelayed(scheduleTick, time.millisUntilNextMinute.coerceIn(1, 60_000))
    }

    private fun cancelPending() {
        scheduler.remove(transition)
        pending = null
    }

    private fun applyNight(value: Boolean) {
        if (value == night) return
        night = value
        onNightChanged(value)
    }

    private companion object {
        const val AMBIENT_FALLBACK_GRACE_MILLIS = 1_500L
    }
}
