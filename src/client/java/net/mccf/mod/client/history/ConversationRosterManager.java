package net.mccf.mod.client.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端本地维护"某个 Conversation 当前有哪些参与者"的映射，数据来源是
 * 服务端下发的 {@code ConversationRosterPayload}（只在参与者集合真正变化
 * 时才会收到一次，见服务端 SpatialChatHandler#broadcastConversationRoster
 * 的说明）。
 *
 * 用途：{@link ChatHistoryScreen} 渲染对话分组的大标题（"LimAimo、test、Alex
 * 的对话"）需要知道"这个 Conversation 里都有谁"；渲染"XX 加入了对话"提示
 * 时需要对比"这次收到的名单"和"上一次已知的名单"算出新增的人。
 *
 * 为什么按参与者列表（{@link RosterEntry} 记录每个人的加入顺序）而不是简单的
 * Set：需要保留"谁先加入"的顺序信息，用于渲染"Alex 加入了对话"这类按时间
 * 先后排列的提示——如果只存无序集合，没法区分"这次收到的新增成员是谁"，
 * 只能重新对比全量差集，虽然也能算出来，但显式记录"加入时刻"能让历史界面
 * 未来扩展更精确的时间线展示（比如每个人是什么时候加入的），成本很低但
 * 保留了更多信息，不亏。
 */
public final class ConversationRosterManager {

	/** 单条名单记录：某个参与者 + 他的显示名，加入顺序由所在 List 的下标体现。 */
	public record RosterEntry(UUID playerId, String displayName) {}

	/** conversationId -> 当前已知的完整参与者名单（按加入顺序排列）。 */
	private static final Map<UUID, List<RosterEntry>> ROSTERS = new LinkedHashMap<>();

	private ConversationRosterManager() {}

	/**
	 * 收到服务端 ConversationRosterPayload 时调用：用服务端下发的最新完整名单
	 * 覆盖本地记录。服务端每次都发完整名单（不是增量 diff），所以这里直接整体
	 * 替换即可，不需要合并逻辑——服务端是权威数据源。
	 *
	 * @return 相比上一次已知的名单，这次新增了哪些人（用于历史界面渲染"XX 加入了
	 *         对话"提示；如果这是第一次收到这个 Conversation 的名单，返回值就是
	 *         全部参与者，此时历史界面应该展示"开始了一段新对话"而不是逐个"加入"
	 *         提示——两种场景由调用方根据"之前有没有记录"自行区分）。
	 */
	public static synchronized List<RosterEntry> update(UUID conversationId, List<UUID> ids, List<String> names) {
		List<RosterEntry> previous = ROSTERS.get(conversationId);
		List<UUID> previousIds = previous == null ? List.of() :
				previous.stream().map(RosterEntry::playerId).toList();

		List<RosterEntry> updated = new ArrayList<>(ids.size());
		List<RosterEntry> newlyAdded = new ArrayList<>();
		for (int i = 0; i < ids.size(); i++) {
			RosterEntry entry = new RosterEntry(ids.get(i), names.get(i));
			updated.add(entry);
			if (!previousIds.contains(ids.get(i))) {
				newlyAdded.add(entry);
			}
		}
		ROSTERS.put(conversationId, updated);
		return newlyAdded;
	}

	/** 是否是第一次记录这个 Conversation 的名单（用于区分"新对话开始"还是"有人加入现有对话"）。 */
	public static synchronized boolean isFirstRoster(UUID conversationId) {
		return !ROSTERS.containsKey(conversationId);
	}

	/** 获取某个 Conversation 当前已知的完整参与者名单（按加入顺序），未知则返回空列表。 */
	public static synchronized List<RosterEntry> get(UUID conversationId) {
		if (conversationId == null) return List.of();
		return ROSTERS.getOrDefault(conversationId, List.of());
	}

	/** 断开连接时清空——名单跟聊天历史记录同生命周期，不跨服务器/世界保留。 */
	public static synchronized void clear() {
		ROSTERS.clear();
	}
}
