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
 * 1.21.1 上 {@link net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget}
 * 的 {@code itemHeight} 不可配置（固定 36px），且 {@link net.minecraft.client.gui.widget.EntryListWidget}
 * 没有 x 坐标参数，会贴到屏幕最左边。本类改用 {@link ClickableWidget} 自行绘制
 * 紧凑列表，行高 20px，并正确对齐到配置面板左侧。
 *
 * 交互：
 * - 点击某一行 = 仅切换"当前选中查看/编辑"的 Provider，不等于立即启用，
 *   仍需右侧面板的保存按钮才会把它写成 activeProvider 并生效。
 * - "已启用"（等于当前 activeProvider）和"选中查看"是两种独立状态，
 *   视觉上分开：选中查看=整行高亮描边背景；已启用=行内 ✓ 图标 + 绿色 Provider 名。
 *   一个 Provider 可以同时是"已启用"又"被选中查看"（比如刚打开界面时）。
 */
public class ProviderListWidget extends ClickableWidget {

	/** 每个列表项的高度。8 个 Provider 总共只占 160px。 */
	private static final int ENTRY_HEIGHT = 20;
	/** 悬浮/选中背景色的透明度。 */
	private static final int HOVER_BG = 0x22FFFFFF;
	private static final int SELECTED_BG = 0x4488FF88;

	public interface ActiveProvider {
		String get();
	}

	private final String[] providerIds;
	private final ActiveProvider activeProvider;
	private final Consumer<String> onSelect;
	private String selectedProvider;

	public ProviderListWidget(int x, int y, int width, int height,
							  String[] providerIds, String initiallySelected,
							  ActiveProvider activeProvider, Consumer<String> onSelect) {
		super(x, y, width, height, Text.empty());
		this.providerIds = providerIds;
		this.activeProvider = activeProvider;
		this.onSelect = onSelect;
		this.selectedProvider = initiallySelected;
	}

	/** 从代码里（而非玩家点击）重新设定选中项——例如收到服务端快照确认后，把列表选中态跟回真正生效的 Provider。 */
	public void setSelectedProvider(String providerId) {
		this.selectedProvider = providerId;
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int x = getX();
		int y = getY();
		int width = getWidth();
		int maxY = y + getHeight();

		for (String id : providerIds) {
			if (y + ENTRY_HEIGHT > maxY) break;

			boolean isSelected = id.equals(selectedProvider);
			boolean isActive = id.equals(activeProvider.get());
			boolean rowHovered = hovered && mouseX >= x && mouseX < x + width
					&& mouseY >= y && mouseY < y + ENTRY_HEIGHT;

			if (isSelected) {
				context.fill(x, y, x + width, y + ENTRY_HEIGHT, SELECTED_BG);
			} else if (rowHovered) {
				context.fill(x, y, x + width, y + ENTRY_HEIGHT, HOVER_BG);
			}

			int textColor = isActive ? Colors.GREEN : Colors.WHITE;
			int textX = x + 6;
			if (isActive) {
				// 行首打勾标记"已启用/生效中"，与"仅选中查看"的描边区分开。
				context.drawTextWithShadow(textRenderer, Text.literal("✓"), textX,
						y + (ENTRY_HEIGHT - textRenderer.fontHeight) / 2, Colors.GREEN);
				textX += textRenderer.getWidth("✓ ");
			}
			context.drawTextWithShadow(textRenderer,
					Text.translatable(ClientConfigState.providerNameKey(id)),
					textX, y + (ENTRY_HEIGHT - textRenderer.fontHeight) / 2, textColor);

			y += ENTRY_HEIGHT;
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		// 列表本身的朗读消息可省略；需要时可为当前选中项生成。
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		int rowY = getY();
		int maxY = getY() + getHeight();
		for (String id : providerIds) {
			if (rowY > maxY) break;
			if (mouseY >= rowY && mouseY < rowY + ENTRY_HEIGHT) {
				this.selectedProvider = id;
				onSelect.accept(id);
				return;
			}
			rowY += ENTRY_HEIGHT;
		}
	}
}
