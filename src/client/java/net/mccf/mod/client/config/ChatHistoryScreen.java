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
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
 * 分组方式（{@link ChatHistoryManager#groupedSnapshot(ChatHistoryManager.FilterOptions,
 * ChatHistoryManager.SortMode)} 已经整理好）：
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
 *
 * 筛选 + 排序（应用户要求新增）：标题栏最右边有一个小按钮，点击展开/收起一个
 * 筛选/排序面板——平时收起不占用列表可用高度，展开时才显示完整控件。三个筛选
 * 维度（来源、参与者、关键词）可以同时组合使用（AND 关系），按对话分组整体
 * 过滤（不是单条消息级别，见 {@link ChatHistoryManager#matchesFilter} 的说明）。
 * 排序方式四选一。筛选/排序状态只存在于本次打开界面期间，不持久化（关闭界面
 * 后下次重新打开会回到默认的"不筛选 + 时间倒序"）。
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

	/** 筛选/排序面板展开时的高度，列表顶部要相应下移让出这块空间。1.1.4 起从 84 改到 104（多一行"显示译文"开关）。 */
	private static final int FILTER_PANEL_HEIGHT = 104;

	/** 面板是否展开——默认收起，不影响原有"打开即看列表"的体验。 */
	private boolean filterPanelExpanded = false;

	/** 当前选中的来源筛选（空集合 = 不筛选/全选）。用 EnumSet 保证四个来源枚举值顺序稳定。 */
	private final Set<ChatHistoryEntry.Source> selectedSources = EnumSet.noneOf(ChatHistoryEntry.Source.class);
	/** 当前选中的参与者筛选；"" 表示"全部"（不筛选）。 */
	private String selectedParticipant = "";
	private String keywordInput = "";
	private ChatHistoryManager.SortMode sortMode = ChatHistoryManager.SortMode.TIME_DESC;
	/**
	 * 1.1.4 新增：是否显示译文。默认开启——历史记录的价值就是"回看翻译对照"。
	 * 玩家可以在筛选面板里关闭，关闭后只显示原文，方便专注阅读原文不被译文干扰。
	 */
	private boolean showTranslated = true;

	private ButtonWidget filterToggleButton;
	private ButtonWidget sourceSelfButton;
	private ButtonWidget sourceVisibleButton;
	private ButtonWidget sourceAudibleButton;
	private ButtonWidget sourceClientOnlyButton;
	private CyclingButtonWidget<String> participantButton;
	private TextFieldWidget keywordField;
	private CyclingButtonWidget<ChatHistoryManager.SortMode> sortButton;
	/** 1.1.4 新增："显示译文"开关，放在筛选面板第三行右侧。 */
	private CyclingButtonWidget<Boolean> showTranslatedButton;

	public ChatHistoryScreen(Screen parent) {
		super(Text.translatable("mccf.history.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// 标题栏最右边的"筛选"小按钮——应用户明确要求放在标题最右边，点开才有
		// 详细面板，平时不占用列表空间。宽度较窄（60px），只需要放得下"筛选"
		// 这类简短文字或一个漏斗图标+文字。
		int toggleWidth = 60;
		filterToggleButton = addDrawableChild(ButtonWidget.builder(
						Text.translatable("mccf.history.filter.button"), button -> toggleFilterPanel())
				.dimensions(this.width - toggleWidth - 8, 6, toggleWidth, 16)
				.build());

		buildFilterPanelWidgets();
		rebuildList();

		addDrawableChild(ButtonWidget.builder(Text.translatable("mccf.history.close"), button -> close())
				.dimensions(this.width / 2 - 60, this.height - 28, 120, 20)
				.build());
	}

	/**
	 * 创建筛选/排序面板里的所有控件（来源多选、参与者下拉、关键词输入框、排序
	 * 下拉）。控件本身始终存在（加入 {@code addDrawableChild}），可见性/可交互性
	 * 由 {@link #filterPanelExpanded} 控制——收起时 {@code visible = false}，
	 * Minecraft 的 ClickableWidget 在 visible=false 时既不渲染也不响应点击，
	 * 不需要额外处理输入拦截问题。
	 */
	private void buildFilterPanelWidgets() {
		int panelTop = 26;
		int rowHeight = 18;
		int col1 = 8;
		int fieldHeight = 16;

		// 来源多选：四个独立的开关按钮并排，每个代表一个 Source 枚举值。用普通
		// ButtonWidget 手动维护勾选态（点击切换 selectedSources 里是否包含该来源），
		// 而不是 CyclingButtonWidget<Boolean>——四个来源要并排挤在一行里，每个的
		// 宽度需要按文字自适应，独立 ButtonWidget 更方便逐个控制宽度和位置。
		int sourceY = panelTop;
		int sourceX = col1;
		sourceSelfButton = addDrawableChild(makeSourceToggle(ChatHistoryEntry.Source.SELF,
				"mccf.history.source.self", sourceX, sourceY));
		sourceX += sourceSelfButton.getWidth() + 4;
		sourceVisibleButton = addDrawableChild(makeSourceToggle(ChatHistoryEntry.Source.VISIBLE,
				"mccf.history.source.visible", sourceX, sourceY));
		sourceX += sourceVisibleButton.getWidth() + 4;
		sourceAudibleButton = addDrawableChild(makeSourceToggle(ChatHistoryEntry.Source.AUDIBLE,
				"mccf.history.source.audible", sourceX, sourceY));
		sourceX += sourceAudibleButton.getWidth() + 4;
		sourceClientOnlyButton = addDrawableChild(makeSourceToggle(ChatHistoryEntry.Source.CLIENT_ONLY,
				"mccf.history.source.client_only", sourceX, sourceY));

		int row2Y = panelTop + rowHeight;
		int halfWidth = (this.width - 16 - 8) / 2;

		// 参与者下拉：选项是"全部"（空字符串，代表不筛选）+ 当前历史记录里出现过
		// 的所有玩家显示名。每次面板重新构建时都重新收集一遍名单——玩家可能是
		// 打开界面之后才有新消息进来，用最新的 knownSpeakerNames() 更准确。
		List<String> participantOptions = new ArrayList<>();
		participantOptions.add(""); // "全部"选项，空字符串代表不筛选
		participantOptions.addAll(ChatHistoryManager.knownSpeakerNames());
		participantButton = addDrawableChild(CyclingButtonWidget.<String>builder(name ->
						name.isEmpty() ? Text.translatable("mccf.history.filter.participant_all") : Text.literal(name))
				.values(participantOptions)
				.initially(participantOptions.contains(selectedParticipant) ? selectedParticipant : "")
				.build(col1, row2Y, halfWidth, fieldHeight,
						Text.translatable("mccf.history.filter.participant"),
						(button, value) -> selectedParticipant = value));

		sortButton = addDrawableChild(CyclingButtonWidget.<ChatHistoryManager.SortMode>builder(mode ->
						Text.translatable(sortModeKey(mode)))
				.values(List.of(ChatHistoryManager.SortMode.values()))
				.initially(sortMode)
				.build(col1 + halfWidth + 8, row2Y, halfWidth, fieldHeight,
						Text.translatable("mccf.history.sort.label"),
						(button, value) -> { sortMode = value; rebuildList(); }));

		int row3Y = row2Y + rowHeight;
		keywordField = addDrawableChild(new TextFieldWidget(
				MinecraftClient.getInstance().textRenderer, col1, row3Y, this.width - 16, fieldHeight,
				Text.translatable("mccf.history.filter.keyword")));
		keywordField.setMaxLength(64);
		keywordField.setPlaceholder(Text.translatable("mccf.history.filter.keyword"));
		keywordField.setText(keywordInput);

		// 1.1.4 新增："显示译文"开关，放在关键词输入框右侧——关键词是全宽输入框，
		// 这里改成关键词占左半、显示译文开关占右半，省得再增加一行高度。
		// 重新调整关键词输入框宽度为左半。
		int keywordWidth = (this.width - 16 - 8) / 2;
		keywordField.setWidth(keywordWidth);
		showTranslatedButton = addDrawableChild(CyclingButtonWidget.onOffBuilder(showTranslated)
				.build(col1 + keywordWidth + 8, row3Y, keywordWidth, fieldHeight,
						Text.translatable("mccf.history.filter.show_translated"),
						(button, value) -> { showTranslated = value; rebuildList(); }));
		// 关键词是文本输入，不适合像其他筛选项那样"改了就立刻生效"——打字过程中
		// 每敲一个字符都重建列表会很卡，也容易在打到一半时列表就跳来跳去。改为
		// 失去焦点（比如点击别处、按 Tab）时才应用，配合下面的 setChangedListener
		// 仅同步 keywordInput 字段，实际触发 rebuildList() 放在 onKeywordChanged。
		keywordField.setChangedListener(text -> keywordInput = text);

		syncFilterPanelVisibility();
	}

	/** 创建一个来源筛选的开关按钮：点击切换该来源是否在 selectedSources 里，文字前缀用 ✓/✗ 提示当前状态。 */
	private ButtonWidget makeSourceToggle(ChatHistoryEntry.Source source, String labelKey, int x, int y) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		String label = Text.translatable(labelKey).getString();
		int width = textRenderer.getWidth(label) + 20;
		return ButtonWidget.builder(sourceToggleText(source, labelKey), button -> {
					if (selectedSources.contains(source)) {
						selectedSources.remove(source);
					} else {
						selectedSources.add(source);
					}
					button.setMessage(sourceToggleText(source, labelKey));
					rebuildList();
				})
				.dimensions(x, y, width, 16)
				.build();
	}

	private Text sourceToggleText(ChatHistoryEntry.Source source, String labelKey) {
		String prefix = selectedSources.contains(source) ? "✓ " : "";
		return Text.literal(prefix).append(Text.translatable(labelKey));
	}

	private String sortModeKey(ChatHistoryManager.SortMode mode) {
		return switch (mode) {
			case TIME_DESC -> "mccf.history.sort.time_desc";
			case TIME_ASC -> "mccf.history.sort.time_asc";
			case PARTICIPANT_COUNT_DESC -> "mccf.history.sort.participant_count";
			case MESSAGE_COUNT_DESC -> "mccf.history.sort.message_count";
		};
	}

	private void toggleFilterPanel() {
		filterPanelExpanded = !filterPanelExpanded;
		syncFilterPanelVisibility();
		// 面板收起时，把还没应用的关键词输入也一并应用一次——避免玩家打完关键词
		// 直接收起面板、觉得筛选没生效的困惑（关键词是"失焦/收起时应用"的策略，
		// 收起面板本身也应该算一次"离开输入框"）。
		if (!filterPanelExpanded) {
			applyKeywordAndRebuild();
		}
		rebuildList(); // 面板高度变化导致列表可用区域变化，需要重新构建
	}

	private void applyKeywordAndRebuild() {
		if (keywordField != null) {
			keywordInput = keywordField.getText();
		}
	}

	private void syncFilterPanelVisibility() {
		boolean v = filterPanelExpanded;
		if (sourceSelfButton != null) sourceSelfButton.visible = v;
		if (sourceVisibleButton != null) sourceVisibleButton.visible = v;
		if (sourceAudibleButton != null) sourceAudibleButton.visible = v;
		if (sourceClientOnlyButton != null) sourceClientOnlyButton.visible = v;
		if (participantButton != null) participantButton.visible = v;
		if (keywordField != null) keywordField.visible = v;
		if (sortButton != null) sortButton.visible = v;
		if (showTranslatedButton != null) showTranslatedButton.visible = v;
	}

	/**
	 * 重新根据当前筛选/排序状态构建列表内容。任何筛选条件或排序方式变化、以及
	 * 面板展开/收起导致列表可用高度变化时都要调用这个方法——不是只在 init()
	 * 里调用一次，因为筛选/排序是交互式的，玩家改了条件要能立刻看到结果。
	 */
	private void rebuildList() {
		// 关键词筛选在玩家还在打字、尚未失焦/收起面板前不生效（见 keywordField
		// 的 setChangedListener 说明）；这里读取的是"已经应用"的 keywordInput，
		// 不是 keywordField 当前的实时文本——除非本次调用就是由
		// applyKeywordAndRebuild 触发的（那种情况 keywordInput 已经是最新值）。
		ChatHistoryManager.FilterOptions filter = new ChatHistoryManager.FilterOptions(
				selectedSources.isEmpty() ? null : EnumSet.copyOf(selectedSources),
				selectedParticipant.isEmpty() ? null : selectedParticipant,
				keywordInput.isEmpty() ? null : keywordInput);

		// 每次重建都会创建一个全新的 HistoryListWidget 实例（因为 listTop 可能
		// 因面板展开/收起而变化，AlwaysSelectedEntryListWidget 的位置/尺寸在
		// 1.21.1 构造完成后不方便动态修改，重新构造比找 API 调整现有实例更简单
		// 可靠）。旧实例必须先从 Screen 的子控件集合里移除，否则每次筛选/排序
		// 变化都会残留一个失效的列表在原地——不仅浪费内存，旧列表的裁剪区域/
		// 输入响应还会跟新列表叠加，导致点击、滚动等交互错乱。
		if (listWidget != null) {
			remove(listWidget);
		}

		int listTop = filterPanelExpanded ? 26 + FILTER_PANEL_HEIGHT : 32;
		listWidget = new HistoryListWidget(MinecraftClient.getInstance(),
				this.width, listTop, this.height - 40, ITEM_HEIGHT);

		List<ChatHistoryManager.ConversationGroup> groups =
				ChatHistoryManager.groupedSnapshot(filter, sortMode);
		isEmpty = groups.isEmpty();

		// groupedSnapshot() 已经按选定的排序方式排好，这里直接按返回顺序渲染，
		// 组内 items 是时间正序——为了让"最新的在最上面"这个直觉在组内也成立，
		// 实际渲染时组内也倒序展示（与旧版本"最新消息在最上面"的展示习惯保持
		// 一致，不随整体排序方向联动，维持组内展示顺序的稳定性）。
		for (ChatHistoryManager.ConversationGroup group : groups) {
			listWidget.addEntry(new GroupTitleWidget(group));

			List<ChatTimelineItem> items = new ArrayList<>(group.items());
			java.util.Collections.reverse(items);
			for (ChatTimelineItem item : items) {
				if (item instanceof ChatTimelineItem.Message m) {
					listWidget.addEntry(new HistoryEntryWidget(m.entry(), showTranslated));
				} else if (item instanceof ChatTimelineItem.SystemEvent s) {
					listWidget.addEntry(new SystemEventWidget(s.event()));
				}
			}
		}
		addSelectableChild(listWidget);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		// 关键词输入框失焦时应用筛选——每帧检查一次"当前是否仍聚焦在输入框上"，
		// 不是最优雅的做法，但比给 TextFieldWidget 包一层监听失焦事件的封装
		// 简单可靠得多，对渲染性能的影响可忽略（一次布尔比较 + 偶尔的字符串
		// 比较）。只在面板展开时检查，收起时 toggleFilterPanel 已经应用过一次。
		if (filterPanelExpanded && keywordField != null && !keywordField.isFocused()
				&& !keywordInput.equals(keywordField.getText())) {
			applyKeywordAndRebuild();
			rebuildList();
		}

		// 列表 widget 不在 addDrawableChild 里（只 addSelectableChild），与
		// ModelSelectionScreen 同理，这里手动渲染以控制绘制顺序。
		listWidget.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 12, Colors.WHITE);

		if (isEmpty) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mccf.history.empty"),
					this.width / 2, this.height / 2, Colors.LIGHT_GRAY);
		}
	}

	/**
	 * 关键词回车提交：玩家在输入框里按回车，应该立即应用筛选，不用等到失焦或
	 * 收起面板——这是比"每帧轮询失焦状态"更即时的补充路径，两者不冲突
	 * （keyPressed 优先触发，render 里的轮询作为兜底，防止玩家用鼠标点击别处
	 * 导致的失焦没有触发 keyPressed）。
	 */
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (filterPanelExpanded && keywordField != null && keywordField.isFocused() && keyCode == 257 /* GLFW_KEY_ENTER */) {
			applyKeywordAndRebuild();
			rebuildList();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
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
			// 1.1.4 改造：大标题用 1.3 倍放大渲染 + 更深的背景色，与普通消息行视觉分开。
			// 用 context.getMatrices().scale 实现——Minecraft 的 DrawContext 没有直接
			// "画大号文字"的 API，scale 是最可靠的方式。scale 后文字坐标会按比例放大，
			// 所以 x/y 坐标要除以 scale 因子。
			// 背景色加深（从 0x3355AA55 改到 0x5555AA55），让大标题行更醒目。
			context.fill(x, y, x + entryWidth, y + entryHeight, 0x5555AA55);

			float scale = 1.3f;
			context.getMatrices().push();
			context.getMatrices().scale(scale, scale, 1.0f);
			// scale 后坐标系放大，文字坐标要除以 scale 才能落在正确位置
			int scaledX = (int) ((x + 4) / scale);
			int scaledY = (int) ((y + Math.max(0, (entryHeight - textRenderer.fontHeight * scale) / 2)) / scale);
			String trimmed = textRenderer.trimToWidth(title, (int) ((entryWidth - 8) / scale));
			context.drawTextWithShadow(textRenderer, trimmed, scaledX, scaledY, 0x55FF55);
			context.getMatrices().pop();
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			// 1.1.4：大标题不可被点击选中——它是纯展示元素，选中它没有意义。
			return false;
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

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			// 1.1.4：系统提示不可被点击选中——纯展示元素。
			return false;
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
		/** 1.1.4 新增：是否显示译文，由 ChatHistoryScreen 在创建 entry 时传入。 */
		private final boolean showTranslated;

		HistoryEntryWidget(ChatHistoryEntry entry, boolean showTranslated) {
			this.entry = entry;
			this.showTranslated = showTranslated;
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

			// 1.1.4 修复：根据 showTranslated 决定是否显示译文。关闭时只显示原文，
			// 方便专注阅读原文不被译文干扰；开启时显示 "原文 ⇄ 译文 [语言标签]"。
			String message = entry.originalText();
			if (showTranslated) {
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
