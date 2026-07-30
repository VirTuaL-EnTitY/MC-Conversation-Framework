package net.mccf.mod.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RateLimiter} 的单元测试。
 *
 * 覆盖场景：
 * 1. 单线程基本限流——窗口内前 N 条放行，超出的拒绝
 * 2. 窗口过期后重置——等待窗口过期后重新放行
 * 3. 并发竞争——多线程同时调用不会放过超过上限的请求
 *
 * 这些场景手动测试几乎无法覆盖：
 * - 窗口过期需要精确等待 + 时间戳对齐
 * - 并发竞争需要多线程同时触发，手动模拟不现实
 */
class RateLimiterTest {

	@Test
	@DisplayName("单线程：窗口内前 maxRequests 条放行，超出拒绝")
	void testBasicRateLimit() {
		RateLimiter limiter = new RateLimiter(1000L, 5);

		// 前 5 条放行
		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.tryAcquire(), "第 " + (i + 1) + " 条应该放行");
		}

		// 第 6 条及之后拒绝
		for (int i = 5; i < 10; i++) {
			assertFalse(limiter.tryAcquire(), "第 " + (i + 1) + " 条应该被拒绝");
		}
	}

	@Test
	@DisplayName("窗口过期后重置：等待窗口过期后重新放行")
	void testWindowReset() throws InterruptedException {
		// 用 200ms 窗口，不需要等太久
		RateLimiter limiter = new RateLimiter(200L, 3);

		// 第一轮：3 条放行
		for (int i = 0; i < 3; i++) {
			assertTrue(limiter.tryAcquire());
		}
		assertFalse(limiter.tryAcquire(), "第 4 条应被拒绝");

		// 等待窗口过期
		Thread.sleep(250);

		// 第二轮：窗口已重置，3 条放行
		for (int i = 0; i < 3; i++) {
			assertTrue(limiter.tryAcquire(), "窗口过期后第 " + (i + 1) + " 条应放行");
		}
		assertFalse(limiter.tryAcquire(), "第二轮第 4 条应被拒绝");
	}

	@Test
	@DisplayName("并发：50 个线程同时调用，不会放过超过 maxRequests 条")
	void testConcurrentRateLimit() throws InterruptedException {
		final int threadCount = 50;
		final int maxRequests = 5;
		RateLimiter limiter = new RateLimiter(60000L, maxRequests); // 60s 窗口，保证测试期间不过期

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch doneGate = new CountDownLatch(threadCount);
		AtomicInteger accepted = new AtomicInteger(0);
		AtomicInteger rejected = new AtomicInteger(0);

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					startGate.await(); // 所有线程同时起跑，最大化竞争
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				if (limiter.tryAcquire()) {
					accepted.incrementAndGet();
				} else {
					rejected.incrementAndGet();
				}
				doneGate.countDown();
			});
		}

		startGate.countDown(); // 放行
		boolean finished = doneGate.await(5, TimeUnit.SECONDS);
		executor.shutdown();

		assertTrue(finished, "所有线程应在 5 秒内完成");

		// 核心断言：放行的数量绝不能超过 maxRequests
		// 这是并发 bug 的关键检测点——如果 synchronized/双检查有缺陷，
		// 多个线程可能同时进入重置分支，导致计数被清零，放过超过上限的请求
		assertEquals(maxRequests, accepted.get(),
				"并发调用时放行数必须等于 maxRequests，实际放行 " + accepted.get());
		assertEquals(threadCount - maxRequests, rejected.get(),
				"被拒绝的数量必须等于 threadCount - maxRequests");
	}

	@Test
	@DisplayName("构造器参数校验：非正数参数抛 IllegalArgumentException")
	void testConstructorValidation() {
		org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class, () -> new RateLimiter(0, 5));
		org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class, () -> new RateLimiter(1000, 0));
		org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class, () -> new RateLimiter(-1, 5));
		org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class, () -> new RateLimiter(1000, -1));
	}

	@Test
	@DisplayName("currentCount 反映当前窗口已放行数量（含被拒绝的请求）")
	void testCurrentCount() {
		// maxRequests=3，窗口 60s 保证测试期间不重置
		RateLimiter limiter = new RateLimiter(60000L, 3);
		assertEquals(0, limiter.currentCount());

		// 3 次放行
		limiter.tryAcquire();
		limiter.tryAcquire();
		limiter.tryAcquire();
		assertEquals(3, limiter.currentCount());

		// 2 次拒绝——被拒绝的请求也会 incrementAndGet，所以 count 继续涨
		limiter.tryAcquire(); // 拒绝（第4次调用）
		limiter.tryAcquire(); // 拒绝（第5次调用）
		assertEquals(5, limiter.currentCount());
	}
}
