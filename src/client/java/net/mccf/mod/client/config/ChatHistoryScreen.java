package net.mccf.mod.client.config;

import net.mccf.mod.client.history.ChatHistoryEntry;
import net.mccf.mod.client.history.ChatHistoryManager;
import net.mccf.mod.client.history.ChatHistorySystemEvent;
import net.mccf.mod.client.history.ChatTimelineItem;
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
import java.util.List;

/**
 * 聊天历史记录界面：展示本次连接期间 {@link ChatHistoryManager} 记录下的所有消息，
 * 按服务端 Conversation 分组展示（对应服务端 {@code net.mccf.mod.context.Conversation}
 * 的对话合并/拆分机制，应用户明确要求"搬一下多人上下文的那个管理机制"）。
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
 *
 * 分组方式（{@link ChatHistoryManager#groupedSnapshot()} 已经整理好）：
 * - 大标题：这个 Conversation 里出现过的所有人名（"LimAimo、test、Alex 的对话"）——
 *   用 {@code ConversationRosterManager} 记录的、服务端下发的权威参与者名单，
 *   而不是客户端自己猜。
 * - 组内混排：消息（原文+译文+语言标签）和系统提示（"开始了一段新对话"/
 *   "XX 加入了对话"）按时间正序穿插展示——系统提示告诉玩家"这个对话是什么时候
 *   开始的、中途谁加入了"，消息告诉玩家具体聊了什么。
 * - 第三者能否算"加入对话"完全由服务端下发的数据决定：服务端只会把
 *   ConversationRosterPayload 发给"当时确实能收到这条对话消息的人"（见服务端
 *   SpatialChatHandler#broadcastConversationRoster），所以 A 看不到 Alex、
 *   B 看得到 Alex 时，A 的历史记录里天然不会出现"Alex 加入了对话"——这是
 *   客户端完全被动接收数据的自然结果，不需要客户端自己做任何额外判断。
 * - 无归属消息（纯客户端模式的 CLIENT_ONLY，没有服务端 Conversation 概念）
 *   各自单独成组，不与其他消息混在一起。
 */
public class ChatHistoryScreen extends Screen {

	private final Screen parent;
	private HistoryListWidget listWidget;
	private boolean isEmpty;

	/** 展示用的时间格式：只显示时:分:秒，历史记录本身就限定在"这次连接期间"，日期没有意义。 */
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	/**
	 * 每条记录的行高（像素）。单行文字 9px + 上下各 1.5px padding ≈ 12，让一屏
	 * 能放下十几条而非一条。
	 *
	 * 历史踩坑（2026-07 修正）：本项目曾误以为 1.21.1 的
	 * {@link AlwaysSelectedEntryListWidget} 第 5 个构造参数是 bottom（底部坐标），
	 * 实际是 itemHeight（行高）；当时把 {@code this.height - 40}（本应是 bottom）
	 * 传成了 itemHeight，导致每条记录行高 = 整个列表区域高度，一屏只能看到一条。
	 * 详见 {@link HistoryListWidget} 注释与 README 0.7.0 更新日志。
	 */
	private static final int ITEM_HEIGHT = 12;

	public ChatHistoryScreen(Screen parent) {
		super(Text.translatable("mccf.history.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		listWidget = new HistoryListWidget(MinecraftClient.getInstance(),
				this.width, 32, this.height - 40, ITEM_HEIGHT);

		List<ChatHistoryManager.ConversationGroup> groups = ChatHistoryManager.groupedSnapshot();
		isEmpty = groups.isEmpty();

		// groupedSnapshot() 已经按"组内最新消息时间"倒序排好（最近活跃的对话
		// 排最前面），这里直接按返回顺序渲染，组内 items 是时间正序——为了
		// 让"最新的在最上面"这个直觉在组内也成立，实际渲染时组内也倒序展示
		// （与旧版本"最新消息在最上面"的展示习惯保持一致）。
		for (ChatHistoryManager.ConversationGroup group : groups) {
			listWidget.addEntry(new GroupTitleWidget(group));

			List<ChatTimelineItem> items = new java.util.ArrayList<>(group.items());
			java.util.Collections.reverse(items);
			for (ChatTimelineItem item : items) {
				if (item instanceof ChatTimelineItem.Message m) {
					listWidget.addEntry(new HistoryEntryWidget(m.entry()));
				} else if (item instanceof ChatTimelineItem.SystemEvent s) {
					listWidget.addEntry(new SystemEventWidget(s.event()));
				}
			}
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

	/**
	 * 列表 widget：复用 {@link AlwaysSelectedEntryListWidget} 的滚动条与裁剪实现。
	 *
	 * <b>1.21.1 构造函数签名（yarn 1.21.1+build.3 实测）</b>：
	 * {@code EntryListWidget(MinecraftClient, int width, int height, int y, int itemHeight)}
	 * ——第 4 参数是列表顶部 y 坐标，第 5 参数是 itemHeight（每行高度），<b>没有 bottom
	 * 参数</b>（列表底部由 y + height 决定）。{@code AlwaysSelectedEntryListWidget}
	 * 同样只有这一个构造函数。
	 *
	 * <b>历史踩坑</b>：本项目曾误判这个签名，导致每条记录行高 = 整个列表区域高度，
	 * 一屏只能看到一条消息——这正是用户反馈的"一句话占用整个界面"的根因。本次修正：
	 * 保留 top/bottom 语义方便 Screen 传入，内部换算成 height（= bottom - top）和
	 * y（= top）传给父类。
	 *
	 * 1.21.8 起构造函数扩为 6 参数（重新加回 bottom），届时本类的换算可简化，但当前
	 * 1.21.1 必须按 5 参数签名传。
	 */
	private static class HistoryListWidget extends AlwaysSelectedEntryListWidget<HistoryListEntry> {
		HistoryListWidget(MinecraftClient client, int screenWidth, int top, int bottom, int itemHeight) {
			// yarn 1.21.1: (client, width, height, y, itemHeight)
			super(client, screenWidth, bottom - top, top, itemHeight);
		}

		@Override
		public int addEntry(HistoryListEntry entry) {
			return super.addEntry(entry);
		}
	}

	/**
	 * 列表条目基类：分组大标题（{@link GroupTitleWidget}）、系统提示行
	 * （{@link SystemEventWidget}）、消息行（{@link HistoryEntryWidget}）
	 * 共同实现，以便统一加入同一个 {@link HistoryListWidget}。1.21.1 的
	 * {@code EntryListWidget} 的 itemHeight 是列表级统一的（不支持每条目自定义
	 * 高度，那是 1.21.8 才引入的能力），所以三种行都共用 {@link #ITEM_HEIGHT}，
	 * 靠背景色/文字颜色/字体样式区分而非高度。
	 */
	private abstract static class HistoryListEntry extends AlwaysSelectedEntryListWidget.Entry<HistoryListEntry> {
	}

	/**
	 * 对话分组大标题：列出这个 Conversation 里出现过的所有参与者显示名
	 * （"LimAimo、test、Alex 的对话"）。数据来自服务端下发的权威名单
	 * （{@code ConversationRosterManager}），不是客户端自己猜测——应用户
	 * 明确要求"复用服务端的对话分组逻辑"。
	 *
	 * 无归属消息（CLIENT_ONLY，conversationId 为 null）时，participantNames
	 * 是空列表，标题退化为显示该组唯一一条消息的说话者名（如果有）或者一个
	 * 通用占位符——这类消息本来就没有"对话"概念，标题只是给它一个视觉锚点，
	 * 不代表真的有服务端认可的对话分组。
	 */
	private static class GroupTitleWidget extends HistoryListEntry {
		private final ChatHistoryManager.ConversationGroup group;

		GroupTitleWidget(ChatHistoryManager.ConversationGroup group) {
			this.group = group;
		}

		@Override
		public Text getNarration() {
			return Text.literal(buildTitle());
		}

		private String buildTitle() {
			if (group.conversationId() == null) {
				// 无归属消息：退化显示这条消息本身的说话者名，没有"对话"的概念。
				if (!group.items().isEmpty() && group.items().get(0) instanceof ChatTimelineItem.Message m) {
					String name = m.entry().speakerName();
					return (name == null || name.isBlank()) ? "?" : name;
				}
				return "?";
			}
			if (group.participantNames().isEmpty()) {
				// 理论上不会发生（有 conversationId 就说明收到过至少一次
				// ConversationRosterPayload），防御性兜底。
				return Text.translatable("mccf.history.unknown_conversation").getString();
			}
			// 用带 %s 占位符的完整句子模板（而不是代码里拼接"名字+固定后缀"），
			// 让每种语言自己决定名字该放在句子的哪个位置、用什么措辞——比如
			// 英语是 "%s's conversation"（所有格后缀），中文是 "%s的对话"
			// （前缀），俄语则是完全不同的语序 "Разговор: %s"。如果代码里
			// 假设"名字永远在前、后面拼一个固定后缀"，会强迫所有语言迁就
			// 同一种语序，容易翻译出不自然的句子。
			String names = String.join(
					Text.translatable("mccf.history.name_separator").getString(), group.participantNames());
			return Text.translatable("mccf.history.conversation_title", names).getString();
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			String title = buildTitle();
			// 浅绿背景条把大标题与下面的内容行视觉分开，比系统提示/消息行都更醒目
			// （大标题是分组的视觉锚点，应该一眼能找到）。
			context.fill(x, y, x + entryWidth, y + entryHeight, 0x3355AA55);

			int textY = y + Math.max(0, (entryHeight - textRenderer.fontHeight) / 2);
			String trimmed = textRenderer.trimToWidth(title, entryWidth - 8);
			context.drawTextWithShadow(textRenderer, trimmed, x + 4, textY, 0x55FF55);
		}
	}

	/**
	 * 系统提示行："开始了一段新对话" / "XX 加入了对话"。灰色斜体风格，与真实
	 * 聊天消息（白色/绿色等）区分开，一眼看出这不是有人说的话，而是对话状态
	 * 本身的变化。
	 */
	private static class SystemEventWidget extends HistoryListEntry {
		private final ChatHistorySystemEvent event;

		SystemEventWidget(ChatHistorySystemEvent event) {
			this.event = event;
		}

		@Override
		public Text getNarration() {
			return buildText();
		}

		private Text buildText() {
			if (event.type() == ChatHistorySystemEvent.Type.CONVERSATION_STARTED) {
				return Text.translatable("mccf.history.conversation_started");
			}
			String names = String.join(
					Text.translatable("mccf.history.name_separator").getString(), event.involvedNames());
			return Text.translatable("mccf.history.participant_joined", names);
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			String text = buildText().getString();
			int textY = y + Math.max(0, (entryHeight - textRenderer.fontHeight) / 2);
			String trimmed = textRenderer.trimToWidth(text, entryWidth - 8);
			// 居中显示——系统提示不是"谁说的话"，不需要像消息行那样左对齐配合
			// "[来源] 说话者:" 前缀，居中更符合"这是一条旁白式提示"的视觉语言。
			int textX = x + Math.max(0, (entryWidth - textRenderer.getWidth(trimmed)) / 2);
			context.drawTextWithShadow(textRenderer, trimmed, textX, textY, Colors.GRAY);
		}
	}

	/**
	 * 单条历史消息条目。单行渲染："[来源] 说话者: 原文（⇄ 译文 [源语言→目标语言]）"，
	 * 右侧对齐时间戳。
	 *
	 * 语言标签（例如 "zh_cn→en_us"）只在 sourceLang/targetLang 都非空且两者不同
	 * 时显示——相同语言之间没有发生真正的翻译，画一个语言标签没有意义（比如
	 * SELF 来源的自己回显，sourceLang == targetLang，不显示标签）。语言代码直接
	 * 显示原始格式（如 "zh_cn"），不做本地化名称转换。
	 *
	 * 不可点击、不可选中——这只是一个只读的展示列表，AlwaysSelectedEntryListWidget
	 * 只是为了复用现成的滚动条实现，选中态在这里没有实际意义。
	 */
	private static class HistoryEntryWidget extends HistoryListEntry {
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

			// itemHeight 是列表级统一的（12px），不支持每条目自定义高度（1.21.8 才引入），
			// 所以每条记录只能画一行——把"[来源] 说话者：原文（⇄ 译文 [语言标签]）"拼成
			// 一行，右侧留时间戳，超宽时裁剪加省略号。为兼顾信息密度，优先保证时间戳和
			// 说话者可见，正文内容允许被截断（历史记录本来就是"回溯个大概"，不是完整
			// 对照阅读工具）。
			String message = entry.originalText();
			boolean hasTranslation = entry.translatedText() != null
					&& !entry.translatedText().isBlank()
					&& !entry.translatedText().equals(entry.originalText());
			if (hasTranslation) {
				message = message + " ⇄ " + entry.translatedText();

				boolean hasLangLabel = entry.sourceLang() != null && entry.targetLang() != null
						&& !entry.sourceLang().equals(entry.targetLang());
				if (hasLangLabel) {
					message = message + " [" + entry.sourceLang() + "→" + entry.targetLang() + "]";
				}
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
