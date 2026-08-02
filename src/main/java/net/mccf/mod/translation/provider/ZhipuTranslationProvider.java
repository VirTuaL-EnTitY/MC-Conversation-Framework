package net.mccf.mod.translation.provider;

import net.mccf.mod.config.ProviderConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 智谱 AI（Zhipu AI / Z.ai）翻译 Provider，GLM 系列模型。
 *
 * 智谱的 API 是 OpenAI Chat Completions 兼容接口（官方文档确认 base_url +
 * api_key 替换即可复用标准 OpenAI 客户端），因此直接复用
 * {@link ChatCompletionsSupport} 的请求构造 / 响应解析逻辑，写法与
 * {@link KimiTranslationProvider}、{@link DeepSeekTranslationProvider} 一致。
 *
 * 默认模型 glm-5.2（当前最新旗舰）默认开启思考模式，可通过
 * {@code config.disableThinking} 打开时带上
 * {@code "thinking":{"type":"disabled"}} 关闭（GLM-5 / GLM-5.2 官方示例代码
 * 确认支持这个参数结构，与 DeepSeek/Kimi 用的是同一套 thinking 参数格式）。
 *
 * Endpoint: POST https://open.bigmodel.cn/api/paas/v4/chat/completions
 * Auth: Authorization: Bearer <key>
 */
public class ZhipuTranslationProvider implements TranslationProvider {

	public static final String ID = "zhipu";

	private final ProviderConfig config;

	public ZhipuTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "Zhipu AI / GLM (" + (config.model.isBlank() ? "glm-5.2" : config.model) + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Zhipu API key not configured"));
		}

		String model = config.model.isBlank() ? "glm-5.2" : config.model;
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/chat/completions";
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
			return CompletableFuture.failedFuture(new IllegalStateException("Zhipu API key not configured"));
		}
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/models";
		return HttpProviderSupport.getJson(endpoint, Map.of("Authorization", "Bearer " + config.apiKey))
				.thenApply(ChatCompletionsSupport::parseModelListResponse);
	}
}
