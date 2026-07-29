package net.mccf.mod.client.subtitle;

import java.util.UUID;

/**
 * 客户端内存中的一条"正在显示"的字幕。
 *
 * @param speakerId      说话者 UUID（VISIBLE 模式下用于在世界中定位其模型旁边的字幕位置）
 * @param speakerName    说话者名称
 * @param originalText   原文
 * @param translatedText 译文
 * @param mode           VISIBLE（说话者模型旁边）或 AUDIBLE（屏幕下方，物品栏上方）
 * @param expiresAtMillis 该字幕应消失的系统时间戳（毫秒）
 */
public record ActiveSubtitle(
		UUID speakerId,
		String speakerName,
		String originalText,
		String translatedText,
		Mode mode,
		long expiresAtMillis
) {
	public enum Mode { VISIBLE, AUDIBLE }

	public boolean isExpired(long nowMillis) {
		return nowMillis >= expiresAtMillis;
	}
}
