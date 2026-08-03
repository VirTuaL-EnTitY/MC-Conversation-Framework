package net.mccf.mod.client.subtitle;

import net.mccf.mod.network.SubtitlePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧字幕状态管理（仅 AUDIBLE 模式）。
 *
 * 设计要点（对应"多人字幕"需求）：
 * - AUDIBLE（屏幕下方物品栏上方）模式的字幕按"说话者"去重、排队显示，
 *   每人同一时刻只保留最新一条，并按说话者名字排序，保证多人对话时字幕位置稳定
 *   不跳（如果按接收顺序堆叠，同一说话者的字幕位置会因新消息插入而跳动，影响阅读）。
 * - 每条字幕存活时间基于文本长度动态计算（阅读时间），避免长句子一闪而过。
 *
 * 0.16.0 起本类只管理 AUDIBLE 模式字幕：VISIBLE 模式的 payload 在 MCCFClient
 * 接收时就被分流到原版聊天栏（addVisibleToChatHud），不会进入 SubtitleManager。
 * 早期版本的 Mode 枚举（区分 VISIBLE/AUDIBLE）已随 WorldSubtitleRenderer 一并移除。
 */
public class SubtitleManager {

	private static final long MIN_DISPLAY_MILLIS = 2500;
	private static final long PER_CHAR_MILLIS = 60;
	private static final long MAX_DISPLAY_MILLIS = 8000;

	/** speakerId -> 当前该说话者最新的一条 AUDIBLE 字幕。 */
	private static final Map<UUID, ActiveSubtitle> ACTIVE = new ConcurrentHashMap<>();

	private SubtitleManager() {}

	public static void onReceive(SubtitlePayload payload) {
		long displayDuration = computeDisplayDuration(payload.translatedText());
		long expiresAt = System.currentTimeMillis() + displayDuration;

		ActiveSubtitle subtitle = new ActiveSubtitle(
				payload.speakerId(), payload.speakerName(),
				payload.originalText(), payload.translatedText(),
				expiresAt);

		ACTIVE.put(payload.speakerId(), subtitle);
	}

	private static long computeDisplayDuration(String text) {
		long duration = MIN_DISPLAY_MILLIS + (long) text.length() * PER_CHAR_MILLIS;
		return Math.min(duration, MAX_DISPLAY_MILLIS);
	}

	/** 供渲染器调用：清理过期字幕，返回当前仍有效的字幕列表（按说话者名排序，保证多人布局稳定）。 */
	public static List<ActiveSubtitle> getActiveAndPrune() {
		long now = System.currentTimeMillis();
		ACTIVE.entrySet().removeIf(e -> e.getValue().isExpired(now));

		List<ActiveSubtitle> list = new ArrayList<>(ACTIVE.values());
		list.sort((a, b) -> a.speakerName().compareToIgnoreCase(b.speakerName()));
		return list;
	}

	public static void clear() {
		ACTIVE.clear();
	}
}
