package net.mccf.mod.client.history;

/**
 * 聊天历史时间线上的一项——要么是一条真实的聊天消息（{@link ChatHistoryEntry}），
 * 要么是一条系统提示（{@link ChatHistorySystemEvent}，比如"开始了一段新对话"）。
 *
 * 用 sealed interface 而不是公共基类：两种类型的字段完全不同（一个有说话者
 * /原文/译文，一个只有提示类型/涉及的人名），没有值得抽取的公共字段，
 * sealed interface 只是给"这是时间线上的一项，可能是这两种之一"这个概念
 * 一个名字，方便 {@link ChatHistoryManager} 按时间统一排序，同时保留
 * switch 表达式在两个具体类型上的穷尽性检查（忘记处理某个分支时编译器
 * 会报错，而不是运行时才发现漏了一种情况）。
 */
public sealed interface ChatTimelineItem permits ChatTimelineItem.Message, ChatTimelineItem.SystemEvent {

	long timestampMillis();

	record Message(ChatHistoryEntry entry) implements ChatTimelineItem {
		@Override
		public long timestampMillis() {
			return entry.timestampMillis();
		}
	}

	record SystemEvent(ChatHistorySystemEvent event) implements ChatTimelineItem {
		@Override
		public long timestampMillis() {
			return event.timestampMillis();
		}
	}
}
