package net.mccf.mod.translation.provider;

import net.mccf.mod.config.ProviderConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Kimi (Moonshot AI) 翻译 Provider。
 *
 * Kimi 的 API 与 OpenAI Chat Completions 完全兼容（官方文档：只需替换
 * base_url 和 api_key，其余代码不用改），因此直接复用
 * {@link ChatCompletionsSupport} 的请求构造 / 响应解析逻辑。
 *
 * 默认模型 kimi-k2.5 支持 {@code "thinking":{"type":"disabled"}} 关闭思考
 * （{@code config.disableThinking} 为 true 时会带上这个参数）。**重要限制**：
 * 如果玩家把模型手动改成 K3 系列，官方文档明确写"Reasoning is always on.
 * There is no non-thinking mode."——传这个参数不会报错，但也不会真正关闭
 * 思考，K3 强制思考是 API 本身的限制，不是这个开关失效。
 *
 * Endpoint: POST https://api.moonshot.ai/v1/chat/completions
 * Auth: Authorization: Bearer <key>
 */
public class KimiTranslationProvider implements TranslationProvider {

	public static final String ID = "kimi";

	private final ProviderConfig config;

	public KimiTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "Kimi / Moonshot AI (" + (config.model.isBlank() ? "kimi-k2.5" : config.model) + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Kimi API key not configured"));
		}

		String model = config.model.isBlank() ? "kimi-k2.5" : config.model;
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/chat/completions";
		String systemPrompt = ChatCompletionsSupport.buildSystemPrompt(request);
		String body = ChatCompletionsSupport.buildRequestBody(model, systemPrompt, request.sourceText(), config.disableThinking);

		return HttpProviderSupport.postJson(endpoint, body, Map.of(
				"Authorization", "Bearer " + config.apiKey,
				"Content-Type", "application/json"
		)).thenApply(ChatCompletionsSupport::parseChatCompletionResponse)
				.thenApply(TranslationResult::new);
	}

	@Override
	public CompletableFuture<List<String>> listModels() {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Kimi API key not configured"));
		}
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/models";
		return HttpProviderSupport.getJson(endpoint, Map.of("Authorization", "Bearer " + config.apiKey))
				.thenApply(ChatCompletionsSupport::parseModelListResponse);
	}
}
