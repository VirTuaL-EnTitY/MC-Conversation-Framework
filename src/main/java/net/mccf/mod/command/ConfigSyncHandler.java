package net.mccf.mod.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.mccf.mod.MCCF;
import net.mccf.mod.config.MCCFConfig;
import net.mccf.mod.config.ProviderConfig;
import net.mccf.mod.config.ProviderDefaults;
import net.mccf.mod.network.ConfigSnapshotPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
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
		// showOriginalText / showOriginalTextInChat 已移至客户端偏好（1.1.1），
		// 不再由服务端配置控制，快照里不包含这两个字段。

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

	/**
	 * 向所有在线玩家广播当前配置快照。
	 *
	 * 1.1.5 新增：修复"服务端切换 Provider 后客户端必须重连才能同步"的 bug。
	 * 旧版只在两个时机发 ConfigSnapshotPayload：玩家自己请求快照（打开配置界面）、
	 * 玩家自己提交修改（op 保存配置）——两种都只回发给发起者本人。其他在线玩家
	 * 的 ClientConfigState.activeProvider 一直是旧值，必须重连或重开配置界面才能拿到新值。
	 *
	 * 为什么按玩家各自 op 状态逐个构造快照而不是发同一份：buildSnapshotJson 内部
	 * 会根据 op 状态脱敏 apiKey（非 op 收到的 apiKey 为空字符串）。如果发同一份，
	 * 要么非 op 玩家看到真实 Key（安全漏洞），要么 op 玩家看到脱敏 Key（界面显示
	 * Key 被清空了，误以为配置丢了）。必须按玩家分别构造。
	 *
	 * 调用时机：
	 * 1. op 通过配置界面保存后（MCCF.java UpdateConfigPayload 接收器）
	 * 2. op 通过 /mccf provider set 命令切换后（MCCFCommand.setProvider）
	 * 3. 玩家加入服务器时（ServerPlayConnectionEvents.JOIN，只发给该玩家）
	 *
	 * @param server 当前 MinecraftServer 实例，用于获取在线玩家列表
	 * @param config 当前服务端配置
	 */
	public static void broadcastSnapshot(MinecraftServer server, MCCFConfig config) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			String snapshotJson = buildSnapshotJson(player, config);
			ServerPlayNetworking.send(player, new ConfigSnapshotPayload(snapshotJson));
		}
	}

	/**
	 * 向单个玩家推送配置快照。
	 *
	 * 1.1.5 新增：用于玩家加入服务器时主动推送，让 ClientConfigState 立即反映
	 * 真实服务端状态——不打开配置界面也能知道当前 activeProvider 是什么。
	 * 旧版玩家加入后 ClientConfigState.activeProvider 一直是默认值 "mock"，
	 * 直到玩家手动打开配置界面发 RequestConfigPayload 才会更新。
	 *
	 * 单独提供一个单播方法而不是复用 broadcastSnapshot + 遍历，是因为加入事件里
	 * 只需要发给该玩家，遍历全服玩家既浪费也语义不清。
	 */
	public static void sendSnapshotTo(ServerPlayerEntity player, MCCFConfig config) {
		String snapshotJson = buildSnapshotJson(player, config);
		ServerPlayNetworking.send(player, new ConfigSnapshotPayload(snapshotJson));
	}
}
