package net.mccf.mod.translation.provider;

import net.mccf.mod.config.ProviderConfig;

/**
 * 根据 Provider ID + 一份 {@link ProviderConfig} 构造对应的 Provider 实例。
 *
 * 两个使用场景：
 * 1. {@code MCCF.registerAllProviders()}：用已保存的配置构造正式使用的 Provider。
 * 2. "一键获取模型"功能：用配置界面里**尚未保存**的临时 apiKey/endpoint
 *    构造一个一次性 Provider 实例，只用来调用 listModels()，不会被注册进
 *    TranslationService，用完即丢。
 *
 * 集中在这里而不是分散在 MCCF.java 里，是因为两个场景都需要同一份
 * "ID -> 构造哪个类" 的映射，避免两处重复维护、容易漏改。
 */
public final class ProviderFactory {

	private ProviderFactory() {}

	public static TranslationProvider create(String providerId, ProviderConfig config) {
		return switch (providerId) {
			case OpenAiTranslationProvider.ID -> new OpenAiTranslationProvider(config);
			case ClaudeTranslationProvider.ID -> new ClaudeTranslationProvider(config);
			case GeminiTranslationProvider.ID -> new GeminiTranslationProvider(config);
			case DeepLTranslationProvider.ID -> new DeepLTranslationProvider(config);
			case KimiTranslationProvider.ID -> new KimiTranslationProvider(config);
			case DeepSeekTranslationProvider.ID -> new DeepSeekTranslationProvider(config);
			case OllamaTranslationProvider.ID -> new OllamaTranslationProvider(config);
			default -> new MockTranslationProvider();
		};
	}
}
