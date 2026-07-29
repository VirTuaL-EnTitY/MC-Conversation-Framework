package net.mccf.mod.translation.provider;

import net.mccf.mod.config.ProviderConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI Chat Completions API 翻译 Provider。
 *
 * 用 Chat Completions（而非新的 Responses API）是因为它是目前跨版本、
 * 跨兼容层（Kimi/DeepSeek 等厂商都模仿这个格式）最广泛支持的形态，
 * {@link KimiTranslationProvider}、{@link DeepSeekTranslationProvider}
 * 复用了几乎完全相同的请求/响应结构——这部分公共逻辑抽到了
 * {@link ChatCompletionsSupport}，本类只负责 OpenAI 自己的鉴权头和 endpoint。
 *
 * Endpoint: POST https://api.openai.com/v1/chat/completions
 * Auth: Authorization: Bearer <key>
 */
public class OpenAiTranslationProvider implements TranslationProvider {

	public static final String ID = "openai";

	private final ProviderConfig config;

	public OpenAiTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "OpenAI (" + (config.model.isBlank() ? "gpt-4o-mini" : config.model) + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("OpenAI API key not configured"));
		}

		String model = config.model.isBlank() ? "gpt-4o-mini" : config.model;
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/chat/completions";
		String systemPrompt = ChatCompletionsSupport.buildSystemPrompt(request);
		String body = ChatCompletionsSupport.buildRequestBody(model, systemPrompt, request.sourceText());

		return HttpProviderSupport.postJson(endpoint, body, Map.of(
				"Authorization", "Bearer " + config.apiKey,
				"Content-Type", "application/json"
		)).thenApply(ChatCompletionsSupport::parseChatCompletionResponse)
				.thenApply(TranslationResult::new);
	}

	@Override
	public CompletableFuture<List<String>> listModels() {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("OpenAI API key not configured"));
		}
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/models";
		return HttpProviderSupport.getJson(endpoint, Map.of("Authorization", "Bearer " + config.apiKey))
				.thenApply(ChatCompletionsSupport::parseModelListResponse);
	}
}
