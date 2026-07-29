package net.mccf.mod.translation;

import net.mccf.mod.MCCF;
import net.mccf.mod.dictionary.WorldDictionary;
import net.mccf.mod.translation.provider.TranslationProvider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 翻译服务：串联 世界词典 -> 翻译 Provider -> 结果还原 的完整流程。
 *
 * 这是模组里唯一一处直接调用 {@link TranslationProvider} 的地方；
 * 其余模块（SpatialChatHandler、ConversationManager 等）只与
 * TranslationService 交互，完全不知道底层用的是哪个 AI Provider，
 * 也不直接接触词典的占位符细节。这样切换 Provider、调整词典逻辑
 * 都不会影响上层的空间化/上下文逻辑。
 */
public class TranslationService {

	private final WorldDictionary dictionary;
	private final Map<String, TranslationProvider> providers = new ConcurrentHashMap<>();
	private volatile TranslationProvider activeProvider;

	/**
	 * 翻译结果缓存的大小上限。超过此大小会触发 LRU 淘汰——最久未访问的条目
	 * 被 {@link LruCache#removeEldestEntry} 自动移除（见 {@link #putToCache}）。
	 *
	 * 之所以选 5000：按平均一条缓存项（key + value + CacheEntry 对象头）
	 * 大约 300-500 字节估算，5000 条约占 1.5-2.5 MB 内存，对现代 Minecraft
	 * 服务端（通常 2-8 GB 堆）完全可以忽略；而 5000 条已经能覆盖大多数服务器
	 * 一周内的高频翻译需求。再往上调收益递减、内存风险上升；再往下调命中率
	 * 会明显下跌，频繁回退到 Provider 调用反而拖慢消息分发。5000 是经验上的
	 * 平衡点，没必要做精确的容量规划——真到了要调的程度，问题往往在别处
	 * （比如 Provider 不该被频繁打、或上层该做消息去重）。
	 */
	private static final int CACHE_MAX_SIZE = 5000;

	/**
	 * 缓存项的存活时间。超过 1 小时的条目在下次访问时被当作未命中并移除。
	 *
	 * 1 小时是平衡点：
	 *   - 太短（如 5 分钟）：同一句话隔一会儿再说就要重译，浪费 Provider 调用
	 *     额度（部分 Provider 按调用计费），且短时间内的重复翻译结果通常一致，
	 *     没必要刷新。
	 *   - 太长（如 24 小时）：管理员改了词典、或换了 Provider/model 之后，玩家
	 *     还会持续看到旧译文，1 小时虽不能立即收敛（需要手动 clearCache），
	 *     但比起 24 小时已经显著缩短了"脏数据"窗口。
	 *   - 1 小时正好覆盖一局游戏会话内的高频重复消息，又能在词典/Provider
	 *     变更后较自然地收敛。需要立即生效的场景仍可调用 {@link #clearCache()}。
	 */
	private static final long CACHE_TTL_MILLIS = 60 * 60 * 1000L;

	/**
	 * 缓存项：包装译文 future 和写入时间戳。时间戳用于 TTL 判定。
	 *
	 * 用专门的 entry 对象而不是把时间戳拼进 key，是因为 TTL 判定需要读取
	 * 写入时间——如果时间戳拼进 key，get 时拿不到它就无法判断是否过期，
	 * 除非再做一次 containsKey 或用前缀扫描，得不偿失。entry 对象让
	 * "值"自带元数据，get 一次就拿到了判定所需的全部信息。
	 */
	private static final class CacheEntry {
		final CompletableFuture<String> value;
		final long writeTimeMillis;

		CacheEntry(CompletableFuture<String> value, long writeTimeMillis) {
			this.value = value;
			this.writeTimeMillis = writeTimeMillis;
		}

		boolean isExpired(long nowMillis) {
			return nowMillis - writeTimeMillis > CACHE_TTL_MILLIS;
		}
	}

	/**
	 * 基于 LinkedHashMap 的 LRU 缓存容器。
	 *
	 * 构造时传 accessOrder=true，让每次 get/put 都把被访问的 entry 移到链表尾部，
	 * 链表头部即为最久未访问的 entry。覆盖 removeEldestEntry，在 put 之后由
	 * LinkedHashMap 内部回调：返回 true 时自动淘汰头部 entry。这样就实现了
	 * "超过 CACHE_MAX_SIZE 即淘汰最久未访问项"的逐条 LRU，而不是早期版本里
	 * "超限即整表 clear()"的粗粒度策略。
	 *
	 * 之所以用命名内部类而不是匿名类：覆盖 removeEldestEntry 需要一段方法体，
	 * 匿名类写在字段初始化里可读性差；命名类也让"这是一个 LRU 容器"的意图
	 * 在类型名上一目了然，调试时 toString 也更有意义。
	 */
	private static final class LruCache extends LinkedHashMap<String, CacheEntry> {
		LruCache() {
			super(16, 0.75f, true);
		}

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
			return size() > CACHE_MAX_SIZE;
		}
	}

	/**
	 * 翻译结果缓存。key: sourceLang|targetLang|sourceText。
	 *
	 * 重要约束：这里只缓存"翻译成功"的结果，失败回退原文不会进缓存
	 * （见 {@link #translate} 的注释）。
	 *
	 * 之所以用 LinkedHashMap + accessOrder + synchronizedMap 做精细 LRU：
	 * 早期版本用 ConcurrentHashMap + "超限即整表 clear()" 的粗粒度策略，虽然
	 * 并发吞吐好，但容量满时一次清空会把高频条目也一起干掉，命中率有明显的
	 * "锯齿"——清空瞬间所有后续请求都打到 Provider，短暂尖峰后缓存才重新填满。
	 * 换成 LRU 后只淘汰最久未访问的条目，高频条目长期保留，命中率更平稳。
	 * 代价是 synchronizedMap 把读写串行化，但翻译缓存的临界区极短（一次
	 * HashMap get/put），且 Minecraft 服务端的聊天消息吞吐远达不到让这个锁
	 * 成为瓶颈的程度（每秒几十到几百条已是极限），实测无可感知影响。如果未来
	 * 真的出现锁竞争（比如单服上千人同时刷屏），再换回 ConcurrentHashMap + 清空
	 * 策略或引入 Caffeine 也不迟——但那需要 profile 数据佐证，不是凭直觉提前优化。
	 */
	private final Map<String, CacheEntry> cache = Collections.synchronizedMap(new LruCache());

	public TranslationService(WorldDictionary dictionary) {
		this.dictionary = dictionary;
	}

	public void registerProvider(TranslationProvider provider) {
		providers.put(provider.getId(), provider);
		MCCF.LOGGER.info("[MCCF] Registered translation provider: {} ({})", provider.getId(), provider.getDisplayName());
	}

	public boolean setActiveProvider(String id) {
		TranslationProvider provider = providers.get(id);
		if (provider == null) {
			MCCF.LOGGER.warn("[MCCF] Unknown translation provider: {}", id);
			return false;
		}
		this.activeProvider = provider;
		MCCF.LOGGER.info("[MCCF] Active translation provider set to: {}", id);
		return true;
	}

	public TranslationProvider getActiveProvider() {
		return activeProvider;
	}

	public Map<String, TranslationProvider> getProviders() {
		return providers;
	}

	/**
	 * 拉取指定 Provider 当前可用的模型列表（"一键获取模型"功能）。
	 * 直接转发给对应 Provider 的 {@link TranslationProvider#listModels()}；
	 * 若该 Provider 不存在或不支持该操作，返回的 future 会以异常完成。
	 */
	public CompletableFuture<List<String>> listModels(String providerId) {
		TranslationProvider provider = providers.get(providerId);
		if (provider == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown provider: " + providerId));
		}
		return provider.listModels();
	}

	/**
	 * 翻译一条消息。
	 *
	 * @param sourceText      原文
	 * @param sourceLang      源语言
	 * @param targetLang      目标语言
	 * @param contextMessages 当前 Conversation 内的近期上下文消息（已经是
	 *                        "该对话组能看到的" 范围，调用方负责裁剪）
	 * @return 异步返回最终译文（已还原世界词典占位符）
	 */
	public CompletableFuture<String> translate(String sourceText, String sourceLang, String targetLang,
			List<String> contextMessages) {
		if (activeProvider == null) {
			return CompletableFuture.completedFuture(sourceText);
		}
		if (sourceLang.equals(targetLang)) {
			return CompletableFuture.completedFuture(sourceText);
		}

		String cacheKey = sourceLang + "|" + targetLang + "|" + sourceText;

		// 手动模式：先检查缓存命中。命中直接返回——缓存里的都是翻译成功的结果，
		// 失败回退不会进缓存，所以命中项可以无脑复用。
		CompletableFuture<String> cached = getFromCache(cacheKey);
		if (cached != null) {
			return cached;
		}

		// 未命中：异步翻译。翻译成功才写入缓存；失败时返回原文但不写入缓存。
		//
		// 为什么不缓存失败结果：Provider 调用失败大多是瞬态网络错误（限流、DNS 抖动、
		// 偶发 5xx、上游短暂不可用）。如果把这些"回退原文"的结果也永久缓存，网络
		// 恢复后玩家依然只能看到原文，瞬态错误被固化成永久降级——这与"翻译缓存"
		// 的初衷（避免重复成功调用的开销）相悖。失败时不写入，下次同样请求会重新
		// 调用 Provider，网络恢复后即可正常翻译。
		//
		// 这里放弃了原 computeIfAbsent 实现的"在途去重"特性（原实现把进行中的 future
		// 也缓存，能避免同一句话同时被多个玩家触发时重复调用 Provider）。权衡原因：
		// computeIfAbsent 不区分"真成功"和"失败回退"——doTranslate 内部的 exceptionally
		// 会把异常转成"返回原文"的成功 future，于是失败结果也会被永久缓存，违背
		// 上面那条原则。要同时满足"在途去重 + 失败不缓存"需要更复杂的状态机，
		// 而瞬态错误的代价（永久降级）比短暂重复调用的代价（多一次 API 请求）高得多，
		// 所以选择后者。如果未来发现重复调用 Provider 是个问题，可以再引入在途 future
		// 的弱引用表。
		CompletableFuture<String> translation = doTranslate(sourceText, sourceLang, targetLang, contextMessages);

		// 成功才写缓存。用 completedFuture 包装结果，避免后续命中时还附带 whenComplete/
		// exceptionally 链——命中项就是"已经完成的纯结果 future"，开销最小。
		translation.whenComplete((result, ex) -> {
			if (ex == null) {
				putToCache(cacheKey, CompletableFuture.completedFuture(result));
			}
		});

		// 失败时返回原文（不写入缓存，下次请求可重试）
		return translation.exceptionally(ex -> {
			MCCF.LOGGER.error("[MCCF] Translation failed, falling back to source text.", ex);
			return sourceText;
		});
	}

	private CompletableFuture<String> doTranslate(String sourceText, String sourceLang, String targetLang,
			List<String> contextMessages) {
		WorldDictionary.DictionaryPass pass = dictionary.applyPlaceholders(sourceText);

		TranslationProvider.TranslationRequest request = new TranslationProvider.TranslationRequest(
				pass.processedText(), sourceLang, targetLang, contextMessages);

		// exceptionally 回退逻辑不在这里——上移到 translate()，让 translate() 能区分
		// "真成功"和"失败回退"，从而决定是否写缓存。如果这里包了 exceptionally，
		// 上层看到的 future 永远成功，就无从判断要不要缓存了。
		return activeProvider.translate(request)
				.thenApply(result -> dictionary.restorePlaceholders(result.translatedText(), pass.placeholderToTerm(), targetLang));
	}

	/**
	 * 读缓存，自动处理 TTL 过期。
	 *
	 * 用 synchronized(cache) 包裹整个"get + 过期检查 + remove"复合操作：
	 * Collections.synchronizedMap 只对单个方法调用加锁，复合操作必须由调用方
	 * 手动同步，否则可能出现"线程 A 判定过期 -> 线程 B put 了新 entry ->
	 * 线程 A remove(key) 误删 B 的新鲜 entry"的竞态。synchronized(cache) 用的
	 * 就是 synchronizedMap 内部同一把监视器锁，嵌套不会自死锁。
	 *
	 * get 会触发 accessOrder 重排（把命中的 entry 移到链表尾部），这个副作用
	 * 也在 synchronized 块内完成，保证 LRU 顺序的一致性。
	 */
	private CompletableFuture<String> getFromCache(String key) {
		synchronized (cache) {
			CacheEntry entry = cache.get(key);
			if (entry == null) {
				return null;
			}
			long now = System.currentTimeMillis();
			if (entry.isExpired(now)) {
				cache.remove(key);
				return null;
			}
			return entry.value;
		}
	}

	/**
	 * 写缓存。LRU 淘汰由 {@link LruCache#removeEldestEntry} 在 put 内部自动处理：
	 * 超过 {@link #CACHE_MAX_SIZE} 时，链表头部（最久未访问）的 entry 会被移除，
	 * 无需手动检查容量或清空整表。
	 *
	 * 单次 put 是原子操作，synchronizedMap 内部已加锁，不需要额外的 synchronized 块。
	 * removeEldestEntry 回调也在 put 持锁期间执行，淘汰逻辑天然线程安全。
	 */
	private void putToCache(String key, CompletableFuture<String> value) {
		cache.put(key, new CacheEntry(value, System.currentTimeMillis()));
	}

	/** 清空翻译缓存（例如词典或 Provider 变更后调用，避免脏数据）。 */
	public void clearCache() {
		cache.clear();
	}
}
