package net.mccf.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.mccf.mod.command.ConfigSyncHandler;
import net.mccf.mod.command.MCCFCommand;
import net.mccf.mod.config.MCCFConfig;
import net.mccf.mod.context.ConversationManager;
import net.mccf.mod.dictionary.WorldDictionary;
import net.mccf.mod.network.ConfigSnapshotPayload;
import net.mccf.mod.network.LanguageReportPayload;
import net.mccf.mod.network.ModePreferencePayload;
import net.mccf.mod.network.ModelsResultPayload;
import net.mccf.mod.network.RequestConfigPayload;
import net.mccf.mod.network.RequestModelsPayload;
import net.mccf.mod.network.SubtitlePayload;
import net.mccf.mod.network.UpdateConfigPayload;
import net.mccf.mod.spatial.ClientOnlyModeRegistry;
import net.mccf.mod.spatial.PlayerLanguageRegistry;
import net.mccf.mod.spatial.SpatialChatHandler;
import net.mccf.mod.translation.TranslationService;
import net.mccf.mod.translation.provider.MockTranslationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCCF (MC Conversation Framework) 主入口。
 *
 * 整个模组围绕三个核心原则运作，详见项目说明：
 * 1. 语言不再是交流障碍 -> {@link TranslationService}
 * 2. 信息只会传播到真正能够接收到它的人 -> {@link SpatialChatHandler}
 * 3. AI 不应该拥有全知视角，而应该像玩家的感官一样工作 -> {@link ConversationManager}
 *
 * 本类只负责：加载配置、注册网络通道、挂载事件、启动各子系统的生命周期任务。
 * 具体逻辑全部委托给对应子系统，保持入口类简洁。
 */
public class MCCF implements ModInitializer {

	public static final String MOD_ID = "mccf";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 全局单例引用，方便命令/事件访问核心子系统。生命周期与服务器一致。 */
	private static ConversationManager conversationManager;
	private static TranslationService translationService;
	private static WorldDictionary worldDictionary;
	private static MCCFConfig config;

	@Override
	public void onInitialize() {
		LOGGER.info("[MCCF] Initializing MC Conversation Framework...");

		// 1. 加载配置与世界词典
		config = MCCFConfig.loadOrCreate();
		worldDictionary = WorldDictionary.loadOrCreate();

		// 2. 初始化翻译服务，注册全部内置 Provider（Mock + 7 个真实 API）并激活配置中指定的 Provider。
		//    每个 Provider 从 config.providers 里读取自己的 API Key/模型/host。
		translationService = new TranslationService(worldDictionary);
		registerAllProviders();

		// 3. 初始化对话上下文管理器
		conversationManager = new ConversationManager(config);

		// 4. 注册网络负载类型（服务端 <-> 客户端）
		PayloadTypeRegistry.playS2C().register(SubtitlePayload.ID, SubtitlePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(LanguageReportPayload.ID, LanguageReportPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestConfigPayload.ID, RequestConfigPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ConfigSnapshotPayload.ID, ConfigSnapshotPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(UpdateConfigPayload.ID, UpdateConfigPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestModelsPayload.ID, RequestModelsPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ModelsResultPayload.ID, ModelsResultPayload.CODEC);
		// 客户端模式偏好上报通道：客户端告诉服务端"我要不要纯客户端模式"。
		// 旧服务端没注册这个通道，客户端 canSend 检查会返回 false，自动退回从 SubtitlePayload 取文本的方案。
		PayloadTypeRegistry.playC2S().register(ModePreferencePayload.ID, ModePreferencePayload.CODEC);

		// 5. 拦截原版聊天事件，交给空间化处理器
		SpatialChatHandler chatHandler = new SpatialChatHandler(conversationManager, translationService, config);
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(chatHandler::onChatMessage);

		// 6. 每 tick 驱动 Conversation 生命周期检查（超时自动释放等）
		ServerTickEvents.END_SERVER_TICK.register(conversationManager::tick);

		// 7. 接收客户端语言上报，写入 PlayerLanguageRegistry
		ServerPlayNetworking.registerGlobalReceiver(LanguageReportPayload.ID, (payload, context) ->
				PlayerLanguageRegistry.setLanguage(context.player().getUuid(), payload.languageCode()));

		// 7a. 接收客户端模式偏好上报，写入 ClientOnlyModeRegistry。
		//     用 server.execute 落回主线程再写，避免与 SpatialChatHandler 主线程读取并发读到半状态
		//     （虽然 ConcurrentHashMap 本身线程安全，但保持单线程写能让后续 SpatialChatHandler 的判定
		//      与注册表更新之间有明确的 happens-before 关系，调试时也更容易推理）。
		ServerPlayNetworking.registerGlobalReceiver(ModePreferencePayload.ID, (payload, context) -> {
			var playerId = context.player().getUuid();
			context.server().execute(() ->
					ClientOnlyModeRegistry.setClientOnly(playerId, payload.clientOnlyMode()));
		});

		// 7b. 配置界面：客户端请求快照 -> 服务端按其 op 状态构造并回发
		ServerPlayNetworking.registerGlobalReceiver(RequestConfigPayload.ID, (payload, context) -> {
			var player = context.player();
			String snapshotJson = ConfigSyncHandler.buildSnapshotJson(player, config);
			context.server().execute(() ->
					ServerPlayNetworking.send(player, new ConfigSnapshotPayload(snapshotJson)));
		});

		// 7c. 配置界面：客户端提交修改 -> 服务端校验 op 权限后应用并保存
		ServerPlayNetworking.registerGlobalReceiver(UpdateConfigPayload.ID, (payload, context) -> {
			var player = context.player();
			context.server().execute(() -> {
				var error = ConfigSyncHandler.applyUpdateJson(player, config, payload.json());
				// 无论成功与否，都回发最新快照，让客户端界面反映真实的服务端状态
				// （如果被拒绝，玩家会看到配置没有变化，而不是界面显示了不存在的修改）。
				String snapshotJson = ConfigSyncHandler.buildSnapshotJson(player, config);
				ServerPlayNetworking.send(player, new ConfigSnapshotPayload(snapshotJson));
				error.ifPresent(msg -> player.sendMessage(net.minecraft.text.Text.literal("[MCCF] " + msg), false));
			});
		});

		// 7d. 配置界面："一键获取模型"——用临时 Key/endpoint 查询，不落盘
		ServerPlayNetworking.registerGlobalReceiver(RequestModelsPayload.ID, (payload, context) -> {
			var player = context.player();
			ConfigSyncHandler.handleModelsRequest(player, config, payload.json())
					.thenAccept(resultJson -> context.server().execute(() ->
							ServerPlayNetworking.send(player, new ModelsResultPayload(resultJson))));
		});

		// 8. 玩家离线时清理其语言记录、模式偏好与所属 Conversation（避免过期占用）
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			var playerId = handler.getPlayer().getUuid();
			PlayerLanguageRegistry.remove(playerId);
			ClientOnlyModeRegistry.remove(playerId);
			conversationManager.removeParticipant(playerId);
		});

		// 9. 注册 /mccf 管理命令
		MCCFCommand.register();

		LOGGER.info("[MCCF] Initialization complete.");
	}

	public static ConversationManager getConversationManager() {
		return conversationManager;
	}

	public static TranslationService getTranslationService() {
		return translationService;
	}

	public static WorldDictionary getWorldDictionary() {
		return worldDictionary;
	}

	public static MCCFConfig getConfig() {
		return config;
	}

	/**
	 * 完整重载：从磁盘重新读取 config.json 与 dictionary.json，并重建所有
	 * TranslationProvider 实例、清空翻译缓存。供 /mccf reload 命令调用。
	 *
	 * 为什么 reload 要完整读盘 + 重建 + 清缓存三者同步：
	 * 配置变更会同时影响三处状态——配置对象本身的值（如 hearingRange）、
	 * Provider 实例（API Key/模型/host 改了就要重建）、以及翻译缓存
	 * （旧缓存是基于旧 Provider 和旧词典生成的，必须作废）。只做其中
	 * 一步会导致新旧状态不一致，例如换了 API Key 但缓存里还是旧 Provider
	 * 的翻译结果，管理员会以为 reload 没生效。
	 *
	 * 为什么不直接替换 config/dictionary 引用而是原地拷贝字段：
	 * ConversationManager、SpatialChatHandler、HearingResolver 都在构造时
	 * 持有了 config 的引用且字段是 final，如果换成新对象，这些子系统还会
	 * 指向旧 config，reload 对它们不生效。原地修改字段值能让所有持有引用
	 * 的子系统立即看到新值。WorldDictionary 同理——TranslationService 持有
	 * final 的 dictionary 引用，所以也要原地清空再填充 entries，而不是替换
	 * 整个对象。
	 */
	public static void reload() {
		// 1. 重新从磁盘读取配置，把字段原地拷贝到现有 config 对象，
		//    让所有持有 config 引用的子系统（ConversationManager 等）立即看到新值。
		MCCFConfig fresh = MCCFConfig.loadOrCreate();
		config.subtitleVisibleRange = fresh.subtitleVisibleRange;
		config.hearingRange = fresh.hearingRange;
		config.conversationRange = fresh.conversationRange;
		config.conversationIdleTimeoutSeconds = fresh.conversationIdleTimeoutSeconds;
		config.enableOcclusionCheck = fresh.enableOcclusionCheck;
		config.activeProvider = fresh.activeProvider;
		config.showOriginalText = fresh.showOriginalText;
		config.providers = fresh.providers;

		// 2. 重新从磁盘读取词典，原地清空再填充 entries。
		//    TranslationService 持有 final 的 dictionary 引用，不能替换对象。
		WorldDictionary freshDict = WorldDictionary.loadOrCreate();
		worldDictionary.getEntries().clear();
		worldDictionary.getEntries().putAll(freshDict.getEntries());

		// 3. 用新配置重建所有 Provider 实例（registerAllProviders 内部会读取
		//    config.providers 里的 API Key/模型/host 重新构造每个 Provider）。
		registerAllProviders();

		// 4. 清空翻译缓存。registerAllProviders 末尾已调过一次 clearCache，
		//    这里显式再调一次保证语义清晰，也防御未来 registerAllProviders
		//    实现变动后漏调的情况——reload 的契约就是"缓存必须清空"。
		translationService.clearCache();

		LOGGER.info("[MCCF] Config and dictionary reloaded from disk.");
	}

	/**
	 * 用当前 {@link #config} 里的 Provider 配置（API Key / 模型 / host）
	 * 重新构造全部 Provider 实例并注册。每次配置界面/命令改动了某个
	 * Provider 的设置后都应该调用一次，保证正在使用的 Provider 实例
	 * 反映最新配置（Provider 实例内部持有配置的引用，不是拷贝，所以
	 * 简单起见这里直接整体重建，逻辑更简单也不容易遗漏）。
	 */
	public static void registerAllProviders() {
		translationService.registerProvider(new MockTranslationProvider());
		for (String id : java.util.List.of("openai", "claude", "gemini", "deepl", "kimi", "deepseek", "ollama")) {
			translationService.registerProvider(
					net.mccf.mod.translation.provider.ProviderFactory.create(id, config.getProviderConfig(id)));
		}
		if (!translationService.setActiveProvider(config.activeProvider)) {
			LOGGER.warn("[MCCF] Configured provider '{}' not found, falling back to mock.", config.activeProvider);
			translationService.setActiveProvider(MockTranslationProvider.ID);
		}
		translationService.clearCache();
	}
}
