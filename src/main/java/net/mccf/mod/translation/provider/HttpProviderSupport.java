package net.mccf.mod.translation.provider;

import net.mccf.mod.MCCF;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 各 HTTP 翻译 Provider 共用的请求发送逻辑。
 *
 * 所有 Provider 共享同一个 {@link HttpClient}（内部维护连接池，重复创建代价较高）。
 * 统一在这里做超时、异常包装，各 Provider 只需要关心"怎么拼请求体"和
 * "怎么解析响应体"这两件事。
 */
final class HttpProviderSupport {

	static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private HttpProviderSupport() {}

	/** 发送一个 GET 请求并返回响应体字符串，用于模型列表这类只读接口。 */
	static CompletableFuture<String> getJson(String url, java.util.Map<String, String> headers) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(REQUEST_TIMEOUT)
				.GET();

		headers.forEach(builder::header);

		return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> {
					if (response.statusCode() / 100 != 2) {
						MCCF.LOGGER.warn("[MCCF] Provider HTTP call to {} failed with status {}: {}",
								url, response.statusCode(), truncate(response.body()));
						throw new RuntimeException("HTTP " + response.statusCode() + " from " + url);
					}
					return response.body();
				});
	}

	/**
	 * 发送一个 POST 请求并返回响应体字符串。非 2xx 状态码会让返回的
	 * future 以异常方式完成，调用方（TranslationService）会捕获并降级为原文。
	 */
	static CompletableFuture<String> postJson(String url, String body, java.util.Map<String, String> headers) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(body));

		headers.forEach(builder::header);

		return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> {
					if (response.statusCode() / 100 != 2) {
						MCCF.LOGGER.warn("[MCCF] Provider HTTP call to {} failed with status {}: {}",
								url, response.statusCode(), truncate(response.body()));
						throw new RuntimeException("HTTP " + response.statusCode() + " from " + url);
					}
					return response.body();
				});
	}

	/** 表单编码版本（DeepL 用表单而不是 JSON body）。 */
	static CompletableFuture<String> postForm(String url, String formBody, java.util.Map<String, String> headers) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(formBody));

		headers.forEach(builder::header);

		return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> {
					if (response.statusCode() / 100 != 2) {
						MCCF.LOGGER.warn("[MCCF] Provider HTTP call to {} failed with status {}: {}",
								url, response.statusCode(), truncate(response.body()));
						throw new RuntimeException("HTTP " + response.statusCode() + " from " + url);
					}
					return response.body();
				});
	}

	private static String truncate(String s) {
		if (s == null) return "";
		return s.length() > 300 ? s.substring(0, 300) + "..." : s;
	}

	/** 简单的 JSON 字符串转义，用于手写 JSON body（避免引入额外依赖，Gson 仅用于解析响应）。 */
	static String escapeJson(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.toString();
	}

	/** URL 表单编码。 */
	static String urlEncode(String s) {
		return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
	}
}
