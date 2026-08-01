package net.mccf.mod.client.history;

import java.util.UUID;

/**
 * 聊天历史记录里的"系统提示"条目——不是某个人说的话，而是对话状态本身的
 * 变化提示，比如"开始了一段新对话"或"Alex 加入了对话"。
 *
 * 为什么不复用 {@link ChatHistoryEntry} 而是新建独立类型：ChatHistoryEntry
 * 的字段语义都是围绕"一条真实的聊天消息"设计的（说话者、原文、译文），
 * 如果塞进一个特殊类型让大部分字段变成 null，历史界面渲染代码就得到处
 * 判断"这条是不是系统消息、字段是不是 null"，容易写出脆弱易错的分支。
 * 独立类型让"系统事件"和"聊天消息"在编译期就是两种不同的东西，渲染时
 * 按类型分别处理，不存在"忘记判断 null"这类运行时才暴露的问题。
 *
 * @param conversationId   这个提示所属的 Conversation
 * @param type             提示类型，见 {@link Type}
 * @param involvedNames    涉及的玩家显示名——CONVERSATION_STARTED 时为空列表
 *                         （只是提示"开始了新对话"，不需要具体点名，参与者
 *                         名单本来就会在大标题里列出）；PARTICIPANT_JOINED
 *                         时是这次新加入的一个或多个人的名字，历史界面据此
 *                         渲染"XX 加入了对话"或"XX、YY 加入了对话"
 * @param timestampMillis  发生时刻，用于和 ChatHistoryEntry 混合按时间排序展示
 */
public record ChatHistorySystemEvent(
		UUID conversationId,
		Type type,
		java.util.List<String> involvedNames,
		long timestampMillis
) {
	public enum Type {
		/** 这是这个 Conversation 第一次被记录（历史记录里第一次看到这个 conversationId）。 */
		CONVERSATION_STARTED,
		/** 已有的 Conversation 里新增了参与者（不是第一次记录，见 ConversationRosterManager#isFirstRoster）。 */
		PARTICIPANT_JOINED
	}
}
