package net.mccf.mod.translation.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * 复用了几乎完全相同的请求/响应结构。
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
		String endpoint = stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/chat/completions";
		String systemPrompt = buildSystemPrompt(request);
		String body = buildRequestBody(model, systemPrompt, request.sourceText());

		return HttpProviderSupport.postJson(endpoint, body, Map.of(
				"Authorization", "Bearer " + config.apiKey,
				"Content-Type", "application/json"
		)).thenApply(OpenAiTranslationProvider::parseChatCompletionResponse)
				.thenApply(TranslationResult::new);
	}

	static String stripTrailingSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}

	@Override
	public CompletableFuture<List<String>> listModels() {
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException("OpenAI API key not configured"));
		}
		String endpoint = stripTrailingSlash(config.effectiveEndpoint(ID)) + "/v1/models";
		return HttpProviderSupport.getJson(endpoint, Map.of("Authorization", "Bearer " + config.apiKey))
				.thenApply(OpenAiTranslationProvider::parseModelListResponse);
	}

	/** 解析标准 OpenAI 模型列表响应：{"data":[{"id":"gpt-4o",...}, ...]}，按 id 字母排序。 */
	static List<String> parseModelListResponse(String responseBody) {
		JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
		JsonArray data = root.getAsJsonArray("data");
		if (data == null) {
			throw new RuntimeException("No data array in models response: " + responseBody);
		}
		List<String> ids = new java.util.ArrayList<>();
		for (var element : data) {
			JsonObject obj = element.getAsJsonObject();
			if (obj.has("id")) {
				ids.add(obj.get("id").getAsString());
			}
		}
		ids.sort(String::compareTo);
		return ids;
	}

	/** 构造系统提示词：说明翻译任务 + 注入受限的对话上下文（仅限当前 Conversation）。 */
	static String buildSystemPrompt(TranslationRequest request) {
		StringBuilder sb = new StringBuilder();
		sb.append("You are a real-time game chat translator. Translate the user's message from ")
				.append(request.sourceLang()).append(" to ").append(request.targetLang())
				.append(". Only output the translated text, nothing else — no quotes, no explanations.");

		List<String> context = request.contextMessages();
		if (context != null && !context.isEmpty()) {
			sb.append(" Recent conversation context (for tone/pronoun consistency only, do not translate these): ");
			// 只取最近几条，避免 prompt 过长；上下文本身已经由 Conversation 限定范围。
			int start = Math.max(0, context.size() - 5);
			for (int i = start; i < context.size(); i++) {
				sb.append("\"").append(context.get(i).replace("\"", "'")).append("\" ");
			}
		}
		return sb.toString();
	}

	static String buildRequestBody(String model, String systemPrompt, String userText) {
		return "{"
				+ "\"model\":\"" + HttpProviderSupport.escapeJson(model) + "\","
				+ "\"messages\":["
				+ "{\"role\":\"system\",\"content\":\"" + HttpProviderSupport.escapeJson(systemPrompt) + "\"},"
				+ "{\"role\":\"user\",\"content\":\"" + HttpProviderSupport.escapeJson(userText) + "\"}"
				+ "],"
				+ "\"temperature\":0.3"
				+ "}";
	}

	/** 解析标准 OpenAI Chat Completions 响应：choices[0].message.content */
	static String parseChatCompletionResponse(String responseBody) {
		JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
		JsonArray choices = root.getAsJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new RuntimeException("No choices in response: " + responseBody);
		}
		JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
		return message.get("content").getAsString().trim();
	}
}
