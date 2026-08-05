package net.mccf.mod.client.config;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

/**
 * MCCF 的游戏内配置界面。两个入口都指向这个 Screen：
 * - ModMenu 集成（{@code MCCFModMenuIntegration}）
 * - 按键绑定直接呼出（见 {@code MCCFClient} 里的按键监听）
 *
 * 整体布局：顶部一排标签页按钮，切换"服务端配置"/"本地设置"两个面板；
 * 每个面板内部都是"左侧 Provider 列表 + 右侧该 Provider 的设置"的两栏布局
 * （具体实现见 {@link ProviderConfigPanel} 及其两个子类）。这个 Screen 本身
 * 只负责标签页切换和整体尺寸计算，不直接持有输入框——所有字段控件都在
 * 对应的 Panel 里，随标签切换整体添加/移除。
 *
 * 界面尺寸取"接近全屏"：不再是原来居中的一小块，而是四周留一点边距、
 * 铺满大部分窗口，容纳左右两栏布局。
 *
 * 服务端网络回调（{@link net.mccf.mod.client.MCCFClient} 里通过
 * {@code instanceof MCCFConfigScreen} 触发）统一转发给"服务端配置"面板，
 * 这个类名和这几个方法签名必须保持稳定。
 */
public class MCCFConfigScreen extends Screen {

	/** 界面四周留白，营造"接近全屏但不贴边"的效果。 */
	private static final int MARGIN = 20;
	/** 标签栏高度。 */
	private static final int TAB_BAR_HEIGHT = 24;
	/** 标签栏与下方面板之间的间隙——留出空间给面板内部"当前 Provider 名称"标题行。 */
	private static final int TAB_BAR_GAP = 20;
	/**
	 * 底部提示文字区域的预留高度。
	 *
	 * 历史变更：早期版本这里同时常驻画"Provider 说明 + 状态消息"多行文字，
	 * 需要预留 100px 才够用；但 Provider 说明这类不紧急的信息后来改成了
	 * 鼠标悬浮在 Provider 标题上才弹出的 tooltip（见
	 * ServerConfigPanel/LocalConfigPanel#renderExtra），不再常驻占用这块
	 * 区域——这是应用户反馈修复的：截图显示旧版本在这里空出了接近 1.8/4
	 * 屏幕高度的空白，因为预留空间是按"最坏情况全部常驻"估算的，但日常
	 * 使用中 Provider 说明常驻占用的那部分其实完全用不上。
	 *
	 * 现在这块区域只需要容纳：
	 * - LocalConfigPanel：最多 2 行常驻文字（服务器检测状态 + 操作状态消息），
	 *   每行 18px，合计 36px。
	 * - ServerConfigPanel：最多 1 行常驻文字（加载中/超时未安装/保存状态，
	 *   18px）+ 请求超时时额外出现的"重试"按钮（20px 高 + 4px 间距），
	 *   合计 42px。
	 * 取两者较大值 42px，预留 50px 留一点余量。两个面板统一用这个值，即使
	 * LocalConfigPanel 用不上按钮那部分空间也无妨——换来的是两个标签页
	 * "控件区下边界"位置一致，切换标签页时不会感觉界面在跳动。
	 *
	 * 这个值必须和 ServerConfigPanel/LocalConfigPanel 里 renderLeftBottomHints
	 * 用的行距（18px）、以及 ServerConfigPanel 的"重试"按钮尺寸保持同步——如果
	 * 以后改了那边的行距/行数/按钮高度，这里也要跟着调整，否则控件区和提示
	 * 文字/按钮区又会重新出现"共享同一条边界线导致视觉重叠"的问题（这正是
	 * 0.9.0 之前的实际状况，见更新日志里的踩坑记录）。
	 */
	private static final int BOTTOM_HINT_AREA_HEIGHT = 50;

	private final Screen parent;

	private ServerConfigPanel serverPanel;
	private LocalConfigPanel localPanel;

	/** 当前显示哪个面板。默认服务端配置——多数玩家打开设置是为了改服务器上生效的 Provider。 */
	private Tab activeTab = Tab.SERVER;

	private ButtonWidget serverTabButton;
	private ButtonWidget localTabButton;

	/**
	 * 跨 init() 重建保留的"上次选中 Provider"——分 server/local 两个标签页各自独立保存。
	 *
	 * 1.1.3 修复"切换 Provider 后获取模型列表回来 Provider 切回 activeProvider"和
	 * "强制关闭思考警告屏幕点'是'后仍显示关"两个 bug。根因是 init() 重建时 new 了一个
	 * 全新 Panel 实例，新 Panel 的 selectedProvider 通过 initialSelectedProvider() 读
	 * state.activeProvider，丢失了玩家在旧 Panel 里临时切换查看的 selectedProvider。
	 *
	 * 修复：init() 重建前先读出旧 Panel 的 selectedProvider 存到这两个字段，新 Panel
	 * 构造后通过 setPreservedSelectedProvider() 传回去。这样 ConfirmScreen / ModelSelectionScreen
	 * 关闭触发 init() 重建时，玩家选中的 Provider 能正确保留。
	 */
	private String lastSelectedServerProvider;
	private String lastSelectedLocalProvider;

	private enum Tab { SERVER, LOCAL }

	public MCCFConfigScreen(Screen parent) {
		super(Text.translatable("mccf.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int contentLeft = MARGIN;
		int contentTop = MARGIN + TAB_BAR_HEIGHT + TAB_BAR_GAP;
		int contentRight = this.width - MARGIN;
		// 控件区底边不再直接贴到屏幕底部——预留 BOTTOM_HINT_AREA_HEIGHT 的空间给
		// 提示文字，避免"最后一行按钮"和"底部提示文字"共用同一条 y 坐标基准线
		// 而相互覆盖（原设计的疏漏：两者都以 this.height - MARGIN 为基准，一个
		// 往下排列控件，一个往上排列文字，行数一多就会在中间撞上）。
		int contentBottom = this.height - MARGIN - BOTTOM_HINT_AREA_HEIGHT;

		// 标签栏：两个等宽按钮并排在顶部。选中的标签用不同措辞高亮当前状态——
		// Minecraft 原版 ButtonWidget 没有"激活态"的内建视觉，这里靠按钮文字加
		// 方括号标记当前标签，简单可靠，不必自定义渲染。
		int tabWidth = 200;
		int tabGap = 4;
		int tabBarWidth = tabWidth * 2 + tabGap;
		int tabBarLeft = contentLeft + (contentRight - contentLeft - tabBarWidth) / 2;

		serverTabButton = ButtonWidget.builder(tabLabel(Tab.SERVER), button -> switchTab(Tab.SERVER))
				.dimensions(tabBarLeft, MARGIN, tabWidth, TAB_BAR_HEIGHT)
				.build();
		addDrawableChild(serverTabButton);

		localTabButton = ButtonWidget.builder(tabLabel(Tab.LOCAL), button -> switchTab(Tab.LOCAL))
				.dimensions(tabBarLeft + tabWidth + tabGap, MARGIN, tabWidth, TAB_BAR_HEIGHT)
				.build();
		addDrawableChild(localTabButton);

		// 1.1.3 修复：重建前先从旧 Panel 读出 selectedProvider 保留下来。
		// init() 可能在窗口大小变化、ConfirmScreen/ModelSelectionScreen 关闭等场景被触发，
		// 每次都会 new 新 Panel，如果不保留 selectedProvider，玩家临时切换查看的 Provider
		// 会丢失（变回 activeProvider），导致强制关闭思考开关"看起来没生效"和获取模型列表
		// 后"Provider 切回 DeepSeek 丢失更改"两个 bug。
		if (serverPanel != null) {
			lastSelectedServerProvider = serverPanel.getSelectedProvider();
		}
		if (localPanel != null) {
			lastSelectedLocalProvider = localPanel.getSelectedProvider();
		}

		serverPanel = new ServerConfigPanel(this, contentLeft, contentTop, contentRight, contentBottom, this.height / 2);
		if (lastSelectedServerProvider != null) {
			serverPanel.setPreservedSelectedProvider(lastSelectedServerProvider);
		}
		serverPanel.init(this::addDrawableChild);

		localPanel = new LocalConfigPanel(this, contentLeft, contentTop, contentRight, contentBottom, this.height / 2);
		if (lastSelectedLocalProvider != null) {
			localPanel.setPreservedSelectedProvider(lastSelectedLocalProvider);
		}
		localPanel.init(this::addDrawableChild);

		applyTabVisibility();
	}

	private Text tabLabel(Tab tab) {
		String key = tab == Tab.SERVER ? "mccf.config.tab.server" : "mccf.config.tab.local";
		Text base = Text.translatable(key);
		return activeTab == tab ? Text.literal("» ").append(base).append(" «") : base;
	}

	private void switchTab(Tab tab) {
		if (activeTab == tab) return;
		activeTab = tab;
		applyTabVisibility();
		serverTabButton.setMessage(tabLabel(Tab.SERVER));
		localTabButton.setMessage(tabLabel(Tab.LOCAL));
	}

	/** 只显示当前标签对应的 Panel 控件，另一个标签的控件整体隐藏（不可见也不可交互）。 */
	private void applyTabVisibility() {
		boolean showServer = activeTab == Tab.SERVER;
		serverPanel.setVisible(showServer);
		localPanel.setVisible(!showServer);
	}

	// ----- 以下方法由 MCCFClient 的网络接收器通过 instanceof MCCFConfigScreen 触发 -----

	/** 收到服务端最新配置快照后调用。 */
	public void onSnapshotUpdated() {
		if (serverPanel != null) {
			serverPanel.onSnapshotUpdated();
		}
	}

	/** 收到服务端模型列表查询结果后调用。 */
	public void onModelsResult(boolean success, String providerId, java.util.List<String> models, String error) {
		if (serverPanel != null) {
			serverPanel.onModelsResult(success, providerId, models, error);
		}
	}

	/** ModelSelectionScreen 选中模型后的回调。 */
	public void setModelFromSelection(String model) {
		if (serverPanel != null) {
			serverPanel.setModelFromSelection(model);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 4, Colors.WHITE);

		if (activeTab == Tab.SERVER) {
			serverPanel.render(context, mouseX, mouseY, delta);
		} else {
			localPanel.render(context, mouseX, mouseY, delta);
		}
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}
}
