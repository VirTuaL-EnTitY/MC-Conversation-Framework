package net.mccf.mod.client.history;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
 * 超出后自动丢弃最旧的一条。
 *
 * 线程安全：SubtitlePayload 接收器和 ClientOnlyChatTranslator 的回调都是通过
 * {@code client.execute(...)} 切回客户端主线程后才调用 record，因此这里不需要
 * 额外加锁；但为了防御未来有人在非主线程误调用，仍用 synchronized 包一层，
 * 代价可忽略（历史记录写入频率远低于每 tick 级别）。
 */
public final class ChatHistoryManager {

	private static final int MAX_ENTRIES = 500;

	private static final Deque<ChatHistoryEntry> ENTRIES = new ArrayDeque<>(MAX_ENTRIES);

	private ChatHistoryManager() {}

	public static synchronized void record(ChatHistoryEntry entry) {
		if (ENTRIES.size() >= MAX_ENTRIES) {
			ENTRIES.removeFirst();
		}
		ENTRIES.addLast(entry);
	}

	/** 按时间正序返回当前所有记录的快照（供历史界面渲染，倒序展示由界面自己处理）。 */
	public static synchronized List<ChatHistoryEntry> snapshot() {
		return new ArrayList<>(ENTRIES);
	}

	/** 断开连接时清空——历史记录不跨服务器/世界保留，避免不同服务器的聊天记录混在一起造成误解。 */
	public static synchronized void clear() {
		ENTRIES.clear();
	}
}
