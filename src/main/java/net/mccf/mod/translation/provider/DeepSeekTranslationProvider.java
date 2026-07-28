package net.mccf.mod.translation.provider;

import net.mccf.mod.config.ProviderConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DeepSeek 翻译 Provider。
 *
 * 同样是 OpenAI Chat Completions 兼容格式。
 *
 * 注意：DeepSeek 官方在 2026-07-24 起停用旧的 "deepseek-chat" /
 * "deepseek-reasoner" 别名，替换为 "deepseek-v4-flash" /
 * "deepseek-v4-pro"，因此默认模型直接使用新名称。如果你的账号
 * 仍在使用旧别名且尚未到停用日期，可以在配置界面里手动改回去。
 *
 * Endpoint: POST https://api.deepseek.com/chat/completions
 * Auth: Authorization: Bearer <key>
 */
public class DeepSeekTranslationProvider implements TranslationProvider {

	public static final String ID = "deepseek";

	private final ProviderConfig config;

	public DeepSeekTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "DeepSeek (" + (config.model.isBlank() ? "deepseek-v4-flash" : config.model) + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key not configured"));
		}

		String model = config.model.isBlank() ? "deepseek-v4-flash" : config.model;
		String endpoint = OpenAiTranslationProvider.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/chat/completions";
		String systemPrompt = OpenAiTranslationProvider.buildSystemPrompt(request);
		String body = OpenAiTranslationProvider.buildRequestBody(model, systemPrompt, request.sourceText());

		return HttpProviderSupport.postJson(endpoint, body, Map.of(
				"Authorization", "Bearer " + config.apiKey,
				"Content-Type", "application/json"
		)).thenApply(OpenAiTranslationProvider::parseChatCompletionResponse)
				.thenApply(TranslationResult::new);
	}

	@Override
	public CompletableFuture<java.util.List<String>> listModels() {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key not configured"));
		}
		String endpoint = OpenAiTranslationProvider.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/models";
		return HttpProviderSupport.getJson(endpoint, Map.of("Authorization", "Bearer " + config.apiKey))
				.thenApply(OpenAiTranslationProvider::parseModelListResponse);
	}
}
