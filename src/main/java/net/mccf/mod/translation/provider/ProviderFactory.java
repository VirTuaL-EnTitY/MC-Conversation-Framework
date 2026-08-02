package net.mccf.mod.translation.provider;

import net.mccf.mod.MCCF;
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
			case ZhipuTranslationProvider.ID -> new ZhipuTranslationProvider(config);
			case OllamaTranslationProvider.ID -> new OllamaTranslationProvider(config);
			// 未知 ID 仍然 fallback 到 Mock，保证服务器不崩——但必须 warn：
			// 静默 fallback 会把"配置里打错了一个 provider id"这种错误掩盖成
			// "翻译功能好像没生效"，玩家和管理员都难以定位。打 warn 让日志里
			// 有明显线索，同时不抛异常避免阻塞启动或聊天流程。
			default -> {
				MCCF.LOGGER.warn("[MCCF] Unknown provider id '{}', falling back to MockProvider. Check config.json for typos.", providerId);
				yield new MockTranslationProvider();
			}
		};
	}
}
