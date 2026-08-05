package net.mccf.mod.util;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 滑动窗口限流器：在最近的固定时间窗口内最多放行指定数量的请求，超出返回 false。
 *
 * <p>职责边界：
 * <ul>
 *   <li>只管：窗口内请求时间戳记录、过期时间戳清理、是否放行的判定</li>
 *   <li>不管：被拒绝的请求怎么处理（那是调用方的职责）、限流策略的配置来源
 *       （那是调用方在构造时传入的）</li>
 * </ul>
 *
 * <p>1.1.2 从固定窗口升级为滑动窗口的根因：旧版固定窗口在边界附近可能放过接近 2 倍上限
 * 的请求（窗口末尾 N 条 + 新窗口开头 N 条）。对 OpenAI / DeepL 这类付费 API，2 倍突刺
 * 可能触发上游速率限制导致 API Key 被临时封禁——比"翻译偶尔失败"严重得多。滑动窗口
 * 用时间戳队列精确限制"任意连续 windowMillis 内不超过 maxRequests 条"，无突刺风险。
 *
 * <p>实现：维护一个 {@link ArrayDeque} 存储已放行请求的时间戳。每次 {@link #tryAcquire}
 * 先清理掉窗口外的旧时间戳，再判断队列长度是否已达上限——未达上限则把当前时间戳入队
 * 并返回 true，已达上限返回 false（不入队，避免污染队列）。
 *
 * <p>线程安全：所有读写操作都在 {@code synchronized(this)} 块内，保证 ArrayDeque
 * 操作的原子性。ArrayDeque 本身非线程安全，必须外部同步——这里直接对方法加锁
 * 而不是用 ConcurrentLinkedDeque，原因是：
 * 1. 滑动窗口的"清理 + 检查 + 入队"是复合操作，必须原子；
 * 2. 限流调用量远低于高并发场景（每秒个位数），synchronized 的开销可忽略；
 * 3. ConcurrentLinkedDeque 的 size() 是 O(n) 而非 O(1)，频繁调用反而更慢。
 *
 * <p>从 {@code ClientOnlyChatTranslator} 抽取出来的原因：限流逻辑是纯并发控制，
 * 不依赖任何 Minecraft 类，原来混在 ChatTranslator 里没法单独测试。抽成独立类
 * 后可以写 JUnit 测试覆盖并发竞争和窗口边界场景——这些场景手动测试几乎无法覆盖。
 */
public final class RateLimiter {

	private final long windowMillis;
	private final int maxRequests;

	/** 已放行请求的时间戳队列（毫秒），按入队顺序单调递增。队首是最旧的。 */
	private final Deque<Long> timestamps = new ArrayDeque<>();

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
	 * 尝试获取一个请求配额。在最近 windowMillis 内未超限返回 true，超限返回 false。
	 *
	 * <p>清理逻辑：先从队首弹出所有"早于 now - windowMillis"的时间戳——这些时间戳
	 * 已经超出当前窗口，不再计入限流统计。弹完之后队列里只剩"当前窗口内"的请求。
	 *
	 * <p>放行判定：清理后若队列长度 < maxRequests，把 now 入队并返回 true；
	 * 否则返回 false 且不入队（被拒绝的请求不污染队列，否则后续清理前都会
	 * 错误地占着配额）。这与旧版固定窗口的"被拒绝也 incrementAndGet"语义
	 * 不同——滑动窗口下不需要"被拒绝也计数"，因为被拒绝的请求根本没有"时间戳"概念。
	 *
	 * @return true 表示放行，false 表示限流拒绝
	 */
	public synchronized boolean tryAcquire() {
		long now = System.currentTimeMillis();
		long windowStart = now - windowMillis;

		// 清理窗口外的时间戳。peek/peekLast 是 O(1)，pop 也是 O(1)（ArrayDeque 头部删除）。
		// 平均清理次数极少——每个时间戳只会被弹一次，amortized O(1)。
		while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
			timestamps.pollFirst();
		}

		if (timestamps.size() < maxRequests) {
			timestamps.addLast(now);
			return true;
		}
		return false;
	}

	/**
	 * 供测试和调试观察当前窗口内已放行数量。语义与旧版固定窗口的 currentCount 略有差异：
	 * 旧版包含"被拒绝也计数"，这里只统计真正放行的请求时间戳（更直观）。
	 */
	synchronized int currentCount() {
		long now = System.currentTimeMillis();
		long windowStart = now - windowMillis;
		while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
			timestamps.pollFirst();
		}
		return timestamps.size();
	}
}
