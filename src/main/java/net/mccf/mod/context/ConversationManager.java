package net.mccf.mod.context;

import net.mccf.mod.MCCF;
import net.mccf.mod.config.MCCFConfig;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理所有活跃的 {@link Conversation}，负责合并、拆分与过期释放。
 *
 * 触发规则（对应设计确认）："距离 + 主动发言"：
 * 玩家必须实际说话、且当时与目标接收者处于 conversationRange 范围内，
 * 才会被加入到相应的 Conversation。仅仅"路过"或"站在附近不说话"
 * 不会把人拉进对话组——这避免了"沉默的旁观者被动获得剧透"的情况。
 *
 * 合并示例（对应设计文档）：
 *   A 与 B 聊天 -> 建立 Conversation{A,B}
 *   C 加入聊天 -> 升级为 Conversation{A,B,C}
 *   A 离开范围/长时间不说话 -> 重新计算为 Conversation{B,C}，
 *     A 之前的发言不再参与 B、C 之后对话的翻译上下文。
 *
 * 实现说明：本管理器不主动"踢出"玩家；一个玩家是否仍属于某个 Conversation，
 * 由 SpatialChatHandler 在每次有人说话时重新计算范围内的听众，并调用
 * {@link #recordUtterance} 合并/拆分。真正的"离开"体现在：
 * 该玩家不再出现在任何后续 recordUtterance 的听众集合里，
 * 且其所在 Conversation 会在 tick() 中因超时或范围重算而与之分离。
 */
public class ConversationManager {

	private final MCCFConfig config;

	/** conversationId -> Conversation */
	private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();

	/** playerId -> 该玩家当前所属的 Conversation id（一个玩家同一时刻只属于一个对话组）。 */
	private final Map<UUID, UUID> playerToConversation = new ConcurrentHashMap<>();

	public ConversationManager(MCCFConfig config) {
		this.config = config;
	}

	/**
	 * 记录一次"发言事件"：speaker 对 audience（当前范围内能听到的玩家集合）说了一句话。
	 * 这是驱动 Conversation 合并/拆分的唯一入口。
	 *
	 * 逻辑：
	 * 1. 收集 speaker 自己 + audience 中所有已经"在场"的玩家已归属的 Conversation。
	 * 2. 如果都不属于任何 Conversation -> 新建一个，成员为 speaker + audience。
	 * 3. 如果部分人已属于某个 Conversation -> 合并所有涉及到的 Conversation 为一个，
	 *    成员为其并集（原则：新人主动说话且在范围内，即并入现有对话组）。
	 * 4. 不在 audience 范围内、但仍属于旧 Conversation 的玩家不受影响——
	 *    他们是否掉线/离开该组，交由后续的距离重算或超时机制处理。
	 *
	 * @return 处理后的 Conversation，供调用方记录消息内容 / 分发字幕
	 */
	public Conversation recordUtterance(UUID speakerId, Set<UUID> audience, long currentTick) {
		List<Conversation> involved = new ArrayList<>();
		collectConversation(speakerId, involved);
		for (UUID listenerId : audience) {
			collectConversation(listenerId, involved);
		}

		Conversation target;
		if (involved.isEmpty()) {
			target = new Conversation(UUID.randomUUID(), currentTick);
			conversations.put(target.getId(), target);
		} else {
			// 合并：保留第一个作为目标，其余的成员并入目标后废弃。
			target = involved.get(0);
			for (int i = 1; i < involved.size(); i++) {
				Conversation other = involved.get(i);
				if (other.getId().equals(target.getId())) continue;
				mergeInto(target, other, currentTick);
			}
		}

		target.addParticipant(speakerId);
		playerToConversation.put(speakerId, target.getId());
		for (UUID listenerId : audience) {
			target.addParticipant(listenerId);
			playerToConversation.put(listenerId, target.getId());
		}

		return target;
	}

	private void collectConversation(UUID playerId, List<Conversation> out) {
		UUID convId = playerToConversation.get(playerId);
		if (convId == null) return;
		Conversation conv = conversations.get(convId);
		if (conv != null && !out.contains(conv)) {
			out.add(conv);
		}
	}

	private void mergeInto(Conversation target, Conversation source, long currentTick) {
		for (UUID p : source.getParticipants()) {
			target.addParticipant(p);
			playerToConversation.put(p, target.getId());
		}
		// 合并近期消息，仅保留仍在目标组内的发言者的消息，遵循"离开者的历史不延续"原则。
		for (Conversation.ContextMessage msg : source.getRecentMessages()) {
			if (target.getParticipants().contains(msg.speakerId())) {
				target.recordMessage(msg.speakerId(), msg.text(), msg.tick());
			}
		}
		conversations.remove(source.getId());
	}

	/**
	 * 显式将某玩家从其当前 Conversation 中移除（例如：SpatialChatHandler 检测到
	 * 该玩家已不在任何在场成员的听力范围内，或玩家已下线）。
	 * 对应设计文档中 "A 离开 -> 重新建立 Conversation B-C，A 的内容不再参与后续翻译"。
	 */
	public void removeParticipant(UUID playerId) {
		UUID convId = playerToConversation.remove(playerId);
		if (convId == null) return;
		Conversation conv = conversations.get(convId);
		if (conv == null) return;
		conv.removeParticipant(playerId);
		if (conv.isEmpty()) {
			conversations.remove(convId);
		}
	}

	public Conversation getConversationFor(UUID playerId) {
		UUID convId = playerToConversation.get(playerId);
		return convId == null ? null : conversations.get(convId);
	}

	/**
	 * 每 tick 调用：释放长时间无人发言的 Conversation（原则 6：Context 生命周期）。
	 * 避免 AI 引用很久以前的内容、记住不该知道的信息、以及长期上下文堆积导致的性能问题。
	 */
	public void tick(MinecraftServer server) {
		long currentTick = server.getTicks();
		long timeoutTicks = config.conversationIdleTimeoutSeconds * 20L;

		List<UUID> expired = new ArrayList<>();
		for (Conversation conv : conversations.values()) {
			if (currentTick - conv.getLastActivityTick() > timeoutTicks) {
				expired.add(conv.getId());
			}
		}

		for (UUID convId : expired) {
			Conversation conv = conversations.remove(convId);
			if (conv == null) continue;
			for (UUID p : conv.getParticipants()) {
				playerToConversation.remove(p, convId);
			}
			MCCF.LOGGER.debug("[MCCF] Conversation {} expired after {} idle seconds.",
					convId, config.conversationIdleTimeoutSeconds);
		}
	}

	/** 供调试命令使用：当前活跃对话组数量。 */
	public int getActiveConversationCount() {
		return conversations.size();
	}

	Map<UUID, Conversation> debugSnapshot() {
		return new HashMap<>(conversations);
	}
}
