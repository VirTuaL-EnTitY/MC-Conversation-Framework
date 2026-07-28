package net.mccf.mod.translation;

import net.mccf.mod.MCCF;
import net.mccf.mod.dictionary.WorldDictionary;
import net.mccf.mod.translation.provider.TranslationProvider;

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
	 * 简单的同源同文本翻译结果缓存，避免同一句话对多个目标语言/多个玩家
	 * 重复调用 Provider（例如一句话要同时翻给 3 个不同语言的玩家）。
	 * key: sourceLang|targetLang|sourceText
	 */
	private final Map<String, CompletableFuture<String>> cache = new ConcurrentHashMap<>();

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
		return cache.computeIfAbsent(cacheKey, k -> doTranslate(sourceText, sourceLang, targetLang, contextMessages));
	}

	private CompletableFuture<String> doTranslate(String sourceText, String sourceLang, String targetLang,
			List<String> contextMessages) {
		WorldDictionary.DictionaryPass pass = dictionary.applyPlaceholders(sourceText);

		TranslationProvider.TranslationRequest request = new TranslationProvider.TranslationRequest(
				pass.processedText(), sourceLang, targetLang, contextMessages);

		return activeProvider.translate(request)
				.thenApply(result -> dictionary.restorePlaceholders(result.translatedText(), pass.placeholderToTerm(), targetLang))
				.exceptionally(ex -> {
					MCCF.LOGGER.error("[MCCF] Translation failed, falling back to source text.", ex);
					return sourceText;
				});
	}

	/** 清空翻译缓存（例如词典或 Provider 变更后调用，避免脏数据）。 */
	public void clearCache() {
		cache.clear();
	}
}
