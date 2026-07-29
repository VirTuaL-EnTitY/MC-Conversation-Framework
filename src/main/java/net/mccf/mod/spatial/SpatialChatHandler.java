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
 * 客户端语言检测的网络协议已实现：客户端通过 {@link net.mccf.mod.network.LanguageReportPayload}
 * 在加入服务器时上报自己的 Minecraft 语言设置，服务端在 {@link PlayerLanguageRegistry}
 * 内存维护在线玩家的目标语言。另外客户端可通过 {@link net.mccf.mod.network.ModePreferencePayload}
 * 声明自己处于纯客户端模式，此时本类对该玩家不做空间化处理（见 onChatMessage/dispatchTo 开头检查）。
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
	 * 默认返回 false：MCCF 完全接管消息分发，不让原版群发广播发生。
	 * 唯一例外：说话者本人选择了纯客户端模式——此时返回 true 放行原版广播。
	 */
	public boolean onChatMessage(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params) {
		// client-only 玩家的聊天不拦截：这个玩家明确表示只要本地翻译，服务端不应该为他做
		// 空间化处理。放行后原版全服广播照常发生，所有人（包括他自己）都收到原版 CHAT 事件——
		// 这正是 client-only 模式预期的"全服广播 + 各自本地翻译"行为。代价是这条消息不受空间化
		// 隔离约束，但这本来就是玩家主动选择放弃服务端空间化才换来的，属于知情取舍。
		if (ClientOnlyModeRegistry.isClientOnly(sender.getUuid())) {
			return true;
		}

		MinecraftServer server = sender.getServer();
		if (server == null) {
			return true; // 极端情况下退回原版行为，保证消息不丢失。
		}

		String rawText = message.getContent().getString();
		if (rawText.isBlank()) {
			return false;
		}

		// 1. 计算空间范围内的候选听众（同世界的所有其他在线玩家）。
		// 说话者本人不参与距离/遮挡判定（自己跟自己永远"听得到"，判定没有意义），
		// 但这不代表说话者应该收不到自己的回显——回显走独立分支（见下方 dispatchSelfEcho），
		// 不占用 HearingResolver 的候选名单，也不影响 Conversation 的听众集合语义。
		List<ServerPlayerEntity> candidates = server.getPlayerManager().getPlayerList().stream()
				.filter(p -> p.getWorld() == sender.getWorld())
				.filter(p -> !p.getUuid().equals(sender.getUuid()))
				.collect(Collectors.toList());

		HearingResolver.HearingResult hearing = hearingResolver.resolveAll(sender, candidates);
		List<ServerPlayerEntity> allListeners = hearing.allListeners();

		String speakerName = sender.getGameProfile().getName();

		// 无论其他人能否听到，说话者本人始终应该在自己的客户端上看到自己刚说的话——
		// 这跟原版聊天行为一致（原版广播里说话者也会收到自己发的消息），MCCF 接管分发后
		// 不该丢失这个基本体验。回显不翻译（自己的话不需要翻译给自己看），displayMode
		// 跟随"本次发言时，其他听众里哪种关系占多数"（见 dispatchSelfEcho 说明）。
		dispatchSelfEcho(sender, speakerName, rawText, hearing);

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

		// 4. 对每个听众：确定显示模式 -> 翻译 -> 发送字幕包。
		dispatchTo(hearing.visible(), sender, speakerName, rawText, sourceLang, contextMessages, "VISIBLE");
		dispatchTo(hearing.audibleOnly(), sender, speakerName, rawText, sourceLang, contextMessages, "AUDIBLE");

		return false;
	}

	/**
	 * 给说话者本人发一份"自己说的话"的回显包，不经过翻译（自己的话不需要翻译给自己看）。
	 *
	 * 为什么复用 SubtitlePayload 而不是新建一个包类型：字段完全够用——originalText 和
	 * translatedText 都填原文即可，客户端不需要区分"这是回显"还是"这是真的同语言翻译结果"，
	 * 因为无论哪种情况客户端的处理方式都一样（显示原文）。复用能减少一次
	 * PayloadTypeRegistry 注册和一处客户端分支，维护成本更低。
	 *
	 * displayMode 的选择——"跟随主导模式"：说话者本人对自己没有距离/遮挡概念（自己
	 * 永远在自己身边），没法直接复用 HearingResolver 的判定。改为统计本次发言时，
	 * 其他听众里 VISIBLE 和 AUDIBLE 两档各有多少人，人数多的一档即为"主导模式"，
	 * 自己的回显跟随这个主导模式展示——多数人能看到我时，我自己也理应用聊天框看到
	 * 自己说的话（沉浸感一致：大家都在近处对话）；多数人只能听到听不到看到我时
	 * （比如隔墙/远距离喊话），自己的回显也降级成物品栏字幕，呼应"这是一句喊出去的话
	 * 而不是面对面聊天"的情境。没有任何听众时（allListeners 为空）默认 VISIBLE——
	 * 聊天框比一闪而过的字幕更保险，避免独自一人说话时消息被错过。
	 */
	private void dispatchSelfEcho(ServerPlayerEntity sender, String speakerName, String rawText,
			HearingResolver.HearingResult hearing) {
		if (sender.networkHandler == null) return;
		String displayMode = hearing.visible().size() >= hearing.audibleOnly().size() ? "VISIBLE" : "AUDIBLE";
		SubtitlePayload echo = new SubtitlePayload(sender.getUuid(), speakerName, rawText, rawText, displayMode);
		sender.getServer().execute(() -> {
			if (sender.networkHandler != null) {
				ServerPlayNetworking.send(sender, echo);
			}
		});
	}

	private void dispatchTo(List<ServerPlayerEntity> listeners, ServerPlayerEntity sender, String speakerName,
			String rawText, String sourceLang, List<String> contextMessages, String displayMode) {
		for (ServerPlayerEntity listener : listeners) {
			// 对 client-only 听众不发字幕：避免给已经选择本地翻译的玩家发服务端字幕，
			// 造成"原版聊天 + 服务端字幕 + 本地翻译追加"三重叠加显示。这些听众会从原版
			// CHAT 广播里拿到文本走自己的本地翻译流程（前提是说话者也是 client-only 才有原版广播；
			// 若说话者非 client-only，这里跳过则该听众什么都收不到——但 client-only 听众明确表态
			// 不要服务端字幕，这是其主动选择的可见性损失，不是 bug）。
			if (ClientOnlyModeRegistry.isClientOnly(listener.getUuid())) {
				continue;
			}

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
