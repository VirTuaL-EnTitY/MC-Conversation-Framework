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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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
 *
 * 对话分组：消息按时间间隔聚类成"对话组"（相邻两条间隔超过 {@link #GROUP_GAP_MS}
 * 无新消息则视为新对话开始），每组顶部显示一行参与者标题——多人参与显示
 * "A、B、C 的对话"，单人连续发言显示"X 的自言自语"。这样玩家一眼能看出每段
 * 聊天是谁和谁之间的，而不是一堆孤立的"某人说了什么"。
 *
 * 为什么用客户端时间聚类而不是服务端 Conversation 分组：纯客户端模式下根本没有
 * 服务端 Conversation 信息可用（消息来自 ClientOnlyChatTranslator 的本地翻译），
 * 若按服务端分组会导致两套数据源、两套渲染逻辑；而历史回看对"对话边界精度"
 * 要求不高（不需要精确到 ConversationManager 的空间合并语义），统一用时间聚类
 * 既覆盖两种模式又只用一套逻辑。代价是：两个独立对话若间隔不足 30 秒会被合并成
 * 一组、一个长对话若中间沉默超过 30 秒会被拆成两组——对"回看个大概"可接受，
 * 不满意再调 {@link #GROUP_GAP_MS}。
 */
public class ChatHistoryScreen extends Screen {

	private final Screen parent;
	private HistoryListWidget listWidget;
	private boolean isEmpty;

	/** 展示用的时间格式：只显示时:分:秒，历史记录本身就限定在"这次连接期间"，日期没有意义。 */
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	/**
	 * 对话组分界阈值（毫秒）：相邻两条消息间隔超过此值则视为新对话开始。
	 * 取 30 秒——AUDIBLE 字幕最长相干时间约 8 秒（淡出），玩家正常对话节奏
	 * 远小于 30 秒；超过 30 秒没人说话基本意味着话题中断或人散了。这是经验取值，
	 * 没做严格统计，后续若发现分组过粗/过细可调整。
	 */
	private static final long GROUP_GAP_MS = 30_000L;

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

		List<ChatHistoryEntry> entries = ChatHistoryManager.snapshot();

		// 按时间正序聚类成对话组：相邻消息间隔超过 GROUP_GAP_MS 则断开。
		// 先正序聚类、再整体倒序加入列表，保证"最新组在最上、组内最新消息在最上"，
		// 与"玩家打开历史最想看刚才发生了什么"的诉求一致。
		List<List<ChatHistoryEntry>> groups = new ArrayList<>();
		List<ChatHistoryEntry> currentGroup = null;
		long prevTime = Long.MIN_VALUE;
		for (ChatHistoryEntry e : entries) {
			if (currentGroup == null || (e.timestampMillis() - prevTime) > GROUP_GAP_MS) {
				currentGroup = new ArrayList<>();
				groups.add(currentGroup);
			}
			currentGroup.add(e);
			prevTime = e.timestampMillis();
		}

		isEmpty = entries.isEmpty();

		// 倒序展示：最新对话组在最上面。
		Collections.reverse(groups);
		for (List<ChatHistoryEntry> group : groups) {
			// 组内也倒序：组内最新消息在最上面。
			Collections.reverse(group);

			// 收集参与者（保持发言先后顺序去重），并取组内最新时间用于组标题右侧。
			LinkedHashSet<String> participants = new LinkedHashSet<>();
			long latestInGroup = Long.MIN_VALUE;
			for (ChatHistoryEntry e : group) {
				String name = e.speakerName();
				if (name != null && !name.isBlank()) {
					participants.add(name);
				}
				if (e.timestampMillis() > latestInGroup) {
					latestInGroup = e.timestampMillis();
				}
			}

			listWidget.addEntry(new GroupHeaderWidget(participants, latestInGroup));
			for (ChatHistoryEntry e : group) {
				listWidget.addEntry(new HistoryEntryWidget(e));
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
	 * <b>历史踩坑</b>：本项目曾误判这个签名。反编译 jar 只能看到参数类型
	 * {@code (client, int, int, int, int)} 看不到参数名，叠加"老版本 1.20.x 是 6 参数
	 * (client,w,h,top,bottom,itemHeight)"的先入为主，把 1.21.1 的 5 参数版本想成了
	 * {@code (client, w, h, top, bottom)}，第 5 参数 bottom 被当成了底部坐标。实际第 5
	 * 参数是 itemHeight，结果 {@code this.height - 40}（本应是 bottom）被传成 itemHeight，
	 * 每条记录行高 = 整个列表区域高度，一屏只能看到一条消息——这正是用户反馈的
	 * "一句话占用整个界面"的根因。本次修正：保留 top/bottom 语义方便 Screen 传入，
	 * 内部换算成 height（= bottom - top）和 y（= top）传给父类。
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
	 * 列表条目基类：组标题行（{@link GroupHeaderWidget}）与消息行（{@link HistoryEntryWidget}）
	 * 共同实现，以便统一加入同一个 {@link HistoryListWidget}。1.21.1 的
	 * {@code EntryListWidget} 的 itemHeight 是列表级统一的（不支持每条目自定义高度，
	 * 那是 1.21.8 才引入的能力），所以组标题行与消息行共用 {@link #ITEM_HEIGHT}，
	 * 组标题靠背景条 + 颜色区分而非高度。
	 */
	private abstract static class HistoryListEntry extends AlwaysSelectedEntryListWidget.Entry<HistoryListEntry> {
	}

	/**
	 * 对话组标题行：显示该组参与者。多人参与显示"A、B、C 的对话"（绿色 + 浅绿背景），
	 * 单人连续发言显示"X 的自言自语"（灰色 + 浅灰背景）；右侧对齐组内最新消息时间。
	 *
	 * "自言自语"的判定依据是组内不同说话者数量 == 1——不区分这人是不是玩家自己，
	 * 因为无论是别人独自刷屏还是玩家自己连续发言，对"回看"而言都是"没有交流对象
	 * 的独白"，归为一类更直观。
	 */
	private static class GroupHeaderWidget extends HistoryListEntry {
		private final LinkedHashSet<String> participants;
		private final long latestTimestampMillis;

		GroupHeaderWidget(LinkedHashSet<String> participants, long latestTimestampMillis) {
			this.participants = participants;
			this.latestTimestampMillis = latestTimestampMillis;
		}

		@Override
		public Text getNarration() {
			return Text.literal(buildTitle());
		}

		private String buildTitle() {
			if (participants.size() <= 1) {
				String name = participants.isEmpty() ? "?" : participants.iterator().next();
				return name + " 的自言自语";
			}
			return String.join("、", participants) + " 的对话";
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			String title = buildTitle();
			// 单人独白=灰色，多人对话=绿色——与消息行 SELF 绿色呼应，让"有交流"更显眼。
			boolean isMonologue = participants.size() <= 1;
			int textColor = isMonologue ? 0xAAAAAA : 0x55FF55;
			// 浅色背景条把组标题与消息行视觉分开；独白用更暗的背景进一步弱化。
			int bg = isMonologue ? 0x22AAAAAA : 0x2255AA55;
			context.fill(x, y, x + entryWidth, y + entryHeight, bg);

			int textY = y + Math.max(0, (entryHeight - textRenderer.fontHeight) / 2);
			context.drawTextWithShadow(textRenderer, title, x + 4, textY, textColor);

			// 右侧组内最新消息时间，让玩家不用展开组就知道这段对话发生在大约何时。
			String timeStr = TIME_FORMAT.format(Instant.ofEpochMilli(latestTimestampMillis));
			int timeWidth = textRenderer.getWidth(timeStr);
			context.drawTextWithShadow(textRenderer, timeStr, x + entryWidth - timeWidth - 4, textY, Colors.LIGHT_GRAY);
		}
	}

	/**
	 * 单条历史消息条目。单行渲染："[来源] 说话者: 原文（⇄ 译文）"，右侧对齐时间戳。
	 *
	 * 组标题已经显示了"这段对话是谁和谁的"，但每条消息仍保留说话者名——因为同一组
	 * 内多人交替发言时，需要知道每条具体是谁说的；来源标签（SELF/VISIBLE/AUDIBLE/
	 * CLIENT_ONLY）也保留，它区分的是"我怎么收到这条消息的"（自己发的/近处看到/
	 * 只听到/纯客户端翻译），与"谁和谁在聊"是正交的两个维度。
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
			// 所以每条记录只能画一行——把"[来源] 说话者：原文（⇄ 译文）"拼成一行，右侧留
			// 时间戳，超宽时裁剪加省略号。为兼顾信息密度，优先保证时间戳和说话者可见，
			// 正文内容允许被截断（历史记录本来就是"回溯个大概"，不是完整对照阅读工具）。
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
