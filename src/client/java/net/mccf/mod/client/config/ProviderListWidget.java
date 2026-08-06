package net.mccf.mod.client.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.util.function.Consumer;

/**
 * 左侧 Provider 列表，两个标签页（服务端配置 / 本地设置）共用同一套组件，
 * 保证视觉风格一致。
 *
 * 1.21.1 上 {@link net.minecraft.client.gui.widget.EntryListWidget} 的列表内容
 * 会贴到屏幕最左边（{@code getRowLeft()} 默认返回靠左的固定偏移，没有让列表对齐
 * 到配置面板区域的 x 参数），且 itemHeight 虽可在构造时指定但仍是列表级统一、
 * 不支持每条目不同高度。本类改用 {@link ClickableWidget} 自行绘制紧凑列表，
 * 行高 20px，并正确对齐到配置面板左侧。
 *
 * 历史踩坑修正（2026-07）：原注释写"itemHeight 不可配置（固定 36px）"是误判——
 * 1.21.1 的构造函数第 5 参数就是 itemHeight，可配置；当时误把第 5 参数当 bottom，
 * 才以为行高被锁死在某个大值。详见 ChatHistoryScreen.HistoryListWidget 注释。
 * 本类改用自绘的另一个理由（getRowLeft 贴左、无法对齐面板）依然成立，所以实现不改。
 *
 * 滚动支持（2026-07 修复"DeepSeek 消失"问题）：8 个 Provider × 20px 行高 = 160px，
 * 原实现是纯静态渲染，`y + ENTRY_HEIGHT > maxY` 直接 break 掉超出可视区域的条目，
 * 完全没有滚动能力——一旦分配给列表的高度小于 160px（例如屏幕分辨率较小、或者
 * 配置界面为底部提示文字预留空间后可用高度被压缩），排在后面的 Provider（当时
 * 是 deepseek 和 ollama）就会被直接截断、玩家完全看不到也点不到。现在维护一个
 * {@link #scrollOffset}（像素为单位），支持鼠标滚轮滚动；内容总高度超出可视区域
 * 时右侧画一条简易滚动条（纯视觉提示，不可拖拽——列表项本来就不多，滚轮足够，
 * 不必再实现拖拽滚动条的完整交互逻辑）。
 *
 * 交互：
 * - 点击某一行 = 仅切换"当前选中查看/编辑"的 Provider，不等于立即启用，
 *   仍需右侧面板的保存按钮才会把它写成 activeProvider 并生效。
 * - "已启用"（等于当前 activeProvider）和"选中查看"是两种独立状态，
 *   视觉上分开：选中查看=整行高亮描边背景；已启用=行内 ✓ 图标 + 绿色 Provider 名。
 *   一个 Provider 可以同时是"已启用"又"被选中查看"（比如刚打开界面时）。
 */
public class ProviderListWidget extends ClickableWidget {

	/** 每个列表项的高度。8 个 Provider 总共占 160px，超出可视区域时需要滚动。 */
	private static final int ENTRY_HEIGHT = 20;
	/** 悬浮/选中背景色的透明度。 */
	private static final int HOVER_BG = 0x22FFFFFF;
	private static final int SELECTED_BG = 0x4488FF88;
	/** 滚动条宽度（仅在内容超出可视高度时绘制）。 */
	private static final int SCROLLBAR_WIDTH = 4;
	/** 每次滚轮滚动的像素距离，取一行高度，滚动手感和"一次滚一条"一致。 */
	private static final int SCROLL_STEP = ENTRY_HEIGHT;

	public interface ActiveProvider {
		String get();
	}

	private final String[] providerIds;
	private final ActiveProvider activeProvider;
	private final Consumer<String> onSelect;
	private String selectedProvider;

	/** 当前纵向滚动偏移（像素），范围 [0, maxScrollOffset]。 */
	private int scrollOffset = 0;

	/**
	 * 滚动锁定标志——true 时禁止玩家手动滚动。
	 *
	 * 1.1.5 新增：非 op 玩家打开服务端配置标签页时，Provider 列表应该禁止手动滚动
	 * （但 init 时仍会自动定位到 activeProvider，见 {@link #scrollToProvider}）。
	 * 锁定后玩家无法用滚轮改变 scrollOffset，但仍能看到自动定位后的 activeProvider 附近区域。
	 */
	private boolean scrollLocked = false;

	/**
	 * 选中锁定标志——true 时禁止玩家点击切换选中项。
	 *
	 * 1.1.5 新增：非 op 玩家打开服务端配置标签页时，Provider 列表应该禁止选中切换
	 * （非 op 只能看 activeProvider 的状态，不能切到别的 Provider 查看——没有意义，
	 * 看了也改不了）。锁定时点击不触发 onSelect 回调。
	 */
	private boolean selectionLocked = false;

	/**
	 * 灰显标志——true 时列表所有文字用灰色绘制（包括 active provider 的绿色标记）。
	 *
	 * 1.1.5 新增：非 op 玩家打开服务端配置标签页时，整个列表应该视觉上呈现"不可用"
	 * 状态——不只是锁定交互，还要让玩家一眼看出"这不是给我操作的"。
	 * 单纯锁定滚动和选中但颜色正常，玩家可能以为"列表能用、只是这几个 Provider
	 * 碰巧不能选"；全部变灰配合横幅提示，语义最清晰。
	 * active provider 的 ✓ 标记和绿色文字也一起变灰——非 op 玩家看到的应该是
	 * "只读展示"而非"正常列表但点不了"，保留绿色会让 active provider 看起来
	 * 像是"可选的"，与只读语义冲突。
	 */
	private boolean greyedOut = false;

	public ProviderListWidget(int x, int y, int width, int height,
							  String[] providerIds, String initiallySelected,
							  ActiveProvider activeProvider, Consumer<String> onSelect) {
		super(x, y, width, height, Text.empty());
		this.providerIds = providerIds;
		this.activeProvider = activeProvider;
		this.onSelect = onSelect;
		this.selectedProvider = initiallySelected;
	}

	/** 设置滚动锁定状态——true 禁止手动滚动，false 恢复正常。 */
	public void setScrollLocked(boolean locked) {
		this.scrollLocked = locked;
	}

	/** 设置选中锁定状态——true 禁止点击切换，false 恢复正常。 */
	public void setSelectionLocked(boolean locked) {
		this.selectionLocked = locked;
	}

	/** 设置灰显状态——true 时所有文字变灰（含 active 标记），false 恢复正常配色。 */
	public void setGreyedOut(boolean greyedOut) {
		this.greyedOut = greyedOut;
	}

	/**
	 * 把列表滚动到让指定 Provider 完整可见的位置。
	 *
	 * 1.1.5 新增：应用户要求"每次打开界面自动滚动到选中并应用的 Provider"。
	 * 旧版每次 init() 重建都把 scrollOffset 重置为 0，如果 activeProvider 排在
	 * 列表靠后位置（比如 ollama 排第 8 个），玩家每次打开都要手动滚下去找。
	 *
	 * 自动定位逻辑：计算目标 Provider 的行 y 坐标，如果已在可视区域内不动；
	 * 在可视区域上方则往上滚到刚好完整显示该行；在下方则往下滚到刚好完整显示。
	 * 优先保证该行可见，不追求居中——列表本来就不长，能看到就行。
	 */
	public void scrollToProvider(String providerId) {
		int targetIndex = -1;
		for (int i = 0; i < providerIds.length; i++) {
			if (providerIds[i].equals(providerId)) {
				targetIndex = i;
				break;
			}
		}
		if (targetIndex < 0) return;

		int rowY = targetIndex * ENTRY_HEIGHT;
		int viewHeight = getHeight();
		int max = maxScrollOffset();

		// 如果该行在当前偏移下已经完整可见，不动。
		if (rowY >= scrollOffset && rowY + ENTRY_HEIGHT <= scrollOffset + viewHeight) {
			return;
		}
		// 该行在可视区域上方：滚到该行顶部对齐可视区域顶部。
		if (rowY < scrollOffset) {
			scrollOffset = rowY;
		} else {
			// 该行在可视区域下方：滚到该行底部对齐可视区域底部。
			scrollOffset = rowY + ENTRY_HEIGHT - viewHeight;
		}
		scrollOffset = Math.max(0, Math.min(max, scrollOffset));
	}

	/** 从代码里（而非玩家点击）重新设定选中项——例如收到服务端快照确认后，把列表选中态跟回真正生效的 Provider。 */
	public void setSelectedProvider(String providerId) {
		this.selectedProvider = providerId;
	}

	/** 内容总高度（所有条目排列后的总像素高度，不受可视区域大小影响）。 */
	private int contentHeight() {
		return providerIds.length * ENTRY_HEIGHT;
	}

	/** 最大可滚动偏移——内容超出可视区域的部分；内容比容器矮时为 0（无需滚动）。 */
	private int maxScrollOffset() {
		return Math.max(0, contentHeight() - getHeight());
	}

	private boolean needsScrollbar() {
		return maxScrollOffset() > 0;
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int x = getX();
		int y = getY();
		int width = getWidth();
		int viewTop = y;
		int viewBottom = y + getHeight();

		// 内容超出可视区域时，右侧列宽预留给文字的空间要收窄一点，避免文字压到
		// 滚动条上；未超出时不留白，充分利用宽度（大多数情况下 8 个条目其实
		// 是能放下的，只在容器被压得很矮时才会触发滚动）。
		int textAreaWidth = needsScrollbar() ? width - SCROLLBAR_WIDTH - 2 : width;

		// 用裁剪区域限制渲染范围到列表自身的可视矩形内——滚动偏移会让部分条目的
		// 计算 y 坐标跑到 viewTop 之上或 viewBottom 之下，不裁剪的话这些条目会
		// 画到列表框外面，可能盖住上面的标签栏或下面的提示文字。
		context.enableScissor(x, viewTop, x + width, viewBottom);

		int rowY = y - scrollOffset;
		String hoveredProviderId = null;
		for (String id : providerIds) {
			// 完全在可视区域上方或下方的条目跳过渲染（性能优化，且反正会被
			// scissor 裁掉，跳过只是省一次没必要的绘制调用）。
			if (rowY + ENTRY_HEIGHT > viewTop && rowY < viewBottom) {
				boolean isSelected = id.equals(selectedProvider);
				boolean isActive = id.equals(activeProvider.get());
				// 1.1.5：灰显模式下禁用 hover 效果——非 op 玩家的列表是只读展示，
				// 不应该有 hover 反馈（hover 反馈暗示"可交互"），只画选中态描边
				// 让玩家知道当前看的是哪个 Provider。
				boolean rowHovered = !greyedOut && hovered && mouseX >= x && mouseX < x + width
						&& mouseY >= Math.max(rowY, viewTop) && mouseY < Math.min(rowY + ENTRY_HEIGHT, viewBottom);

				if (rowHovered) {
					// 记录下来，留到 scissor 解除之后再画 tooltip（见下方）——tooltip
					// 内容可能比列表本身宽，如果在 scissor 区域内画会被裁掉右侧/底部
					// 超出列表框的部分，必须在 disableScissor() 之后单独画。
					hoveredProviderId = id;
				}

				if (isSelected) {
					context.fill(x, rowY, x + textAreaWidth, rowY + ENTRY_HEIGHT, SELECTED_BG);
				} else if (rowHovered) {
					context.fill(x, rowY, x + textAreaWidth, rowY + ENTRY_HEIGHT, HOVER_BG);
				}

				// 1.1.5：灰显时所有文字用灰色，包括 active provider 的绿色标记——
				// 非 op 列表是只读展示，保留绿色会让 active provider 看起来"可选"，
				// 与只读语义冲突。非灰显时保持原配色（active=绿、其他=白）。
				int textColor = greyedOut ? Colors.GRAY : (isActive ? Colors.GREEN : Colors.WHITE);
				int checkColor = greyedOut ? Colors.GRAY : Colors.GREEN;
				int textX = x + 6;
				if (isActive) {
					// 行首打勾标记"已启用/生效中"，与"仅选中查看"的描边区分开。
					context.drawTextWithShadow(textRenderer, Text.literal("✓"), textX,
							rowY + (ENTRY_HEIGHT - textRenderer.fontHeight) / 2, checkColor);
					textX += textRenderer.getWidth("✓ ");
				}
				context.drawTextWithShadow(textRenderer,
						Text.translatable(ClientConfigState.providerNameKey(id)),
						textX, rowY + (ENTRY_HEIGHT - textRenderer.fontHeight) / 2, textColor);
			}

			rowY += ENTRY_HEIGHT;
		}

		context.disableScissor();

		// 悬浮在列表任意一项上（不需要先点中它）就弹出该 Provider 的说明 tooltip——
		// 应用户明确要求"悬浮在 Kimi、DeepSeek 这些列表项上就弹出对应说明，而不需要
		// 先点中它们"。之前的实现只在悬浮到顶部"当前选中查看"的标题上才弹 tooltip，
		// 这里挪到列表本身，覆盖到全部 8 个 Provider 而不只是当前选中的那一个。
		// 必须在 disableScissor() 之后画（见上方 hoveredProviderId 记录处的说明）。
		if (hoveredProviderId != null) {
			Text hoverDesc = Text.translatable("mccf.config.provider_hint." + hoveredProviderId);
			context.drawTooltip(textRenderer, hoverDesc, mouseX, mouseY);
		}

		if (needsScrollbar()) {
			int max = maxScrollOffset();
			int trackHeight = getHeight();
			// 滑块高度正比于"可视区域 / 内容总高度"的比例，最小 8px 避免内容极多时
			// 滑块小到看不见/点不到（虽然本组件不支持拖拽，纯视觉提示，但保留这个
			// 下限是良好实践，万一以后加拖拽交互也不用回头改）。
			int thumbHeight = Math.max(8, trackHeight * trackHeight / contentHeight());
			int thumbY = y + (max == 0 ? 0 : (trackHeight - thumbHeight) * scrollOffset / max);
			int scrollbarX = x + width - SCROLLBAR_WIDTH;
			context.fill(scrollbarX, y, scrollbarX + SCROLLBAR_WIDTH, y + trackHeight, 0x33FFFFFF);
			context.fill(scrollbarX, thumbY, scrollbarX + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0x88FFFFFF);
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		// 列表本身的朗读消息可省略；需要时可为当前选中项生成。
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		// 1.1.5：滚动锁定时禁止手动滚动（非 op 玩家打开服务端配置时）。
		if (scrollLocked) return false;
		if (!needsScrollbar()) return false;
		if (!(mouseX >= getX() && mouseX < getX() + getWidth() && mouseY >= getY() && mouseY < getY() + getHeight())) {
			return false;
		}
		// verticalAmount 为正表示向上滚（原版惯例），对应减小 scrollOffset（内容往下移，
		// 露出更前面的条目）。手写 min/max 夹逼而不是依赖 MathHelper.clamp——
		// 减少一个跨版本签名不确定的 API 依赖点，min/max 是 java.lang.Math 的
		// 标准静态方法，签名从未变过，最大程度保证能编译通过。
		int delta = (int) Math.signum(verticalAmount) * SCROLL_STEP;
		scrollOffset = Math.max(0, Math.min(maxScrollOffset(), scrollOffset - delta));
		return true;
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		// 1.1.5：选中锁定时禁止点击切换（非 op 玩家打开服务端配置时）。
		// ClickableWidget.onClick 在 1.21.1 返回 void（不是 boolean），所以这里
		// 用 return 提前退出方法即可实现"锁定时不执行选中"，不需要返回值告知父类。
		// 父类调用 onClick 时不会检查返回值，锁定时方法体直接 return 不做任何事，
		// 等同于"点击被吞掉"——视觉上玩家点了没反应，正是非 op 该有的行为。
		if (selectionLocked) return;
		int rowY = getY() - scrollOffset;
		int viewTop = getY();
		int viewBottom = getY() + getHeight();
		for (String id : providerIds) {
			// 点击判定同样要求命中"实际可视范围内"的那部分行高，避免滚动到一半、
			// 某一行只露出一半时，点击被裁剪掉的那一半区域也能误触发选中。
			int rowTop = Math.max(rowY, viewTop);
			int rowBottom = Math.min(rowY + ENTRY_HEIGHT, viewBottom);
			if (rowBottom > rowTop && mouseY >= rowTop && mouseY < rowBottom) {
				this.selectedProvider = id;
				onSelect.accept(id);
				return;
			}
			rowY += ENTRY_HEIGHT;
		}
	}
}
