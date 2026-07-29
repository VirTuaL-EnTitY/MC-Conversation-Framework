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

	private final Screen parent;

	private ServerConfigPanel serverPanel;
	private LocalConfigPanel localPanel;

	/** 当前显示哪个面板。默认服务端配置——多数玩家打开设置是为了改服务器上生效的 Provider。 */
	private Tab activeTab = Tab.SERVER;

	private ButtonWidget serverTabButton;
	private ButtonWidget localTabButton;

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
		int contentBottom = this.height - MARGIN;

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

		serverPanel = new ServerConfigPanel(this, contentLeft, contentTop, contentRight, contentBottom, this.height / 2);
		serverPanel.init(this::addDrawableChild);

		localPanel = new LocalConfigPanel(this, contentLeft, contentTop, contentRight, contentBottom, this.height / 2);
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
