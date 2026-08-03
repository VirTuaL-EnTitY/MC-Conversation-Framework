package net.mccf.mod.client.subtitle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * 渲染 AUDIBLE 模式字幕：显示在屏幕下方、物品栏上方，类似原版辅助字幕
 * （Options > Accessibility > Show Subtitles）的呈现方式。
 *
 * 只管"把字幕画到 HUD 上物品栏上方"，不管字幕生命周期（由 SubtitleManager 负责）、
 * 不管 VISIBLE 模式（VISIBLE 走原版聊天栏，由 MCCFClient#addVisibleToChatHud 处理）。
 *
 * 对应"多人字幕"需求：多个说话者同时说话时，按名字排序纵向堆叠显示，
 * 自动避免重叠；每条字幕背景做半透明处理，不遮挡物品栏内容。
 *
 * 挂载点：由 MCCFClient 通过 HudRenderCallback.EVENT.register 注册
 * （1.21.1 上用旧 API；1.21.6+ 才有 HudElementRegistry.addLast）。
 */
public class HotbarSubtitleRenderer {

	private static final int LINE_HEIGHT = 12;
	private static final int BOTTOM_MARGIN_ABOVE_HOTBAR = 32; // 物品栏高度 + 间距
	// 同时显示的最大字幕条数。超过此数的旧消息不渲染（但不影响 SubtitleManager 的
	// 生命周期管理，过期后自然清理）。设为 5 是避免屏幕被字幕刷屏的保守上限。
	private static final int MAX_SUBTITLES = 5;
	// 每条字幕最多显示的行数。HUD 字幕过长会覆盖物品栏、占据过多屏幕空间，
	// 限制 2 行后既能容纳短句翻译又不至于遮挡游戏 UI。
	private static final int MAX_LINES_PER_SUBTITLE = 2;
	// 单行最大像素宽度。屏幕宽度的约一半，保证字幕不会横向铺满整个屏幕。
	private static final int MAX_TEXT_WIDTH = 200;
	// 背景色：约 69% 不透明度黑。与 WorldSubtitleRenderer 保持一致以保证两套
	// 渲染器视觉统一。参考原版辅助字幕风格——太低（<50%）看不清字，太高（>85%）
	// 遮挡视野，69% 是可读性和视觉侵入性之间的经验平衡值。
	private static final int BACKGROUND_COLOR = 0xB0000000;
	private static final int TEXT_COLOR = Colors.WHITE;

	public void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options.hudHidden) return;

		// 0.16.0 起 SubtitleManager 只承载 AUDIBLE 字幕，不再需要按 mode 过滤——
		// VISIBLE 模式的 payload 在 MCCFClient 接收时就被分流到聊天栏，不进入 SubtitleManager。
		List<ActiveSubtitle> subtitles = SubtitleManager.getActiveAndPrune().stream()
				.limit(MAX_SUBTITLES)
				.toList();
		if (subtitles.isEmpty()) return;

		TextRenderer textRenderer = client.textRenderer;
		int screenWidth = context.getScaledWindowWidth();
		int screenHeight = context.getScaledWindowHeight();

		// 预先对所有字幕做换行处理，以便根据总行数计算起始 Y 坐标，保证多行字幕
		// 整体仍然底对齐到物品栏上方
		List<List<String>> allLines = new ArrayList<>();
		for (ActiveSubtitle subtitle : subtitles) {
			String line = subtitle.speakerName() + ": " + subtitle.translatedText();
			allLines.add(wrapForHotbar(textRenderer, line));
		}

		int totalLines = 0;
		for (List<String> lines : allLines) totalLines += lines.size();
		int baseY = screenHeight - BOTTOM_MARGIN_ABOVE_HOTBAR - (totalLines * LINE_HEIGHT);

		int y = baseY;
		for (List<String> lines : allLines) {
			for (String line : lines) {
				int textWidth = textRenderer.getWidth(line);
				int x = (screenWidth - textWidth) / 2;
				context.fill(x - 4, y - 1, x + textWidth + 4, y + LINE_HEIGHT - 2, BACKGROUND_COLOR);
				context.drawTextWithShadow(textRenderer, line, x, y, TEXT_COLOR);
				y += LINE_HEIGHT;
			}
		}
	}

	/**
	 * 按像素宽度对 HUD 字幕做换行和截断。
	 *
	 * 选用 TextRenderer.trimToWidth 而非按字符数截断的原因：HUD 字幕在固定像素
	 * 宽度下渲染，trimToWidth 能精确按视觉宽度裁剪，避免 CJK 字符（双倍宽度）
	 * 和 ASCII 字符混排时按字符数截断导致行宽不一致的问题。代价是英文单词可能
	 * 被从中间截断，但 HUD 字幕场景下可接受。
	 */
	private static List<String> wrapForHotbar(TextRenderer renderer, String text) {
		List<String> lines = new ArrayList<>();
		String remaining = text;
		while (lines.size() < MAX_LINES_PER_SUBTITLE && !remaining.isEmpty()) {
			boolean isLast = lines.size() == MAX_LINES_PER_SUBTITLE - 1;
			if (isLast) {
				// 最后一行：先尝试全部放下，放不下则留出省略号空间截断
				String trimmed = renderer.trimToWidth(remaining, MAX_TEXT_WIDTH);
				if (trimmed.length() >= remaining.length()) {
					lines.add(trimmed);
				} else {
					int ellipsisWidth = renderer.getWidth("...");
					int availableWidth = Math.max(1, MAX_TEXT_WIDTH - ellipsisWidth);
					String head = renderer.trimToWidth(remaining, availableWidth);
					if (head.isEmpty()) head = remaining.substring(0, 1);
					lines.add(head + "...");
				}
				break;
			} else {
				String trimmed = renderer.trimToWidth(remaining, MAX_TEXT_WIDTH);
				if (trimmed.isEmpty() && !remaining.isEmpty()) {
					// 单个字符就超宽，强制取第一个字符避免死循环
					trimmed = remaining.substring(0, 1);
				}
				lines.add(trimmed);
				remaining = remaining.substring(trimmed.length());
			}
		}
		return lines;
	}
}
