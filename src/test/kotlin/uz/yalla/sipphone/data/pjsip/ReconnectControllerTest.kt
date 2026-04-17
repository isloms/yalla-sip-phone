package uz.yalla.sipphone.data.pjsip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectControllerTest {

    @Test
    fun `start invokes attempt once when it succeeds immediately`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val attempts = mutableListOf<Int>()
        val controller = ReconnectController(CoroutineScope(dispatcher)) { a, _ -> attempts.add(a) }
        var invocations = 0

        controller.start {
            invocations++
            Result.success(Unit)
        }
        advanceUntilIdle()

        assertEquals(1, invocations)
        assertEquals(listOf(1), attempts)
    }

    @Test
    fun `failed attempts retry with increasing backoff until success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val attempts = mutableListOf<Pair<Int, Long>>()
        val controller = ReconnectController(CoroutineScope(dispatcher)) { a, b -> attempts.add(a to b) }
        var count = 0

        controller.start {
            count++
            if (count >= 3) Result.success(Unit) else Result.failure(RuntimeException("fail"))
        }
        advanceUntilIdle()

        assertEquals(3, count)
        assertEquals(3, attempts.size)
        assertTrue(attempts[1].second > attempts[0].second)
        assertTrue(attempts[2].second > attempts[1].second)
    }

    @Test
    fun `stop cancels pending retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = ReconnectController(CoroutineScope(dispatcher)) { _, _ -> }
        var count = 0

        controller.start {
            count++
            Result.failure(RuntimeException("fail"))
        }
        advanceTimeBy(500)
        controller.stop()
        advanceUntilIdle()

        assertTrue(count <= 1, "expected at most 1 attempt before stop; got $count")
    }

    @Test
    fun `second start while running does not create a second loop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val attempts = mutableListOf<Int>()
        val controller = ReconnectController(CoroutineScope(dispatcher)) { a, _ -> attempts.add(a) }

        controller.start { Result.failure(RuntimeException("fail")) }
        controller.start { Result.failure(RuntimeException("fail")) }
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertEquals(listOf(1), attempts)
    }

    @Test
    fun `stop resets attempt counter so next start begins at 1`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val attempts = mutableListOf<Int>()
        val controller = ReconnectController(CoroutineScope(dispatcher)) { a, _ -> attempts.add(a) }

        controller.start { Result.failure(RuntimeException("fail")) }
        advanceTimeBy(5_000)
        controller.stop()
        attempts.clear()
        controller.start { Result.success(Unit) }
        advanceUntilIdle()

        assertEquals(listOf(1), attempts)
    }
}
