package net.mccf.mod.spatial;

import net.mccf.mod.MCCF;
import net.mccf.mod.config.MCCFConfig;
import net.mccf.mod.context.Conversation;
import net.mccf.mod.context.ConversationManager;
import net.mccf.mod.network.SubtitlePayload;
import net.mccf.mod.translation.TranslationService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MCCF 的核心调度点：拦截原版聊天广播，替换为"空间化 + 上下文化 + 翻译化"的
 * 点对点分发。
 *
 * 处理流程（对应设计文档的三条核心原则）：
 * 1. 拦截原版全服聊天广播（返回 false 阻止其广播，避免"信息传播到不该到达的人"）。
 * 2. 用 {@link HearingResolver} 计算说话者周围谁能"看到"/"听到"这句话
 *    （原则 2：信息只会传播到真正能够接收到它的人）。
 * 3. 把这次发言喂给 {@link ConversationManager}，动态合并/拆分对话组
 *    （原则 3：AI 不应该拥有全知视角，上下文严格限定在当前对话组内）。
 * 4. 对每个听众，用其"客户端语言"作为目标语言发起翻译（异步），
 *    翻译上下文严格限定于该 Conversation 内的近期消息。
 * 5. 翻译完成后，为每个听众单独发送一个 {@link SubtitlePayload}，
 *    displayMode 由其与说话者的距离/视线关系决定。
 *
 * 注意：这里没有实现"客户端语言检测"的具体网络协议（这需要一个配套的
 * C2S 握手包，客户端在加入服务器时上报 Options.language）。为了保持
 * 这份代码可独立编译验证，这里提供了 {@link PlayerLanguageRegistry}
 * 作为占位存储层——真实项目里在握手包处理器中调用它的 setLanguage 即可，
 * 其余翻译/字幕逻辑完全不需要改动。
 */
public class SpatialChatHandler {

	private final ConversationManager conversationManager;
	private final TranslationService translationService;
	private final MCCFConfig config;
	private final HearingResolver hearingResolver;

	public SpatialChatHandler(ConversationManager conversationManager, TranslationService translationService,
			MCCFConfig config) {
		this.conversationManager = conversationManager;
		this.translationService = translationService;
		this.config = config;
		this.hearingResolver = new HearingResolver(config);
	}

	/**
	 * 对应 {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE} 的回调签名。
	 * 始终返回 false：MCCF 完全接管消息分发，不让原版群发广播发生。
	 */
	public boolean onChatMessage(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params) {
		MinecraftServer server = sender.getServer();
		if (server == null) {
			return true; // 极端情况下退回原版行为，保证消息不丢失。
		}

		String rawText = message.getContent().getString();
		if (rawText.isBlank()) {
			return false;
		}

		// 1. 计算空间范围内的候选听众（同世界的所有其他在线玩家）。
		List<ServerPlayerEntity> candidates = server.getPlayerManager().getPlayerList().stream()
				.filter(p -> p.getWorld() == sender.getWorld())
				.filter(p -> !p.getUuid().equals(sender.getUuid()))
				.collect(Collectors.toList());

		HearingResolver.HearingResult hearing = hearingResolver.resolveAll(sender, candidates);
		List<ServerPlayerEntity> allListeners = hearing.allListeners();

		if (allListeners.isEmpty()) {
			// 没有任何人能听到——原则 2 的直接体现：这句话不会被任何人、
			// 也不会被任何 Conversation 记录，自然也不会进入任何 AI 上下文。
			return false;
		}

		// 2. 驱动 Conversation 合并/拆分（原则：距离 + 主动发言）。
		Set<UUID> audienceIds = allListeners.stream().map(ServerPlayerEntity::getUuid).collect(Collectors.toSet());
		long currentTick = server.getTicks();
		Conversation conversation = conversationManager.recordUtterance(sender.getUuid(), audienceIds, currentTick);
		conversation.recordMessage(sender.getUuid(), rawText, currentTick);

		// 3. 为该 Conversation 构造严格受限的上下文（仅限当前仍在场成员的近期发言）。
		List<String> contextMessages = conversation.getRecentMessages().stream()
				.map(Conversation.ContextMessage::text)
				.collect(Collectors.toList());

		String sourceLang = PlayerLanguageRegistry.getLanguage(sender.getUuid());
		String speakerName = sender.getGameProfile().getName();

		// 4. 对每个听众：确定显示模式 -> 翻译 -> 发送字幕包。
		dispatchTo(hearing.visible(), sender, speakerName, rawText, sourceLang, contextMessages, "VISIBLE");
		dispatchTo(hearing.audibleOnly(), sender, speakerName, rawText, sourceLang, contextMessages, "AUDIBLE");

		return false;
	}

	private void dispatchTo(List<ServerPlayerEntity> listeners, ServerPlayerEntity sender, String speakerName,
			String rawText, String sourceLang, List<String> contextMessages, String displayMode) {
		for (ServerPlayerEntity listener : listeners) {
			String targetLang = PlayerLanguageRegistry.getLanguage(listener.getUuid());

			translationService.translate(rawText, sourceLang, targetLang, contextMessages)
					.thenAccept(translated -> {
						String shownOriginal = config.showOriginalText ? rawText : "";
						SubtitlePayload payload = new SubtitlePayload(
								sender.getUuid(), speakerName, shownOriginal, translated, displayMode);
						// 网络发送必须回到服务器主线程执行。
						sender.getServer().execute(() -> {
							if (listener.networkHandler != null) {
								ServerPlayNetworking.send(listener, payload);
							}
						});
					})
					.exceptionally(ex -> {
						MCCF.LOGGER.error("[MCCF] Failed to dispatch subtitle to {}", listener.getGameProfile().getName(), ex);
						return null;
					});
		}
	}
}
