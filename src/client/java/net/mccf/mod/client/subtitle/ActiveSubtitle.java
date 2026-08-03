package net.mccf.mod.client.subtitle;

import java.util.UUID;

/**
 * 客户端内存中的一条"正在显示"的物品栏上方字幕（AUDIBLE 模式）。
 *
 * @param speakerId      说话者 UUID
 * @param speakerName    说话者名称
 * @param originalText   原文
 * @param translatedText 译文
 * @param expiresAtMillis 该字幕应消失的系统时间戳（毫秒）
 *
 * 0.16.0 起本类只承载 AUDIBLE 模式字幕。早期版本还有一个 Mode 枚举区分
 * VISIBLE/AUDIBLE，VISIBLE 模式由 WorldSubtitleRenderer 在世界空间渲染；
 * 0.16.0 删除 WorldSubtitleRenderer、VISIBLE 走聊天栏后，SubtitleManager
 * 只会接收 AUDIBLE 模式的 payload，Mode 枚举随之移除以避免死代码。
 */
public record ActiveSubtitle(
		UUID speakerId,
		String speakerName,
		String originalText,
		String translatedText,
		long expiresAtMillis
) {
	public boolean isExpired(long nowMillis) {
		return nowMillis >= expiresAtMillis;
	}
}
