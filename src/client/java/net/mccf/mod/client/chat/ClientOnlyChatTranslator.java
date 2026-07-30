package net.mccf.mod.client.chat;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.mccf.mod.MCCF;
import net.mccf.mod.client.config.ClientOnlyTranslationConfig;
import net.mccf.mod.client.mode.ClientOnlyModeManager;
import net.mccf.mod.translation.provider.ProviderFactory;
import net.mccf.mod.util.RateLimiter;
import net.mccf.mod.translation.provider.TranslationProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 纯客户端模式下的聊天翻译：不做任何空间化听觉判定（没有服务端参与，做不到
 * 真正的点对点分发/信息隔离），单纯把收到的每一条玩家聊天消息在本地翻译成
 * 玩家自己的语言，追加显示在聊天栏里——原始消息仍然正常显示，翻译是"追加"
 * 而不是"替换"。
 *
 * 只在 {@link ClientOnlyModeManager#isClientOnlyModeActive()} 为 true 时生效；
 * 服务器装了 MCCF 且未被手动强制切换到纯客户端模式时，翻译由服务端的
 * 空间化管线负责，这里不重复处理，避免同一条消息被翻译两次。
 *
 * 用的是 {@code ClientReceiveMessageEvents.CHAT}（信息性事件，在消息已经
 * 确定会被显示之后触发，不需要返回值），而不是 {@code ALLOW_CHAT}——
 * 因为翻译是异步的（多数 Provider 走网络请求），没法在一次同步事件回调里
 * 立刻拿到结果去替换原始文本，所以选择"让原文正常显示，翻译结果异步追加
 * 一条"的方案，而不是等翻译完成才决定是否放行原始消息。
 *
 * <p>两个调用点都会触发 {@link #translateAndAppend}：
 * 1. {@link #register()} 注册的 CHAT 事件监听器——服务器走原版全服广播时
 *    （说话者也是 client-only，或服务器根本没装 MCCF），客户端正常收到 CHAT 事件。
 * 2. MCCFClient 的 SubtitlePayload 接收器——退回方案：旧服务端不认识
 *    ModePreferencePayload，依旧拦截原版聊天改发 SubtitlePayload，客户端
 *    收不到 CHAT 事件，只能从 SubtitlePayload 里提取文本走这个方法。
 */
public final class ClientOnlyChatTranslator {

	private ClientOnlyChatTranslator() {}

	public static void register() {
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			if (!ClientOnlyModeManager.isClientOnlyModeActive()) return;

			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null) return;

			String sourceText = message.getString();
			if (sourceText.isBlank()) return;

			// sender.getId() 返回的是 MessageSender 的 UUID（与 player.getUuid() 同源可比），
			// 不是实体 int id。toString 后传给 translateAndAppend 做"是不是自己发的"判定。
			String senderUuid = (sender != null && sender.getId() != null) ? sender.getId().toString() : null;
			translateAndAppend(sourceText, senderUuid);
		});
	}

	/**
	 * 把一条原文走本地翻译后追加显示到聊天栏。供两个调用点复用：
	 * 1. {@link #register()} 注册的 CHAT 事件监听器（原版聊天广播触发）；
	 * 2. MCCFClient 的 SubtitlePayload 接收器（旧服务端退回方案触发）。
	 *
	 * 抽取成独立方法是为了避免两处复制粘贴翻译/节流/Provider 缓存逻辑——
	 * 这两处的"翻译 + 追加"行为完全一致，只有"文本从哪里来"不同。
	 *
	 * @param sourceText 原文（调用方负责保证非空、非空白）
	 * @param senderUuid 说话者 UUID 字符串，可为 null（用于"是不是自己发的"判定）
	 */
	public static void translateAndAppend(String sourceText, String senderUuid) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;

		// 自己发的消息不翻译——玩家显然已经知道自己说了什么。
		// 退回方案路径下，SubtitlePayload 的 speakerId 也可能是自己（旧服务端不知道
		// 我是 client-only，可能给我发我自己说话的字幕），同样需要跳过。
		// 跳过翻译前仍记入历史（Source.SELF）——纯客户端模式下原版聊天广播本来就正常
		// 显示自己发的消息（这条路径没有 SpatialChatHandler 拦截），但历史界面需要完整
		// 记录，所以这里补一条，跟 MCCFClient 里服务端空间化模式下自己回显的记录逻辑对齐。
		if (senderUuid != null && senderUuid.equals(client.player.getUuid().toString())) {
			net.mccf.mod.client.history.ChatHistoryManager.record(
					new net.mccf.mod.client.history.ChatHistoryEntry(
							client.player.getUuid(), client.player.getGameProfile().getName(),
							sourceText, sourceText,
							net.mccf.mod.client.history.ChatHistoryEntry.Source.SELF,
							System.currentTimeMillis()));
			return;
		}

		if (!rateLimiter.tryAcquire()) {
			MCCF.LOGGER.warn("[MCCF] Client-only translation rate limit exceeded, skipping: {}",
					sourceText.length() > 80 ? sourceText.substring(0, 80) + "..." : sourceText);
			return;
		}

		String targetLang = detectClientLanguage(client);
		TranslationProvider provider = getProvider();

		provider.translate(new TranslationProvider.TranslationRequest(
				sourceText, "auto", targetLang, List.of()
		)).thenAccept(result -> client.execute(() -> {
			if (client.player == null) return;
			String translated = result.translatedText();
			if (translated == null || translated.isBlank() || translated.equals(sourceText)) return;
			client.inGameHud.getChatHud().addMessage(
					Text.literal("⇄ " + translated).formatted(net.minecraft.util.Formatting.GRAY));
			// speakerUuid 在纯客户端模式下无法可靠拿到说话者的稳定 UUID 展示名（这条路径
			// 没有服务端 speakerName 字段，只有聊天原文），历史记录里 speakerName 留空，
			// 界面按"未知发言者"或直接不显示名字处理。
			net.mccf.mod.client.history.ChatHistoryManager.record(
					new net.mccf.mod.client.history.ChatHistoryEntry(
							senderUuid != null ? java.util.UUID.fromString(senderUuid) : new java.util.UUID(0, 0),
							"", sourceText, translated,
							net.mccf.mod.client.history.ChatHistoryEntry.Source.CLIENT_ONLY,
							System.currentTimeMillis()));
		})).exceptionally(ex -> {
			// 纯客户端模式下翻译失败（比如没配 API Key）只记日志，不刷屏聊天栏——
			// 玩家可以用配置界面的"导出日志"按钮排查，不需要每条消息都弹一次错误。
			String reason = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
			MCCF.LOGGER.warn("[MCCF] Client-only translation failed: {}", reason);
			return null;
		});
	}

	/**
	 * 获取当前配置激活的翻译 Provider，按 providerId 缓存实例。
	 *
	 * 为什么缓存 Provider 而不是每次新建：ProviderFactory.create 内部会 new 一个
	 * ProviderConfig 再 new 一个 Provider，聊天消息高频时每条都新建会产生
	 * 大量短命对象。大部分玩家在一次游戏会话里不会频繁切换 Provider，按 id 缓存
	 * 足以覆盖绝大多数场景。
	 *
	 * 缓存的代价：玩家在配置界面改了 apiKey/model/endpoint 后，缓存的 Provider
	 * 仍持有旧的 ProviderConfig 引用，不会立即生效——需要切换到别的 Provider 再
	 * 切回来才能重建。这个权衡是有意为之的：实时感知配置变更需要 ProviderConfig
	 * 自己支持"字段变更通知"或每次都比对字段，复杂度不值当；玩家改配置后切一下
	 * Provider 是可接受的操作成本。
	 */
	private static TranslationProvider cachedProvider;
	private static String cachedProviderId;

	private static TranslationProvider getProvider() {
		ClientOnlyTranslationConfig config = ClientOnlyTranslationConfig.get();
		String currentId = config.activeProvider;
		if (cachedProvider == null || !currentId.equals(cachedProviderId)) {
			cachedProvider = ProviderFactory.create(currentId, config.toProviderConfig(currentId));
			cachedProviderId = currentId;
		}
		return cachedProvider;
	}

	/**
	 * 纯客户端翻译的速率限制器：每秒最多 5 条翻译请求。
	 *
	 * 限流逻辑已抽取到 {@link RateLimiter}，这里只持有实例并调用 tryAcquire()。
	 * 抽取的原因：限流逻辑是纯并发控制，不依赖任何 Minecraft 类，混在
	 * ChatTranslator 里无法单独测试。抽成独立类后可以写 JUnit 测试覆盖
	 * 并发竞争和窗口边界场景。
	 *
	 * 为什么限流 5 条/秒：大部分翻译 API 的免费 tier 限制在每秒个位数请求（OpenAI
	 * 免费 key 约 20 RPM、DeepL 免费档短时并发也有限制），5 条是保守值。超出会触发
	 * 429 速率限制甚至临时封 Key——QA 报告的 M8 级问题就是玩家在聊天刷屏时本地翻译
	 * 狂调 API 导致 Key 被封。限流后超出的消息直接丢弃，玩家最多少看几条翻译，
	 * 不会影响游戏功能。
	 */
	private static final RateLimiter rateLimiter = new RateLimiter(1000L, 5);

	private static String detectClientLanguage(MinecraftClient client) {
		String language = client.options.language;
		return (language == null || language.isBlank()) ? "en_us" : language;
	}
}
