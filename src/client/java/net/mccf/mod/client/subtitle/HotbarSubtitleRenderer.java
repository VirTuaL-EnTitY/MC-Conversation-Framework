package net.mccf.mod.client.subtitle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Colors;

import java.util.List;

/**
 * 渲染 AUDIBLE 模式字幕：显示在屏幕下方、物品栏上方，类似原版辅助字幕
 * （Options > Accessibility > Show Subtitles）的呈现方式。
 *
 * 对应"多人字幕"需求：多个说话者同时说话时，按名字排序纵向堆叠显示，
 * 自动避免重叠；每条字幕背景做半透明处理，不遮挡物品栏内容。
 *
 * 挂载点：由 MCCFClient 通过 HudElementRegistry.addLast 注册。
 */
public class HotbarSubtitleRenderer {

	private static final int LINE_HEIGHT = 12;
	private static final int BOTTOM_MARGIN_ABOVE_HOTBAR = 32; // 物品栏高度 + 间距
	private static final int MAX_LINES = 5; // 避免屏幕被字幕刷屏，超出的旧消息自然过期后腾位置
	private static final int BACKGROUND_COLOR = 0x90000000; // 半透明黑
	private static final int TEXT_COLOR = Colors.WHITE;

	public void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options.hudHidden) return;

		List<ActiveSubtitle> subtitles = SubtitleManager.getActiveAndPrune().stream()
				.filter(s -> s.mode() == ActiveSubtitle.Mode.AUDIBLE)
				.limit(MAX_LINES)
				.toList();
		if (subtitles.isEmpty()) return;

		TextRenderer textRenderer = client.textRenderer;
		int screenWidth = context.getScaledWindowWidth();
		int screenHeight = context.getScaledWindowHeight();

		int baseY = screenHeight - BOTTOM_MARGIN_ABOVE_HOTBAR - (subtitles.size() * LINE_HEIGHT);

		for (int i = 0; i < subtitles.size(); i++) {
			ActiveSubtitle subtitle = subtitles.get(i);
			String line = subtitle.speakerName() + ": " + subtitle.translatedText();
			int textWidth = textRenderer.getWidth(line);
			int x = (screenWidth - textWidth) / 2;
			int y = baseY + i * LINE_HEIGHT;

			context.fill(x - 4, y - 1, x + textWidth + 4, y + LINE_HEIGHT - 2, BACKGROUND_COLOR);
			context.drawTextWithShadow(textRenderer, line, x, y, TEXT_COLOR);
		}
	}
}
