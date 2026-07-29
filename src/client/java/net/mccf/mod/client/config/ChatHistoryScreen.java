package net.mccf.mod.client.config;

import net.mccf.mod.client.history.ChatHistoryEntry;
import net.mccf.mod.client.history.ChatHistoryManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * 聊天历史记录界面：展示本次连接期间 {@link ChatHistoryManager} 记录下的所有消息
 * （自己发的 + 收到的，无论是 VISIBLE 聊天框、AUDIBLE 物品栏字幕还是纯客户端模式
 * 本地翻译追加），按时间倒序（最新的在最上面）排列。
 *
 * 存在的意义：字幕会自动淡出（AUDIBLE 2.5~8 秒、VISIBLE 也是临时消息），玩家
 * 稍微一走神就会错过内容；这个界面让玩家能随时回溯"刚才这段时间发生了什么对话"，
 * 不需要争分夺秒地在字幕消失前读完。
 *
 * 入口：主配置界面（{@link MCCFConfigScreen}）的"聊天历史记录"按钮，或独立按键
 * 绑定（见 {@code MCCFClient} 的 openHistoryKey）——两者都不受 op 权限限制，
 * 因为历史记录是纯本地展示数据，不涉及任何服务端配置。
 *
 * 只读界面：没有编辑/删除单条记录的功能（不是聊天工具，是"回看"工具），
 * 只提供"关闭"按钮返回。
 */
public class ChatHistoryScreen extends Screen {

	private final Screen parent;
	private HistoryListWidget listWidget;
	private boolean isEmpty;

	/** 展示用的时间格式：只显示时:分:秒，历史记录本身就限定在"这次连接期间"，日期没有意义。 */
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	public ChatHistoryScreen(Screen parent) {
		super(Text.translatable("mccf.history.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		listWidget = new HistoryListWidget(MinecraftClient.getInstance(), this.width, this.height, 32, this.height - 40);

		// 按时间倒序（最新的在最上面）——玩家打开历史记录最想看的通常是"刚才发生了什么"，
		// 不需要从最早的一条开始往下翻。
		List<ChatHistoryEntry> entries = ChatHistoryManager.snapshot();
		Collections.reverse(entries);
		isEmpty = entries.isEmpty();
		for (ChatHistoryEntry entry : entries) {
			listWidget.addEntry(new HistoryEntryWidget(entry));
		}
		addSelectableChild(listWidget);

		addDrawableChild(ButtonWidget.builder(Text.translatable("mccf.history.close"), button -> close())
				.dimensions(this.width / 2 - 60, this.height - 28, 120, 20)
				.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		// 列表 widget 不在 addDrawableChild 里（只 addSelectableChild），与
		// ModelSelectionScreen 同理，这里手动渲染以控制绘制顺序。
		listWidget.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 12, Colors.WHITE);

		if (isEmpty) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mccf.history.empty"),
					this.width / 2, this.height / 2, Colors.LIGHT_GRAY);
		}
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	private static class HistoryListWidget extends AlwaysSelectedEntryListWidget<HistoryEntryWidget> {
		HistoryListWidget(MinecraftClient client, int width, int height, int top, int bottom) {
			super(client, width, height, top, bottom);
		}

		@Override
		public int addEntry(HistoryEntryWidget entry) {
			return super.addEntry(entry);
		}
	}

	/**
	 * 单条历史记录条目。单行渲染："[来源] 说话者: 原文（⇄ 译文）"，右侧对齐时间戳
	 * （1.21.1 的 AlwaysSelectedEntryListWidget 行高固定、不支持每条目自定义高度，
	 * 见下方 render 方法注释，因此不能像 ModelSelectionScreen 之外的双行布局那样
	 * 分两行画）。
	 *
	 * 不可点击、不可选中——这只是一个只读的展示列表，AlwaysSelectedEntryListWidget
	 * 只是为了复用现成的滚动条实现，选中态在这里没有实际意义。
	 */
	private static class HistoryEntryWidget extends AlwaysSelectedEntryListWidget.Entry<HistoryEntryWidget> {
		private final ChatHistoryEntry entry;

		HistoryEntryWidget(ChatHistoryEntry entry) {
			this.entry = entry;
		}

		@Override
		public Text getNarration() {
			return Text.literal(entry.speakerName() + ": " + entry.originalText());
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;

			// 来源标签颜色：SELF 用绿色（这是我自己说的，一眼区分），VISIBLE 用白色（近处看到），
			// AUDIBLE 用浅灰（只听到），CLIENT_ONLY 用青色（纯客户端模式，与主配置界面里
			// client-only 相关提示的配色呼应）。
			String sourceKey = switch (entry.source()) {
				case SELF -> "mccf.history.source.self";
				case VISIBLE -> "mccf.history.source.visible";
				case AUDIBLE -> "mccf.history.source.audible";
				case CLIENT_ONLY -> "mccf.history.source.client_only";
			};
			int textColor = switch (entry.source()) {
				case SELF -> 0x55FF55;
				case VISIBLE -> Colors.WHITE;
				case AUDIBLE -> Colors.LIGHT_GRAY;
				case CLIENT_ONLY -> 0x55FFFF;
			};
			Text sourceLabel = Text.translatable(sourceKey);

			String speakerName = entry.speakerName() == null || entry.speakerName().isBlank()
					? "?" : entry.speakerName();

			// 1.21.1 的 AlwaysSelectedEntryListWidget 行高固定、不支持每条目自定义高度
			// （见 README 9.2.x 版本兼容性踩坑：itemHeight 参数在 1.21.8 才引入），所以每条
			// 记录只能画一行——把"[来源] 说话者：原文（⇄ 译文）"拼成一行，右侧留时间戳，
			// 超宽时裁剪加省略号。为兼顾信息密度，优先保证时间戳和说话者可见，正文内容
			// 允许被截断（历史记录本来就是"回溯个大概"，不是完整对照阅读工具）。
			String message = entry.originalText();
			boolean hasTranslation = entry.translatedText() != null
					&& !entry.translatedText().isBlank()
					&& !entry.translatedText().equals(entry.originalText());
			if (hasTranslation) {
				message = message + " ⇄ " + entry.translatedText();
			}

			String timeStr = TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestampMillis()));
			int timeWidth = textRenderer.getWidth(timeStr);

			String prefix = "[" + sourceLabel.getString() + "] " + speakerName + ": ";
			String line = prefix + message;

			int maxWidth = entryWidth - timeWidth - 12;
			String trimmed = textRenderer.trimToWidth(line, maxWidth);
			if (trimmed.length() < line.length()) {
				String ellipsis = "...";
				int avail = Math.max(1, maxWidth - textRenderer.getWidth(ellipsis));
				trimmed = textRenderer.trimToWidth(line, avail) + ellipsis;
			}

			int textY = y + Math.max(0, (entryHeight - textRenderer.fontHeight) / 2);
			context.drawTextWithShadow(textRenderer, trimmed, x + 4, textY, textColor);
			context.drawTextWithShadow(textRenderer, timeStr, x + entryWidth - timeWidth - 4, textY, Colors.LIGHT_GRAY);
		}
	}
}
