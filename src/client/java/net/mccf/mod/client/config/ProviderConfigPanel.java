package net.mccf.mod.client.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "标签页内容"的共同骨架：左侧 Provider 列表 + 右侧对应设置面板。
 * {@link ServerConfigPanel}（服务端配置）和 {@link LocalConfigPanel}（本地设置）
 * 都继承这个类，保证两个标签页视觉/交互风格一致（需求确认里明确要求过）。
 *
 * 子类只需要实现"右侧设置区要放哪些控件"（{@link #buildRightPanel}）和
 * "如何取得/展示某个 Provider 的当前状态"，左侧列表的创建、Provider 是否为
 * activeProvider 的判断、选中查看的切换逻辑都在这里统一处理。
 *
 * 可见性管理：Screen 级别只有一个 init()，两个标签页的控件全部在 init() 时
 * 一次性创建好并 addDrawableChild 到同一个 Screen 里，切换标签靠整体设置
 * visible/active 隐藏非当前标签的控件——这比"动态增删 children"更简单可靠，
 * 不用操心 Minecraft Screen 内部 children 列表的时序问题。
 */
public abstract class ProviderConfigPanel {

	protected static final int LIST_WIDTH = 200;
	protected static final int GUTTER = 14;

	protected final Screen screen;
	protected final int left, top, right, bottom;
	/** 屏幕中心 Y 坐标——提示文字统一在此处下方绘制。 */
	protected final int screenCenterY;

	protected ProviderListWidget listWidget;
	/** 当前"选中查看"的 Provider——不等于已启用的 Provider，见类注释。 */
	protected String selectedProvider;

	/** 本面板创建的所有控件，用于统一 setVisible。 */
	private final List<ClickableWidget> ownedWidgets = new ArrayList<>();
	/** 当前标签页是否可见——子类计算字段 active 时应该与"字段本身是否可编辑"做 AND。 */
	protected boolean tabVisible = true;

	protected ProviderConfigPanel(Screen screen, int left, int top, int right, int bottom, int screenCenterY) {
		this.screen = screen;
		this.left = left;
		this.top = top;
		this.right = right;
		this.bottom = bottom;
		this.screenCenterY = screenCenterY;
		// 故意不在这里调用 initialSelectedProvider()：它是 abstract、由子类实现，而子类
		// 实现里访问的是子类自己的实例字段（ServerConfigPanel.state、LocalConfigPanel.config）。
		// Java 的实例字段初始化器在"父类构造器返回之后、子类构造器体之前"才执行——也就是说
		// 此刻从父类构造器回调子类的 override 时，state/config 仍是默认值 null，直接 NPE。
		// 这正是 0.4.0 配置界面一打开就崩 "Rendering screen / serverPanel is null" 的根因：
		// MCCFConfigScreen.init() 第 79 行 new ServerConfigPanel(...) 在 super() 里抛了 NPE，
		// 赋值没完成、serverPanel 保持 null，init() 的异常被上层吞掉后 render() 硬崩。
		// selectedProvider 改到 init() 里赋值（那时子类构造已全部完成、字段就绪），见 init()。
	}

	/** 初始化时列表应该选中哪个 Provider。 */
	protected abstract String initialSelectedProvider();

	/** 当前"已启用/生效中"的 Provider id——决定列表里哪一项打勾。 */
	protected abstract String activeProviderId();

	/** 右侧设置区的控件创建。返回创建的控件不需要手动 add——统一由本类处理。 */
	protected abstract void buildRightPanel(int panelLeft, int panelTop, int panelRight, int panelBottom);

	/** 玩家在列表里选中了不同的 Provider（仅切换查看，非立即生效）。 */
	protected abstract void onProviderSelected(String providerId);

	/** 面板自己的补充渲染（状态文字、提示等）。 */
	protected abstract void renderExtra(DrawContext context, int mouseX, int mouseY, float delta);

	/**
	 * 创建左侧列表 + 触发子类创建右侧控件。
	 *
	 * @param addChild 挂载普通可绘制控件（通常是 {@code Screen::addDrawableChild}）。
	 *        左侧 Provider 列表现在是一个 {@link ClickableWidget}，随 Screen 一起
	 *        渲染和接收输入，不再使用 addSelectableChild + 手动 render 的模式。
	 */
	public void init(Consumer<ClickableWidget> addChild) {
		ownedWidgets.clear();

		// 在这里（而非构造器里）确定初始选中的 Provider：此刻子类构造已全部完成，
		// initialSelectedProvider() 里访问的 state/config 等字段均已初始化。原先在父类
		// 构造器里调用这个 override 会踩"字段初始化器晚于父类构造器"的坑导致 NPE
		// （详见本类构造器注释），这里 deferred 赋值既绕开该坑，又不改变"列表初始选中项"
		// 的语义。selectedProvider 在构造完成到 init() 之间是 null，但这段窗口内没有任何
		// 代码读取它（MCCFConfigScreen.init() 是构造完立刻 init()），所以安全。
		this.selectedProvider = initialSelectedProvider();

		listWidget = new ProviderListWidget(left, top, LIST_WIDTH, bottom - top,
				ClientConfigState.PROVIDER_IDS, selectedProvider, this::activeProviderId,
				this::selectProvider);
		addChild.accept(listWidget);

		int panelLeft = left + LIST_WIDTH + GUTTER;
		buildRightPanel(panelLeft, top, right, bottom);

		for (ClickableWidget widget : ownedWidgets) {
			addChild.accept(widget);
		}
	}

	/** 子类在 buildRightPanel 里创建控件后调用这个方法登记，而不是直接 add——统一走 setVisible 管理。 */
	protected <T extends ClickableWidget> T own(T widget) {
		ownedWidgets.add(widget);
		return widget;
	}

	private void selectProvider(String providerId) {
		if (providerId.equals(selectedProvider)) return;
		selectedProvider = providerId;
		onProviderSelected(providerId);
	}

	/**
	 * 切换标签页可见性。同时设置 visible（控制渲染 + 官方输入路由的第一道防线）
	 * 和触发子类重新计算每个控件的 active（第二道防线：即使 Minecraft 某个
	 * 版本对 visible=false 控件的事件路由处理不够彻底，active=false 也能确保
	 * 不会误触）。两道防线保证切到未显示的标签页时那些控件绝对不可交互。
	 */
	public void setVisible(boolean visible) {
		this.tabVisible = visible;
		if (listWidget != null) {
			listWidget.visible = visible;
		}
		for (ClickableWidget widget : ownedWidgets) {
			widget.visible = visible;
		}
		onTabVisibilityChanged();
	}

	/** 子类在这里根据 tabVisible + 自己的编辑态逻辑，重新计算每个控件的 active。 */
	protected abstract void onTabVisibilityChanged();

	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// 左侧列表 widget 已通过 addChild 加入 Screen，由 Screen 统一渲染，
		// 这里只需绘制右侧面板自身的额外内容。
		renderExtra(context, mouseX, mouseY, delta);
	}

	protected void drawLabel(DrawContext context, Text text, int x, int y, int color) {
		context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x, y, color);
	}

	protected void drawLabel(DrawContext context, Text text, int x, int y) {
		drawLabel(context, text, x, y, Colors.WHITE);
	}
}
