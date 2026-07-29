package net.mccf.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.mccf.mod.MCCF;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCCF 服务端配置。管理员可修改 config/mccf/config.json 来调整行为，
 * 也可以通过游戏内配置界面（ModMenu 集成 / 按键呼出）修改，二者共用同一份文件。
 *
 * 所有距离数值都以"格"（block）为单位，与 Minecraft 原版距离单位一致。
 */
public class MCCFConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve(MCCF.MOD_ID);
	private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

	/** 玩家彼此可见字幕（悬浮在说话者头顶）的最大距离，单位：格。 */
	public double subtitleVisibleRange = 32.0;

	/** 玩家仍可"听到"但看不到说话者的最大距离，单位：格。超出此距离完全收不到消息。 */
	public double hearingRange = 48.0;

	/**
	 * 加入 / 保留在 Conversation 中所需的最大距离。
	 * 必须 <= hearingRange，否则会出现"能听到但不在任何对话组"的悖论状态。
	 */
	public double conversationRange = 48.0;

	/** 一个 Conversation 在无人发言后，闲置多少秒后自动释放（避免过期上下文残留）。 */
	public int conversationIdleTimeoutSeconds = 120;

	/** 射线遮挡检测：是否启用（关闭后仅使用距离判定，性能更好但不考虑墙体）。 */
	public boolean enableOcclusionCheck = true;

	/** 当前启用的翻译 Provider ID，对应某个已注册 TranslationProvider 的 ID。 */
	public String activeProvider = "mock";

	/** 是否在字幕中同时显示原文和译文。 */
	public boolean showOriginalText = true;

	/**
	 * 每个 Provider 的独立配置（API Key / 模型名 / host），key 为 Provider ID。
	 * 即使某个 Provider 当前未激活，它的配置也会保留在这里，方便随时切换。
	 */
	public Map<String, ProviderConfig> providers = defaultProviderConfigs();

	private static Map<String, ProviderConfig> defaultProviderConfigs() {
		Map<String, ProviderConfig> map = new LinkedHashMap<>();
		for (String id : ProviderDefaults.all().keySet()) {
			ProviderDefaults.Defaults d = ProviderDefaults.get(id);
			map.put(id, new ProviderConfig("", d.model(), d.endpoint()));
		}
		return map;
	}

	/** 取某个 Provider 的配置，若不存在（例如旧配置文件缺字段）则返回一份默认值并写回。 */
	public ProviderConfig getProviderConfig(String providerId) {
		return providers.computeIfAbsent(providerId, id -> defaultProviderConfigs().getOrDefault(id, new ProviderConfig()));
	}

	public static MCCFConfig loadOrCreate() {
		try {
			if (Files.exists(CONFIG_FILE)) {
				try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
					MCCFConfig loaded = GSON.fromJson(reader, MCCFConfig.class);
					if (loaded != null) {
						if (loaded.providers == null) {
							loaded.providers = defaultProviderConfigs();
						} else {
							// 补齐新增 Provider 的默认配置（比如从旧版本升级上来，缺少 kimi/deepseek 字段）。
							defaultProviderConfigs().forEach(loaded.providers::putIfAbsent);
						}
						loaded.validateAndClamp();
						MCCF.LOGGER.info("[MCCF] Loaded config from {}", CONFIG_FILE);
						return loaded;
					}
				}
			}
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to read config, falling back to defaults.", e);
		}

		MCCFConfig defaults = new MCCFConfig();
		defaults.validateAndClamp();
		defaults.save();
		return defaults;
	}

	/**
	 * 校验并修正 conversationRange 与 hearingRange 的关系。
	 *
	 * 为什么 clamp 而不是抛异常：配置文件可能被管理员手动改错，抛异常会导致
	 * 服务器启动失败（loadOrCreate 的调用方没有 try-catch 的预期，异常会
	 * 一路抛到 ModInitializer.onInitialize 导致整个模组加载失败），clamp
	 * 到合法值更友好——服务器能正常启动，日志里会有警告提醒管理员修正。
	 */
	private void validateAndClamp() {
		if (conversationRange > hearingRange) {
			MCCF.LOGGER.warn("[MCCF] conversationRange ({}) > hearingRange ({}), clamping conversationRange to hearingRange. " +
					"能听到但不在对话组的悖论状态已通过 clamp 规避，请检查 config.json。",
					conversationRange, hearingRange);
			conversationRange = hearingRange;
		}
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_DIR);
			try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Failed to save config.", e);
		}
	}
}
