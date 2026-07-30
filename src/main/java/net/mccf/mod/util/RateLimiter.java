package net.mccf.mod.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 固定窗口限流器：每个时间窗口（默认 1 秒）内最多放行指定数量的请求，
 * 超出返回 false 让调用方跳过。
 *
 * <p>职责边界：
 * <ul>
 *   <li>只管：窗口内请求计数、窗口过期重置、是否放行的判定</li>
 *   <li>不管：被拒绝的请求怎么处理（那是调用方的职责）、限流策略的配置来源
 *       （那是调用方在构造时传入的）</li>
 * </ul>
 *
 * <p>从 {@code ClientOnlyChatTranslator} 抽取出来的原因：限流逻辑是纯并发控制，
 * 不依赖任何 Minecraft 类，原来混在 ChatTranslator 里没法单独测试。抽成独立类
 * 后可以写 JUnit 测试覆盖并发竞争和窗口边界场景——这些场景手动测试几乎无法覆盖。
 *
 * <p>线程安全：内部用 {@link AtomicInteger} + {@code volatile} + {@code synchronized}
 * 保证多线程调用的正确性。具体竞态分析见 {@link #tryAcquire()} 的注释。
 */
public final class RateLimiter {

	private final long windowMillis;
	private final int maxRequests;

	private final AtomicInteger requestCount = new AtomicInteger(0);
	private volatile long windowStartMillis = 0L;

	/**
	 * @param windowMillis  窗口时长（毫秒）
	 * @param maxRequests   单个窗口内最多放行的请求数
	 */
	public RateLimiter(long windowMillis, int maxRequests) {
		if (windowMillis <= 0) throw new IllegalArgumentException("windowMillis must be positive");
		if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
		this.windowMillis = windowMillis;
		this.maxRequests = maxRequests;
	}

	/**
	 * 尝试获取一个请求配额。在当前窗口内未超限返回 true，超限返回 false。
	 *
	 * <p>窗口过期重置用 synchronized + 双检查保证原子性：
	 * 如果不用锁，两个线程可能同时进入重置分支，计数被清零两次但时间戳只前进一次，
	 * 导致窗口内实际放过超过上限的请求。双检查避免重复重置（第二个线程进入
	 * synchronized 块时发现已经被重置过了，直接跳过）。
	 *
	 * <p>为什么用固定窗口而不是滑动窗口：固定窗口实现简单，最坏情况下窗口边界附近
	 * 可能放过接近 2 倍上限的请求（窗口末尾 N 条 + 新窗口开头 N 条），但聊天刷屏
	 * 本来就是异常行为，精确限流意义不大；滑动窗口要维护时间戳队列，开销和复杂度
	 * 都更高，不值当。
	 *
	 * @return true 表示放行，false 表示限流拒绝
	 */
	public boolean tryAcquire() {
		long now = System.currentTimeMillis();
		long windowStart = windowStartMillis;
		if (now - windowStart >= windowMillis) {
			synchronized (this) {
				if (now - windowStartMillis >= windowMillis) {
					windowStartMillis = now;
					requestCount.set(0);
				}
			}
		}
		return requestCount.incrementAndGet() <= maxRequests;
	}

	/** 供测试和调试观察当前窗口已计数，不用于生产逻辑。 */
	int currentCount() {
		return requestCount.get();
	}
}
