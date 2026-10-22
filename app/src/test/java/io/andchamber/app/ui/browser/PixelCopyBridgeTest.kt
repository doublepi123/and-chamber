package io.andchamber.app.ui.browser

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelCopyBridgeTest {
    private class FakeResource {
        var disposed = 0
            private set

        fun dispose() {
            disposed += 1
        }
    }

    private val settled = HashSet<FakeResource>()

    private fun settle(
        resource: FakeResource,
        success: Boolean,
        current: Boolean,
        deliver: (FakeResource?) -> Unit,
    ) = settleOwnedCopy(
        resource,
        success,
        current,
        deliver,
        FakeResource::dispose,
        alreadySettled = { it in settled },
        markSettled = { settled += it },
    )

    @Test fun `cancel before the callback leaves disposal to that callback`() = runTest {
        val started = CompletableDeferred<Unit>()
        lateinit var deliver: (FakeResource?) -> Unit
        val job = launch {
            awaitOwnedResult<FakeResource>(
                start = {
                    deliver = it
                    started.complete(Unit)
                },
                dispose = { it?.dispose() },
            )
        }
        started.await()
        job.cancel()
        val resource = FakeResource()
        settle(resource, success = false, current = true, deliver)
        assertEquals(1, resource.disposed)
    }

    @Test fun `a resumed value cancelled before it is read is disposed`() = runTest {
        val resource = FakeResource()
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOwnedResult(
                start = { deliver ->
                    coroutineContext[Job]!!.cancel()
                    settle(resource, success = true, current = true, deliver)
                },
                dispose = { it?.dispose() },
            )
        }
        owner.join()
        assertEquals(1, resource.disposed)
        assertTrue(owner.isCancelled)
    }

    @Test fun `the caller owns one dispose of a successful current copy`() = runTest {
        val resource = FakeResource()
        val owned = awaitOwnedResult(
            start = { deliver -> settle(resource, success = true, current = true, deliver) },
            dispose = { it?.dispose() },
        )
        assertSame(resource, owned)
        assertEquals(0, resource.disposed)
        owned!!.dispose()
        assertEquals(1, resource.disposed)
    }

    @Test fun `an error callback disposes the bitmap and delivers null`() = runTest {
        val resource = FakeResource()
        val owned = awaitOwnedResult(
            start = { deliver -> settle(resource, success = false, current = true, deliver) },
            dispose = { it?.dispose() },
        )
        assertNull(owned)
        assertEquals(1, resource.disposed)
    }

    @Test fun `a stale callback disposes the bitmap and delivers null`() = runTest {
        val resource = FakeResource()
        val owned = awaitOwnedResult(
            start = { deliver -> settle(resource, success = true, current = false, deliver) },
            dispose = { it?.dispose() },
        )
        assertNull(owned)
        assertEquals(1, resource.disposed)
    }

    @Test fun `a throw before the request completes with that exception`() = runTest {
        val error = runCatching {
            awaitOwnedResult<FakeResource>(
                start = { throw IllegalStateException("request rejected") },
                dispose = { it?.dispose() },
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test fun `a throw after the request is queued does not dispose early`() = runTest {
        val resource = FakeResource()
        lateinit var deliver: (FakeResource?) -> Unit
        val error = runCatching {
            awaitOwnedResult(
                start = { callback ->
                    deliver = callback
                    throw IllegalStateException("hook failed after enqueue")
                },
                dispose = { it?.dispose() },
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(0, resource.disposed)
        settle(resource, success = false, current = true, deliver)
        assertEquals(1, resource.disposed)
    }

    @Test fun `a repeated callback for the same bitmap does not dispose twice`() = runTest {
        val resource = FakeResource()
        val owned = awaitOwnedResult(
            start = { deliver ->
                settle(resource, success = true, current = true, deliver)
                settle(resource, success = false, current = true, deliver)
            },
            dispose = { it?.dispose() },
        )
        assertSame(resource, owned)
        assertEquals(0, resource.disposed)
        owned!!.dispose()
        assertEquals(1, resource.disposed)
    }
}
