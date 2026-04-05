package uz.yalla.sipphone.testing.scenario

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Models the ebb-and-flow of a real call center's traffic:
 * bursts of rapid calls, normal breathing periods, and quiet lulls.
 */
enum class TrafficPhase(val weight: Int) {
    /** High-volume period: short inter-call gaps, typical of morning rush. */
    BURST(40),
    /** Normal traffic: moderate gaps between calls. */
    BREATHE(45),
    /** Quiet period: longer gaps, end-of-shift feel. */
    LULL(15),
}

/**
 * The 13 real-world scenario types an Oktell operator encounters,
 * weighted by observed production frequency.
 */
enum class ScenarioType(val weight: Int) {
    /** Standard call where the agent ends it. */
    NORMAL_AGENT_HANGUP(40),
    /** Standard call where the caller ends it. */
    NORMAL_CALLER_HANGUP(15),
    /** Caller hangs up during ring, before agent answers. */
    CALLER_ABANDON(12),
    /** Agent puts caller on hold, then resumes. */
    HOLD_RESUME(8),
    /** Agent mutes during the call. */
    MUTE_UNMUTE(5),
    /** Agent transfers the call to another extension. */
    TRANSFER(5),
    /** Short call — under 10 seconds talk time. */
    SHORT_CALL(4),
    /** Long call — over 2 minutes talk time. */
    LONG_CALL(3),
    /** Call to a busy extension (486 Busy Here). */
    BUSY_CALLEE(2),
    /** Call that times out with no answer (408). */
    NO_ANSWER_TIMEOUT(2),
    /** DTMF menu navigation (IVR). */
    DTMF_NAVIGATION(2),
    /** Back-to-back rapid calls with no break. */
    RAPID_FIRE(1),
    /** Network blip causes call drop mid-conversation. */
    NETWORK_DROP(1),
}

/**
 * Generates realistic random durations and scenario picks for
 * stress-testing the SIP phone under simulated operator load.
 *
 * All randomness is seeded through [random] for reproducible tests.
 */
class TrafficPattern(val random: Random = Random.Default) {

    private var currentPhase: TrafficPhase = pickPhase()

    /**
     * Pick the next traffic phase based on weights.
     */
    private fun pickPhase(): TrafficPhase =
        weightedPick(TrafficPhase.entries, TrafficPhase::weight)

    /**
     * Duration to wait between consecutive calls.
     * Varies by current [TrafficPhase].
     */
    fun nextInterCallGap(): Duration {
        // Occasionally shift phase
        if (random.nextInt(100) < 20) {
            currentPhase = pickPhase()
        }
        return when (currentPhase) {
            TrafficPhase.BURST -> randomDuration(200.milliseconds, 2.seconds)
            TrafficPhase.BREATHE -> randomDuration(3.seconds, 10.seconds)
            TrafficPhase.LULL -> randomDuration(15.seconds, 45.seconds)
        }
    }

    /**
     * How long a call's talk/active phase lasts.
     * Weighted toward 15-60 second calls with occasional outliers.
     */
    fun nextTalkDuration(): Duration {
        val bucket = random.nextInt(100)
        return when {
            bucket < 10 -> randomDuration(2.seconds, 8.seconds)       // very short
            bucket < 60 -> randomDuration(15.seconds, 60.seconds)     // normal
            bucket < 85 -> randomDuration(60.seconds, 120.seconds)    // medium-long
            else -> randomDuration(120.seconds, 300.seconds)          // long
        }
    }

    /**
     * How long the phone rings before something happens
     * (answer, abandon, timeout).
     */
    fun nextRingDuration(): Duration {
        val bucket = random.nextInt(100)
        return when {
            bucket < 30 -> randomDuration(1.seconds, 3.seconds)      // fast pickup
            bucket < 70 -> randomDuration(3.seconds, 8.seconds)      // normal
            bucket < 90 -> randomDuration(8.seconds, 15.seconds)     // slow
            else -> randomDuration(15.seconds, 30.seconds)           // very slow
        }
    }

    /**
     * Pick the next scenario type based on production frequency weights.
     */
    fun nextScenarioType(): ScenarioType =
        weightedPick(ScenarioType.entries, ScenarioType::weight)

    /**
     * Generate a random duration uniformly distributed in [min, max].
     */
    private fun randomDuration(min: Duration, max: Duration): Duration {
        val minMs = min.inWholeMilliseconds
        val maxMs = max.inWholeMilliseconds
        return random.nextLong(minMs, maxMs + 1).milliseconds
    }

    /**
     * Weighted random pick from a list using the given weight extractor.
     */
    private fun <T> weightedPick(items: List<T>, weight: (T) -> Int): T {
        val totalWeight = items.sumOf { weight(it) }
        var roll = random.nextInt(totalWeight)
        for (item in items) {
            roll -= weight(item)
            if (roll < 0) return item
        }
        return items.last()
    }
}
