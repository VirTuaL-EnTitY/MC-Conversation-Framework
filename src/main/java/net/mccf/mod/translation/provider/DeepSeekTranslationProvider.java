package net.mccf.mod.translation.provider;

import net.mccf.mod.config.ProviderConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DeepSeek 翻译 Provider。
 *
 * 同样是 OpenAI Chat Completions 兼容格式，复用 {@link ChatCompletionsSupport}。
 *
 * 注意：DeepSeek 官方在 2026-07-24 起停用旧的 "deepseek-chat" /
 * "deepseek-reasoner" 别名，替换为 "deepseek-v4-flash" /
 * "deepseek-v4-pro"，因此默认模型直接使用新名称。如果你的账号
 * 仍在使用旧别名且尚未到停用日期，可以在配置界面里手动改回去。
 *
 * V4 系列（deepseek-v4-flash / deepseek-v4-pro）默认开启思考模式——如果
 * {@code config.disableThinking} 为 true，请求体会带上
 * {@code "thinking":{"type":"disabled"}} 关闭它（见 ChatCompletionsSupport
 * #buildRequestBody 的详细说明，含官方文档来源确认）。旧的
 * deepseek-chat/deepseek-reasoner 是靠选不同模型名区分思考与否，这个参数
 * 对它们大概率没有实际效果（但也不会报错），玩家如果手动改回旧模型名，
 * 应该改用 deepseek-chat 而不是这个开关来关闭思考。
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
			return CompletableFuture.failedFuture(new IllegalStateException("DeepSeek API key not configured"));
		}
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/models";
		return HttpProviderSupport.getJson(endpoint, Map.of("Authorization", "Bearer " + config.apiKey))
				.thenApply(ChatCompletionsSupport::parseModelListResponse);
	}
}
