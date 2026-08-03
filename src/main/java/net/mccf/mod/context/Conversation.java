package net.mccf.mod.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一个 Conversation 代表"当前能够互相交流的一组玩家"及其共享的上下文。
 *
 * 这是"AI 不是全知"原则的核心落地：
 * - AI Provider 在翻译时只能看到某个 Conversation 内部的消息（见
 *   {@link #getRecentMessages()}），不会、也无法访问服务器上其他任何对话。
 * - 一个玩家离开对话组后，其历史发言不再参与该组后续的翻译上下文。
 * - Conversation 在无人发言一段时间后自动过期释放（见 ConversationManager#tick），
 *   防止 AI 引用陈旧内容，也避免长期上下文导致的性能问题。
 *
 * 上下文范围（0.16.0 起的最终方案）：一个 Conversation 从创建到过期释放的整个
 * 生命周期内，所有参与者说过的话都作为翻译上下文，不再做任何条数截断——应用户
 * 明确要求"一个 Conversation 从开始到结束作为完整上下文"。生命周期的终结由
 * ConversationManager 的 idle timeout（默认 120 秒无人发言）控制，超时后整个
 * Conversation 被丢弃，下一次有人发言会新建一个新的对话组。这意味着上下文不会
 * 跨对话组泄露，也不会无限累积——长对话期间确实会让 prompt 变长、token 消耗
 * 增加，这是用户知情接受的取舍（用户明确选择"完全去掉截断"而非软上限方案）。
 *
 * Conversation 本身不做任何空间判定，只是一个"当前参与者 + 历史消息"的容器；
 * 判定谁该加入/离开由 {@link ConversationManager} 结合 SpatialChatHandler 完成。
 */
public class Conversation {

	private final UUID id;
	private final Set<UUID> participants = new LinkedHashSet<>();
	private final Deque<ContextMessage> recentMessages = new ArrayDeque<>();
	private long lastActivityTick;

	public Conversation(UUID id, long currentTick) {
		this.id = id;
		this.lastActivityTick = currentTick;
	}

	public UUID getId() {
		return id;
	}

	public Set<UUID> getParticipants() {
		return participants;
	}

	/** @return 是否真的加入了新成员（如果该玩家已经在 participants 里，返回 false）。 */
	public boolean addParticipant(UUID playerId) {
		return participants.add(playerId);
	}

	public void removeParticipant(UUID playerId) {
		participants.remove(playerId);
		// 该玩家离开后，其发言记录不应继续参与后续翻译上下文（原则 2 & 6）。
		recentMessages.removeIf(msg -> msg.speakerId().equals(playerId));
	}

	public boolean isEmpty() {
		return participants.isEmpty();
	}

	public long getLastActivityTick() {
		return lastActivityTick;
	}

	/**
	 * 记录一条新消息并刷新活跃时间。
	 *
	 * 0.16.0 起不再做条数截断——整个 Conversation 生命周期内的所有消息都保留作为
	 * 翻译上下文（应用户明确要求"一个 Conversation 从开始到结束作为完整上下文"）。
	 * 上下文不会无限增长：ConversationManager 的 idle timeout 会在无人发言一段时间
	 * 后整体释放 Conversation，下一次发言新建新组。早期版本的 MAX_CONTEXT_MESSAGES=20
	 * 硬截断已移除。
	 */
	public void recordMessage(UUID speakerId, String text, long currentTick) {
		recentMessages.addLast(new ContextMessage(speakerId, text, currentTick));
		this.lastActivityTick = currentTick;
	}

	public Deque<ContextMessage> getRecentMessages() {
		return recentMessages;
	}

	public record ContextMessage(UUID speakerId, String text, long tick) {}
}
