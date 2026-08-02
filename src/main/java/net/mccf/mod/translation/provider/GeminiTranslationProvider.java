package net.mccf.mod.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mccf.mod.config.ProviderConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Gemini (Google) 翻译 Provider，使用 generateContent 端点。
 *
 * Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * Auth: x-goog-api-key: <key>
 *
 * 与 OpenAI/Claude 不同，Gemini 没有独立的 system 角色字段（Contents API
 * 里没有 "system"），因此把系统提示词和用户文本拼在同一个 user part 里。
 *
 * 思考控制：默认模型 gemini-3.5-flash 支持通过
 * {@code generationConfig.thinkingConfig.thinkingBudget: 0} 关闭思考——这是
 * Gemini 2.5/3.5 flash 系列沿用的旧参数名（官方 SDK 示例代码确认
 * gemini-3.5-flash 依然接受这个参数，不是新一代的 thinking_level）。
 * **重要限制**：官方文档明确写"Thinking can't be turned off for Gemini
 * 2.5 Pro"，Gemini 3 Pro 同样"thinking cannot be disabled"——如果玩家把
 * 模型手动改成任何 *-pro 型号，这个参数不会报错，但思考也不会真正关闭，
 * 这是 Google API 本身的限制，不是这个开关失效。
 */
public class GeminiTranslationProvider implements TranslationProvider {

	public static final String ID = "gemini";

	private final ProviderConfig config;

	public GeminiTranslationProvider(ProviderConfig config) {
		this.config = config;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDisplayName() {
		return "Gemini (" + (config.model.isBlank() ? "gemini-3.5-flash" : config.model) + ")";
	}

	@Override
	public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Gemini API key not configured"));
		}

		String model = config.model.isBlank() ? "gemini-3.5-flash" : config.model;
		String base = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID));
		String endpoint = base + "/v1beta/models/" + model + ":generateContent";

		String systemPrompt = ChatCompletionsSupport.buildSystemPrompt(request);
		String combinedPrompt = systemPrompt + "\n\nText to translate:\n" + request.sourceText();
		String body = buildRequestBody(combinedPrompt, config.disableThinking);

		return HttpProviderSupport.postJson(endpoint, body, Map.of(
				"x-goog-api-key", config.apiKey,
				"Content-Type", "application/json"
		)).thenApply(GeminiTranslationProvider::parseGenerateContentResponse)
				.thenApply(TranslationResult::new);
	}

	@Override
	public CompletableFuture<java.util.List<String>> listModels() {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("Gemini API key not configured"));
		}
		String base = ChatCompletionsSupport.stripTrailingSlash(config.effectiveEndpoint(ID));
		String endpoint = base + "/v1beta/models";
		return HttpProviderSupport.getJson(endpoint, Map.of("x-goog-api-key", config.apiKey))
				.thenApply(GeminiTranslationProvider::parseModelListResponse);
	}

	/** Gemini 模型列表响应：{"models":[{"name":"models/gemini-2.5-pro",...}]}，name 需去掉 "models/" 前缀。 */
	private static java.util.List<String> parseModelListResponse(String responseBody) {
		JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
		JsonArray models = root.getAsJsonArray("models");
		if (models == null) {
			throw new RuntimeException("No models array in Gemini response: " + responseBody);
		}
		java.util.List<String> ids = new java.util.ArrayList<>();
		for (var element : models) {
			JsonObject obj = element.getAsJsonObject();
			if (obj.has("name")) {
				String name = obj.get("name").getAsString();
				ids.add(name.startsWith("models/") ? name.substring("models/".length()) : name);
			}
		}
		ids.sort(String::compareTo);
		return ids;
	}

	private static String buildRequestBody(String promptText, boolean disableThinking) {
		String thinkingField = disableThinking
				? ",\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":0}}"
				: "";
		return "{"
				+ "\"contents\":[{\"parts\":[{\"text\":\"" + HttpProviderSupport.escapeJson(promptText) + "\"}]}]"
				+ thinkingField
				+ "}";
	}

	/** 解析响应：candidates[0].content.parts[0].text */
	private static String parseGenerateContentResponse(String responseBody) {
		JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
		JsonArray candidates = root.getAsJsonArray("candidates");
		if (candidates == null || candidates.isEmpty()) {
			throw new RuntimeException("No candidates in Gemini response: " + responseBody);
		}
		JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
		JsonArray parts = content.getAsJsonArray("parts");
		if (parts == null || parts.isEmpty()) {
			throw new RuntimeException("No parts in Gemini response: " + responseBody);
		}
		return parts.get(0).getAsJsonObject().get("text").getAsString().trim();
	}
}
