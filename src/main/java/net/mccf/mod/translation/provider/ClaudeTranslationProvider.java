package net.mccf.mod.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mccf.mod.config.ProviderConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Claude (Anthropic) 翻译 Provider，使用 Messages API。
 *
 * Endpoint: POST https://api.anthropic.com/v1/messages
 * Auth: x-api-key: <key>（注意不是 Authorization: Bearer）
 * 必须带 anthropic-version 请求头，且 max_tokens 是必填字段。
 */
public class ClaudeTranslationProvider implements TranslationProvider {

	public static final String ID = "claude";
	private static final String ANTHROPIC_VERSION = "2023-06-01";

	private final ProviderConfig config;

	public ClaudeTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "Claude (" + (config.model.isBlank() ? "claude-sonnet-4-6" : config.model) + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Claude API key not configured"));
		}

		String model = config.model.isBlank() ? "claude-sonnet-4-6" : config.model;
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/messages";
		String systemPrompt = ChatCompletionsSupport.buildSystemPrompt(request);
		String body = buildRequestBody(model, systemPrompt, request.sourceText());

		return HttpProviderSupport.postJson(endpoint, body, Map.of(
				"x-api-key", config.apiKey,
				"anthropic-version", ANTHROPIC_VERSION,
				"content-type", "application/json"
		)).thenApply(ClaudeTranslationProvider::parseMessagesResponse)
				.thenApply(TranslationResult::new);
	}

	@Override
	public CompletableFuture<java.util.List<String>> listModels() {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Claude API key not configured"));
		}
		String endpoint = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/models";
		return HttpProviderSupport.getJson(endpoint, Map.of(
				"x-api-key", config.apiKey,
				"anthropic-version", ANTHROPIC_VERSION
		)).thenApply(ChatCompletionsSupport::parseModelListResponse);
	}

	private static String buildRequestBody(String model, String systemPrompt, String userText) {
		return "{"
				+ "\"model\":\"" + HttpProviderSupport.escapeJson(model) + "\","
				+ "\"max_tokens\":1024,"
				+ "\"system\":\"" + HttpProviderSupport.escapeJson(systemPrompt) + "\","
				+ "\"messages\":[{\"role\":\"user\",\"content\":\"" + HttpProviderSupport.escapeJson(userText) + "\"}]"
				+ "}";
	}

	/** 解析 Messages API 响应：content 是一个数组，取其中 type=="text" 的第一个块。 */
	private static String parseMessagesResponse(String responseBody) {
		JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
		JsonArray content = root.getAsJsonArray("content");
		if (content == null || content.isEmpty()) {
			throw new RuntimeException("No content in Claude response: " + responseBody);
		}
		for (var element : content) {
			JsonObject block = element.getAsJsonObject();
			if (block.has("type") && "text".equals(block.get("type").getAsString())) {
				return block.get("text").getAsString().trim();
			}
		}
		throw new RuntimeException("No text block in Claude response: " + responseBody);
	}
}
