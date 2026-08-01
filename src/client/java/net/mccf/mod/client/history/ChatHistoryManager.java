package net.mccf.mod.client.history;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端本地聊天历史记录：内存环形缓冲区，不持久化到磁盘。
 *
 * 为什么不落盘：历史记录本质是"这局游戏这次连接期间看到过什么"的临时回溯工具，
 * 类似原版聊天栏的滚动历史（原版也不落盘、重进游戏就没了）。落盘会引入隐私
 * （记录了其他玩家的聊天内容）、多世界/多服务器归属混淆、文件增长清理等一堆
 * 复杂度，收益（跨会话回看）相对这次需求不是必须的——README 和用户明确要的是
 * "字幕淡出后能补看"，不是"跨局存档"。
 *
 * 为什么用固定容量环形缓冲而不是无限增长的 List：长时间挂机的服务器聊天量可能
 * 很大，无限增长会造成内存泄漏；固定 500 条覆盖"最近发生了什么"的实际需求，
 * 超出后自动丢弃最旧的一条。聊天消息和系统事件分别维护各自的环形缓冲区
 * （各 500 条上限），互不挤占彼此的容量。
 *
 * 线程安全：SubtitlePayload 接收器和 ClientOnlyChatTranslator 的回调都是通过
 * {@code client.execute(...)} 切回客户端主线程后才调用 record，因此这里不需要
 * 额外加锁；但为了防御未来有人在非主线程误调用，仍用 synchronized 包一层，
 * 代价可忽略（历史记录写入频率远低于每 tick 级别）。
 *
 * 分组渲染支持（应用户要求，参照服务端 Conversation 分组机制）：
 * {@link #groupedSnapshot()} 把所有消息/事件按 conversationId 分组、组内按
 * 时间排序，包装成 {@link ConversationGroup} 列表返回，直接供
 * {@code ChatHistoryScreen} 渲染——渲染代码不需要自己再做分组/排序，
 * 只管遍历这个已经整理好的结构。conversationId 为 null 的消息（纯客户端
 * 模式下的 CLIENT_ONLY 消息，没有服务端 Conversation 概念）各自单独成组
 * （用消息自身的一个合成 key，保证不会被错误地跟别的无归属消息混在一起）。
 */
public final class ChatHistoryManager {

	private static final int MAX_ENTRIES = 500;

	private static final Deque<ChatHistoryEntry> ENTRIES = new ArrayDeque<>(MAX_ENTRIES);
	private static final Deque<ChatHistorySystemEvent> SYSTEM_EVENTS = new ArrayDeque<>(MAX_ENTRIES);

	private ChatHistoryManager() {}

	public static synchronized void record(ChatHistoryEntry entry) {
		if (ENTRIES.size() >= MAX_ENTRIES) {
			ENTRIES.removeFirst();
		}
		ENTRIES.addLast(entry);
	}

	public static synchronized void recordSystemEvent(ChatHistorySystemEvent event) {
		if (SYSTEM_EVENTS.size() >= MAX_ENTRIES) {
			SYSTEM_EVENTS.removeFirst();
		}
		SYSTEM_EVENTS.addLast(event);
	}

	/** 按时间正序返回当前所有聊天消息的快照（不含系统事件；供不需要分组的旧调用方使用）。 */
	public static synchronized List<ChatHistoryEntry> snapshot() {
		return new ArrayList<>(ENTRIES);
	}

	/**
	 * 一个对话分组块，对应服务端的一个 Conversation（或者一条无归属的
	 * CLIENT_ONLY 消息单独成组）——{@link ChatHistoryScreen} 按这个结构渲染
	 * 大标题（参与者名单）+ 时间线（消息与系统提示混排，按时间正序）。
	 *
	 * @param conversationId  分组依据；无归属消息时为 null
	 * @param participantNames 这个分组当前已知的参与者显示名（来自
	 *                         {@link ConversationRosterManager}，无归属消息
	 *                         时为空列表——没有服务端名单可用）
	 * @param items           这个分组内的所有消息/系统事件，按时间正序排列
	 * @param groupStartMillis 这个分组里最早一条记录的时间戳，用于分组之间
	 *                         按时间先后整体排序（最新的分组排在最前面，
	 *                         用组内最后一条的时间比较，见 groupedSnapshot 实现）
	 */
	public record ConversationGroup(
			UUID conversationId,
			List<String> participantNames,
			List<ChatTimelineItem> items,
			long groupStartMillis
	) {}

	/**
	 * 把所有聊天消息 + 系统事件按 conversationId 分组、组内按时间正序排列，
	 * 分组之间按"组内最新一条消息的时间"倒序排列（最近活跃的对话排最前面，
	 * 与聊天历史界面"最想看到最近发生了什么"的直觉一致）。
	 */
	public static synchronized List<ConversationGroup> groupedSnapshot() {
		// key：真实 conversationId，或者对于无归属消息，用一个基于该消息自身
		// 身份（speakerId + timestampMillis 组合）合成的字符串 key，保证每条
		// 无归属消息各自独立成组，不会被意外聚在一起（它们之间没有任何关联性，
		// 混在一起显示反而误导，参照 CLIENT_ONLY 消息本来就没有"对话"概念）。
		Map<Object, List<ChatTimelineItem>> byKey = new LinkedHashMap<>();

		for (ChatHistoryEntry entry : ENTRIES) {
			Object key = entry.conversationId() != null
					? entry.conversationId()
					: "solo:" + entry.speakerId() + ":" + entry.timestampMillis();
			byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new ChatTimelineItem.Message(entry));
		}
		for (ChatHistorySystemEvent event : SYSTEM_EVENTS) {
			// 系统事件恒有 conversationId（见 ChatHistorySystemEvent 类文档），
			// 不会走到无归属分支。
			byKey.computeIfAbsent(event.conversationId(), k -> new ArrayList<>())
					.add(new ChatTimelineItem.SystemEvent(event));
		}

		List<ConversationGroup> groups = new ArrayList<>();
		for (Map.Entry<Object, List<ChatTimelineItem>> e : byKey.entrySet()) {
			List<ChatTimelineItem> items = e.getValue();
			items.sort(java.util.Comparator.comparingLong(ChatTimelineItem::timestampMillis));

			UUID conversationId = e.getKey() instanceof UUID uuid ? uuid : null;
			List<String> participantNames = conversationId != null
					? ConversationRosterManager.get(conversationId).stream()
							.map(ConversationRosterManager.RosterEntry::displayName)
							.toList()
					: List.of();

			long groupStart = items.get(0).timestampMillis();
			groups.add(new ConversationGroup(conversationId, participantNames, items, groupStart));
		}

		// 按"组内最后一条的时间"倒序——最近有新消息的对话排最前面。
		groups.sort((a, b) -> {
			long lastA = a.items().get(a.items().size() - 1).timestampMillis();
			long lastB = b.items().get(b.items().size() - 1).timestampMillis();
			return Long.compare(lastB, lastA);
		});

		return groups;
	}

	/** 断开连接时清空——历史记录不跨服务器/世界保留，避免不同服务器的聊天记录混在一起造成误解。 */
	public static synchronized void clear() {
		ENTRIES.clear();
		SYSTEM_EVENTS.clear();
	}
}
