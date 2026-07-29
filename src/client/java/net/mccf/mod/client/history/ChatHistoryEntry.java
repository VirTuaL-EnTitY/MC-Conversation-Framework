package net.mccf.mod.client.history;

import java.util.UUID;

/**
 * 聊天历史记录里的一条消息。
 *
 * 涵盖三类来源，用 {@link Source} 区分（历史界面按来源决定展示样式，
 * 比如"自己发的"不需要显示"⇄"翻译标记）：
 * - SELF：自己发的消息（服务端 dispatchSelfEcho 回显 / 或纯客户端模式下自己发的消息不翻译）。
 * - VISIBLE：别人发的、能看到说话者时收到的消息（服务端空间化分发，VISIBLE displayMode）。
 * - AUDIBLE：别人发的、听得到但看不到时收到的消息（AUDIBLE displayMode，物品栏字幕）。
 * - CLIENT_ONLY：纯客户端模式下本地翻译追加的消息（无法区分 VISIBLE/AUDIBLE，因为
 *   没有服务端空间化参与，统一归为一类）。
 *
 * @param speakerId      说话者 UUID，SELF 类型即玩家自己的 UUID
 * @param speakerName    说话者显示名
 * @param originalText   原文
 * @param translatedText 译文（与原文相同语言时可能等于原文）
 * @param source         消息来源分类
 * @param timestampMillis 接收时刻（System.currentTimeMillis()），历史界面按时间倒序展示
 */
public record ChatHistoryEntry(
		UUID speakerId,
		String speakerName,
		String originalText,
		String translatedText,
		Source source,
		long timestampMillis
) {
	public enum Source {
		SELF, VISIBLE, AUDIBLE, CLIENT_ONLY
	}
}
