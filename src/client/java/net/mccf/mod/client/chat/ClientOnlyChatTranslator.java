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
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	/**
	 * 从聊天文本中匹配 "<玩家名> 消息内容" 前缀的正则。
	 *
	 * 1.1.2 修复"纯客户端模式下历史记录 speakerName 永远为空"的 bug：
	 * ClientReceiveMessageEvents.CHAT 的 sender 参数虽然带 UUID，
	 * 但拿不到稳定的显示名（只有 UUID，没有名字），历史界面因此显示 "?"。
	 * 退回方案是用聊天文本本身的前缀解析——原版聊天格式是 "<Player> text"，
	 * 用正则把 Player 部分抠出来作为 speakerName。
	 *
	 * 正则限制：只匹配非贪婪的尖括号内容，且要求括号内至少 1 字符、最多 16 字符
	 * （Minecraft 玩家名长度上限）。这不覆盖所有 Mod 修改过的聊天格式，但
	 * 原版聊天和绝大多数 Mod 都遵循 "<Name> " 前缀约定。匹配不到时返回 null，
	 * 调用方继续走"speakerName 留空"的旧路径。
	 */
	private static final Pattern SPEAKER_NAME_PATTERN = Pattern.compile("^<([^<>]{1,16})>\\s");

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
		translateAndAppend(sourceText, senderUuid, null);
	}

	/**
	 * 带 speakerName 提示的重载——供 SubtitlePayload 退回路径使用。
	 *
	 * SubtitlePayload 自带 speakerName 字段（服务端从 GameProfile 取的权威名字），
	 * 比从聊天文本 "<玩家名>" 前缀解析更可靠。CHAT 事件路径没有这个提示，
	 * 仍走 {@link #parseSpeakerName} 从文本解析。
	 *
	 * @param speakerNameHint 说话者显示名提示，null 表示无提示（走文本解析）
	 */
	public static void translateAndAppend(String sourceText, String senderUuid, String speakerNameHint) {
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
							System.currentTimeMillis(), null, null, null));
			return;
		}

		if (!rateLimiter.tryAcquire()) {
			MCCF.LOGGER.warn("[MCCF] Client-only translation rate limit exceeded, skipping: {}",
					sourceText.length() > 80 ? sourceText.substring(0, 80) + "..." : sourceText);
			return;
		}

		String targetLang = detectClientLanguage(client);
		TranslationProvider provider = getProvider();

		// 预先解析说话者名（即使翻译失败也能用于历史记录），失败时返回 null，
		// 历史记录会用空字符串作为 speakerName（界面显示 "?"）。
		// speakerNameHint 非 null 时（SubtitlePayload 退回路径）优先使用，避免
		// 从不带 "<>" 前缀的纯文本里解析不到名字而留空。
		String speakerName = speakerNameHint != null ? speakerNameHint : parseSpeakerName(sourceText);

		provider.translate(new TranslationProvider.TranslationRequest(
				sourceText, "auto", targetLang, List.of()
		)).thenAccept(result -> client.execute(() -> {
			if (client.player == null) return;
			String translated = result.translatedText();
			if (translated == null || translated.isBlank() || translated.equals(sourceText)) return;
			client.inGameHud.getChatHud().addMessage(
					Text.literal("⇄ " + translated).formatted(Formatting.GRAY));
			net.mccf.mod.client.history.ChatHistoryManager.record(
					new net.mccf.mod.client.history.ChatHistoryEntry(
							parseSenderUuid(senderUuid),
							speakerName != null ? speakerName : "",
							sourceText, translated,
							net.mccf.mod.client.history.ChatHistoryEntry.Source.CLIENT_ONLY,
							System.currentTimeMillis(), null, null, null));
		})).exceptionally(ex -> {
			// 1.1.2 修复"翻译失败时玩家完全无感知"：
			// 旧版只记日志，玩家看到聊天栏里的原文不会意识到"翻译失败"——会以为是
			// "对方就说了这句话"或"对方也懂我的语言"。新版增加去重提示：60 秒内同一
			// 错误消息只给玩家发一次聊天栏提示，避免刷屏但仍保证玩家至少感知到一次失败。
			// 不为每条失败都提示的原因：聊天刷屏时可能秒级失败数十次，全提示会反过来
			// 把玩家的聊天栏刷爆，体验比"静默失败"更差。
			String reason = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
			MCCF.LOGGER.warn("[MCCF] Client-only translation failed: {}", reason);
			notifyPlayerOfFailure(client, reason);
			return null;
		});
	}

	/**
	 * 给玩家发一次去重的翻译失败提示。
	 *
	 * 去重策略：以 reason 字符串为 key 记录"最近一次提示时间"，60 秒内同一 reason
	 * 不再提示。换 Provider / 换 API Key 后失败原因通常会变，新 reason 会立即提示，
	 * 让玩家感知"现在的失败是新问题"。
	 *
	 * 为什么用 static 字段而不是实例字段：本类是 utility 类（私有构造器），
	 * 所有方法都是 static，状态自然也用 static。这导致状态在客户端进程内全局共享，
	 * 不能区分"不同服务器"——但翻译失败的原因通常是 API Key 错误、网络超时等
	 * 跨服务器共性问题，全局去重足够。
	 */
	private static final java.util.Map<String, Long> lastFailureNotifyMillis = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long FAILURE_NOTIFY_DEDUP_MILLIS = 60_000L;

	private static void notifyPlayerOfFailure(MinecraftClient client, String reason) {
		long now = System.currentTimeMillis();
		Long last = lastFailureNotifyMillis.get(reason);
		if (last != null && now - last < FAILURE_NOTIFY_DEDUP_MILLIS) {
			return; // 同一 reason 60 秒内已提示过，跳过避免刷屏
		}
		lastFailureNotifyMillis.put(reason, now);

		client.execute(() -> {
			if (client.player == null) return;
			// 灰色文字提示玩家翻译失败，并附简要原因。reason 可能很长（HTTP 响应体片段），
			// 截断到 100 字符避免聊天栏被单行错误占满。
			String truncated = reason != null && reason.length() > 100
					? reason.substring(0, 100) + "..." : String.valueOf(reason);
			client.inGameHud.getChatHud().addMessage(
					Text.literal("[MCCF] 翻译失败：" + truncated + "（详见日志）")
							.formatted(Formatting.YELLOW));
		});
	}

	/**
	 * 从聊天文本里解析 "<玩家名> 消息" 前缀。
	 *
	 * 不破坏原 sourceText 的内容——纯客户端模式下整条 sourceText 都会被送去翻译
	 * （包括 "<玩家名>" 前缀），这是历史行为不在本次修复范围内改动。这里只是从
	 * 同一份文本里抠出玩家名用于历史记录展示，不修改翻译输入。
	 */
	private static String parseSpeakerName(String sourceText) {
		if (sourceText == null || sourceText.isEmpty()) return null;
		Matcher matcher = SPEAKER_NAME_PATTERN.matcher(sourceText);
		return matcher.find() ? matcher.group(1) : null;
	}

	/**
	 * 安全解析 UUID 字符串。
	 *
	 * 1.1.2 修复"senderUuid 非 null 但不是合法 UUID 格式时 UUID.fromString 抛
	 * IllegalArgumentException 被异步调度器吞掉"的问题。某些 Mod 可能修改
	 * MessageSender 返回非标准 ID，这里捕获异常退化成零 UUID，保证翻译流程继续。
	 */
	private static UUID parseSenderUuid(String senderUuid) {
		if (senderUuid == null) return new UUID(0, 0);
		try {
			return UUID.fromString(senderUuid);
		} catch (IllegalArgumentException e) {
			MCCF.LOGGER.warn("[MCCF] Invalid sender UUID format, using zero UUID: {}", senderUuid);
			return new UUID(0, 0);
		}
	}

	/**
	 * 获取当前配置激活的翻译 Provider，按 providerId 缓存实例。
	 *
	 * 为什么缓存 Provider 而不是每次新建：ProviderFactory.create 内部会 new 一个
	 * ProviderConfig 再 new 一个 Provider，聊天消息高频时每条都新建会产生
	 * 大量短命对象。大部分玩家在一次游戏会话里不会频繁切换 Provider，按 id 缓存
	 * 足以覆盖绝大多数场景。
	 *
	 * 1.1.2 修复"改 API Key 后翻译仍用旧 Key"：旧版缓存永不失效，玩家改 Key 后
	 * 必须切 Provider 再切回来才能刷新——这个限制太隐晦，玩家会以为是 Key 没保存
	 * 成功反复改。新版增加 invalidateCache() 方法，由 ClientOnlyTranslationConfig
	 * 在 save() 完成后回调触发，保证"保存即刷新"。Provider 切换时（cachedProviderId
	 * 变化）也自然重建，无需额外处理。
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
	 * 让外部（{@link ClientOnlyTranslationConfig#save()}）通知"配置已变更"，
	 * 失效缓存的 Provider 实例，下次 getProvider() 会用最新配置重建。
	 *
	 * 为什么不直接在 getProvider 里每次都比对字段：实时感知配置变更需要
	 * ProviderConfig 自己支持"字段变更通知"或在 getProvider 里逐字段 diff，
	 * 复杂度不值当；保存路径是已知且单一的（玩家在配置界面点"保存"），
	 * 在保存后回调失效缓存是最简单可靠的方案。
	 */
	public static void invalidateProviderCache() {
		cachedProvider = null;
		cachedProviderId = null;
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
	 *
	 * 1.1.2 升级：RateLimiter 从固定窗口改为滑动窗口，避免窗口边界附近的 2 倍突刺
	 * 触发上游 API 限流。详见 RateLimiter 类注释。
	 */
	private static final RateLimiter rateLimiter = new RateLimiter(1000L, 5);

	private static String detectClientLanguage(MinecraftClient client) {
		String language = client.options.language;
		return (language == null || language.isBlank()) ? "en_us" : language;
	}
}
