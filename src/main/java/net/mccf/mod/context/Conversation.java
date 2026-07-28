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
 * - AI Provider 在翻译时只能看到某个 Conversation 内部的近期消息（见
 *   {@link #getRecentMessages()}），不会、也无法访问服务器上其他任何对话。
 * - 一个玩家离开对话组后，其历史发言不再参与该组后续的翻译上下文。
 * - Conversation 在无人发言一段时间后自动过期释放（见 ConversationManager#tick），
 *   防止 AI 引用陈旧内容，也避免长期上下文导致的性能问题。
 *
 * Conversation 本身不做任何空间判定，只是一个"当前参与者 + 近期消息"的容器；
 * 判定谁该加入/离开由 {@link ConversationManager} 结合 SpatialChatHandler 完成。
 */
public class Conversation {

	/** 保留在上下文窗口内的最大消息条数，避免无限增长。 */
	private static final int MAX_CONTEXT_MESSAGES = 20;

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

	public void addParticipant(UUID playerId) {
		participants.add(playerId);
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
	 * 记录一条新消息并刷新活跃时间。超出上下文窗口大小时丢弃最旧的消息——
	 * 这不是懒惰的截断，而是有意为之：上下文应该只反映"最近仍然相关"的对话，
	 * 而不是无限累积的历史记录。
	 */
	public void recordMessage(UUID speakerId, String text, long currentTick) {
		recentMessages.addLast(new ContextMessage(speakerId, text, currentTick));
		while (recentMessages.size() > MAX_CONTEXT_MESSAGES) {
			recentMessages.removeFirst();
		}
		this.lastActivityTick = currentTick;
	}

	public Deque<ContextMessage> getRecentMessages() {
		return recentMessages;
	}

	public record ContextMessage(UUID speakerId, String text, long tick) {}
}
