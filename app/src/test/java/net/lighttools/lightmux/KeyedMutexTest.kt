package net.lighttools.lightmux

import net.lighttools.lightmux.ssh.KeyedMutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KeyedMutexTest {

    @Test
    fun `同一个 key 永远拿到同一把锁`() {
        val locks = KeyedMutex()
        assertSame(locks["host-a"], locks["host-a"])
        assertNotSame(locks["host-a"], locks["host-b"])
    }

    @Test
    fun `多线程首次同时访问同一个 key 也只发一把锁`() {
        // 这正是 getOrPut（读改写两步）会漏掉的场景：各拿到一把锁，「按主机串行」当场失效。
        val locks = KeyedMutex()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val pending = (1..threads).map {
                pool.submit<Any> {
                    start.await(5, TimeUnit.SECONDS)
                    locks["same-host"]
                }
            }
            start.countDown()
            val results = pending.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.distinctBy { System.identityHashCode(it) }.size)
        } finally {
            pool.shutdownNow()
        }
    }
}
