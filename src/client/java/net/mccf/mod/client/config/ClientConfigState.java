package net.mccf.mod.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.mccf.mod.network.ConfigSnapshotPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端本地保存的配置状态：从服务端快照接收到的数据 + Screen 里正在编辑
 * 但尚未提交的改动。全局单例（客户端同一时刻只会打开一个配置 Screen）。
 *
 * 支持的 Provider 顺序固定，配置 Screen 的"切换 Provider"按钮按这个顺序循环。
 */
public class ClientConfigState {

	public static final String[] PROVIDER_IDS = {
			"mock", "openai", "claude", "gemini", "deepl", "kimi", "deepseek", "ollama"
	};

	/** 生成某个 Provider 在语言文件里对应的翻译 key，例如 "openai" -> "mccf.provider.openai"。 */
	public static String providerNameKey(String providerId) {
		return "mccf.provider." + providerId;
	}

	/** 不支持"一键获取模型"的 Provider（DeepL 是固定引擎无模型概念，Mock 无真实模型）。 */
	public static final java.util.Set<String> NO_MODEL_LIST_SUPPORT = java.util.Set.of("mock", "deepl");

	private static final Gson GSON = new GsonBuilder().create();

	public boolean canEdit = false;
	public String activeProvider = "mock";
	public final Map<String, ClientProviderConfig> providers = new LinkedHashMap<>();

	/** 是否已经从服务端收到过至少一次快照（用于 Screen 判断是否还在等待数据）。 */
	public boolean hasReceivedSnapshot = false;

	private static ClientConfigState instance;

	public static ClientConfigState get() {
		if (instance == null) {
			instance = new ClientConfigState();
		}
		return instance;
	}

	public void applySnapshot(ConfigSnapshotPayload payload) {
		JsonObject root = GSON.fromJson(payload.json(), JsonObject.class);
		if (root == null) return;

		this.canEdit = root.has("canEdit") && root.get("canEdit").getAsBoolean();
		this.activeProvider = root.has("activeProvider") ? root.get("activeProvider").getAsString() : "mock";

		providers.clear();
		if (root.has("providers")) {
			JsonObject providersJson = root.getAsJsonObject("providers");
			for (String id : providersJson.keySet()) {
				JsonObject pc = providersJson.getAsJsonObject(id);
				providers.put(id, new ClientProviderConfig(
						getOrEmpty(pc, "apiKey"),
						getOrEmpty(pc, "model"),
						getOrEmpty(pc, "endpoint"),
						pc.has("isCustomEndpoint") && pc.get("isCustomEndpoint").getAsBoolean()
				));
			}
		}
		// 补齐快照里没有的 Provider（不应该发生，但防止 UI 因缺 key 报 NPE）。
		for (String id : PROVIDER_IDS) {
			providers.putIfAbsent(id, new ClientProviderConfig());
		}

		this.hasReceivedSnapshot = true;
	}

	private static String getOrEmpty(JsonObject obj, String key) {
		return obj.has(key) ? obj.get(key).getAsString() : "";
	}

	public ClientProviderConfig getOrCreate(String providerId) {
		return providers.computeIfAbsent(providerId, id -> new ClientProviderConfig());
	}

	/** 构造提交给服务端的 JSON——只包含当前内存里的编辑结果。 */
	public String buildUpdateJson() {
		JsonObject root = new JsonObject();
		root.addProperty("activeProvider", activeProvider);

		JsonObject providersJson = new JsonObject();
		for (Map.Entry<String, ClientProviderConfig> entry : providers.entrySet()) {
			ClientProviderConfig pc = entry.getValue();
			JsonObject pcJson = new JsonObject();
			pcJson.addProperty("apiKey", pc.apiKey == null ? "" : pc.apiKey);
			pcJson.addProperty("model", pc.model == null ? "" : pc.model);
			pcJson.addProperty("endpoint", pc.endpoint == null ? "" : pc.endpoint);
			pcJson.addProperty("resetEndpoint", !pc.isCustomEndpoint && (pc.endpoint == null || pc.endpoint.isBlank()));
			providersJson.add(entry.getKey(), pcJson);
		}
		root.add("providers", providersJson);

		return GSON.toJson(root);
	}
}
