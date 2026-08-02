package net.mccf.mod.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.mccf.mod.MCCF;
import net.mccf.mod.config.MCCFConfig;
import net.mccf.mod.config.ProviderConfig;
import net.mccf.mod.config.ProviderDefaults;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置界面的服务端数据同步逻辑：把 {@link MCCFConfig} 序列化成
 * 客户端配置 Screen 能理解的 JSON（{@link #buildSnapshotJson}），
 * 以及反向把客户端提交的 JSON 应用回 {@link MCCFConfig}
 * （{@link #applyUpdateJson}）。
 *
 * 权限模型：只有 op（权限等级 >= 2）能真正改动配置。
 * {@link #applyUpdateJson} 在应用前会重新校验，即使客户端 UI 本身已经
 * 做了只读限制，也不能仅凭客户端自觉——服务端永远是权限的最终裁决者。
 */
public class ConfigSyncHandler {

	private static final Gson GSON = new GsonBuilder().create();
	private static final int OP_PERMISSION_LEVEL = 2;

	private ConfigSyncHandler() {}

	/**
	 * 构造发给某个玩家的配置快照 JSON。
	 * 若该玩家不是 op，所有 apiKey 字段会被替换为空字符串，避免泄露。
	 */
	public static String buildSnapshotJson(ServerPlayerEntity player, MCCFConfig config) {
		boolean canEdit = player.hasPermissionLevel(OP_PERMISSION_LEVEL);

		JsonObject root = new JsonObject();
		root.addProperty("canEdit", canEdit);
		root.addProperty("activeProvider", config.activeProvider);
		root.addProperty("showOriginalText", config.showOriginalText);
		root.addProperty("showOriginalTextInChat", config.showOriginalTextInChat);

		JsonObject providersJson = new JsonObject();
		for (Map.Entry<String, ProviderConfig> entry : config.providers.entrySet()) {
			ProviderConfig pc = entry.getValue();
			JsonObject pcJson = new JsonObject();
			pcJson.addProperty("apiKey", canEdit ? nullToEmpty(pc.apiKey) : "");
			pcJson.addProperty("model", nullToEmpty(pc.model));
			pcJson.addProperty("endpoint", pc.effectiveEndpoint(entry.getKey()));
			pcJson.addProperty("isCustomEndpoint", pc.endpoint != null && !pc.endpoint.isBlank());
			pcJson.addProperty("disableThinking", pc.disableThinking);
			providersJson.add(entry.getKey(), pcJson);
		}
		root.add("providers", providersJson);

		return GSON.toJson(root);
	}

	/**
	 * 应用客户端提交的配置更新 JSON。
	 *
	 * @return 若权限校验失败或 JSON 格式有误，返回失败原因；成功则返回空
	 */
	public static java.util.Optional<String> applyUpdateJson(ServerPlayerEntity player, MCCFConfig config, String json) {
		if (!player.hasPermissionLevel(OP_PERMISSION_LEVEL)) {
			MCCF.LOGGER.warn("[MCCF] Player {} attempted to update config without op permission — request denied.",
					player.getGameProfile().getName());
			return java.util.Optional.of("Permission denied: only operators can change MCCF provider settings.");
		}

		try {
			JsonObject root = GSON.fromJson(json, JsonObject.class);
			if (root == null) {
				return java.util.Optional.of("Empty or invalid config payload.");
			}

			if (root.has("activeProvider")) {
				String requestedProvider = root.get("activeProvider").getAsString();
				// 服务端必须校验 activeProvider 是否为已注册的合法 ID。
				// 为什么服务端必须校验：客户端可能发来拼错的 provider id（或恶意
				// 构造的非法值），不校验会导致非法值被持久化到 config.json，下次
				// 重启时 registerAllProviders 会因找不到该 Provider 而回退到 mock，
				// 管理员可能很久都不会注意到配置已经被污染了。
				if (!ProviderDefaults.all().containsKey(requestedProvider)) {
					return java.util.Optional.of("Unknown provider: '" + requestedProvider +
							"'. Valid providers: " + ProviderDefaults.all().keySet());
				}
				config.activeProvider = requestedProvider;
			}

			if (root.has("showOriginalText")) {
				config.showOriginalText = root.get("showOriginalText").getAsBoolean();
			}
			if (root.has("showOriginalTextInChat")) {
				config.showOriginalTextInChat = root.get("showOriginalTextInChat").getAsBoolean();
			}

			if (root.has("providers")) {
				JsonObject providersJson = root.getAsJsonObject("providers");
				Map<String, ProviderConfig> updated = new LinkedHashMap<>(config.providers);
				for (String providerId : providersJson.keySet()) {
					JsonObject pcJson = providersJson.getAsJsonObject(providerId);
					ProviderConfig existing = updated.getOrDefault(providerId, new ProviderConfig());
					// 空字符串的 apiKey 视为"保持原值不变"——避免只读快照往返时
					// 把已保存的 Key 意外清空（客户端非 op 收到的快照 apiKey 本来就是空）。
					String submittedKey = pcJson.has("apiKey") ? pcJson.get("apiKey").getAsString() : "";
					if (!submittedKey.isBlank()) {
						existing.apiKey = submittedKey;
					}
					if (pcJson.has("model")) {
						existing.model = pcJson.get("model").getAsString();
					}
					// resetEndpoint=true 表示玩家点了"恢复默认"，清空 endpoint 让它
					// 回退到 ProviderDefaults 里的官方地址；否则按提交的值更新
					// （空字符串同样代表"使用默认"，不需要额外判断）。
					boolean resetEndpoint = pcJson.has("resetEndpoint") && pcJson.get("resetEndpoint").getAsBoolean();
					if (resetEndpoint) {
						existing.endpoint = "";
					} else if (pcJson.has("endpoint")) {
						existing.endpoint = pcJson.get("endpoint").getAsString();
					}
					if (pcJson.has("disableThinking")) {
						existing.disableThinking = pcJson.get("disableThinking").getAsBoolean();
					}
					updated.put(providerId, existing);
				}
				config.providers = updated;
			}

			config.save();
			MCCF.registerAllProviders();
			MCCF.LOGGER.info("[MCCF] Configuration updated by operator {}.", player.getGameProfile().getName());
			return java.util.Optional.empty();
		} catch (Exception e) {
			MCCF.LOGGER.error("[MCCF] Failed to apply config update from {}.", player.getGameProfile().getName(), e);
			return java.util.Optional.of("Failed to parse config update: " + e.getMessage());
		}
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	/**
	 * 处理"一键获取模型"请求：用玩家提交的临时 apiKey/endpoint（可能尚未
	 * 保存）构造一个一次性 Provider 实例并调用 listModels()。
	 *
	 * 权限：与配置修改一样要求 op——获取模型列表本身不算敏感操作，但请求
	 * 里携带的是真实 API Key，服务端会拿它去发起外部请求，出于谨慎同样
	 * 限制给管理员使用。
	 *
	 * @return 异步返回要发给客户端的结果 JSON（成功/失败都会返回内容，
	 *         不会以异常方式完成——异常已经在内部被捕获转换成失败 JSON）
	 */
	public static java.util.concurrent.CompletableFuture<String> handleModelsRequest(
			ServerPlayerEntity player, MCCFConfig config, String requestJson) {

		if (!player.hasPermissionLevel(OP_PERMISSION_LEVEL)) {
			JsonObject error = new JsonObject();
			error.addProperty("success", false);
			error.addProperty("providerId", "");
			error.addProperty("error", "Permission denied: only operators can fetch model lists.");
			return java.util.concurrent.CompletableFuture.completedFuture(GSON.toJson(error));
		}

		JsonObject requestRoot;
		try {
			requestRoot = GSON.fromJson(requestJson, JsonObject.class);
		} catch (Exception e) {
			JsonObject error = new JsonObject();
			error.addProperty("success", false);
			error.addProperty("error", "Invalid request payload.");
			return java.util.concurrent.CompletableFuture.completedFuture(GSON.toJson(error));
		}

		String providerId = requestRoot.has("providerId") ? requestRoot.get("providerId").getAsString() : "";
		String apiKey = requestRoot.has("apiKey") ? requestRoot.get("apiKey").getAsString() : "";
		String endpoint = requestRoot.has("endpoint") ? requestRoot.get("endpoint").getAsString() : "";

		// 若玩家没有在输入框里填新 Key（apiKey 为空），沿用已保存的配置，
		// 方便"已经保存过 Key，只是想重新拉一次模型列表"这种场景。
		ProviderConfig tempConfig = new ProviderConfig();
		ProviderConfig saved = config.getProviderConfig(providerId);
		tempConfig.apiKey = apiKey.isBlank() ? saved.apiKey : apiKey;
		tempConfig.endpoint = endpoint.isBlank() ? saved.endpoint : endpoint;
		tempConfig.model = saved.model;

		net.mccf.mod.translation.provider.TranslationProvider provider =
				net.mccf.mod.translation.provider.ProviderFactory.create(providerId, tempConfig);

		return provider.listModels()
				.thenApply(models -> {
					JsonObject result = new JsonObject();
					result.addProperty("success", true);
					result.addProperty("providerId", providerId);
					com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
					models.forEach(arr::add);
					result.add("models", arr);
					return GSON.toJson(result);
				})
				.exceptionally(ex -> {
					JsonObject error = new JsonObject();
					error.addProperty("success", false);
					error.addProperty("providerId", providerId);
					String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
					error.addProperty("error", message == null ? "Unknown error" : message);
					MCCF.LOGGER.warn("[MCCF] Model list fetch failed for provider {}: {}", providerId, message);
					return GSON.toJson(error);
				});
	}
}
