package net.mccf.mod.client.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

import java.util.function.Consumer;

/**
 * 左侧 Provider 列表，两个标签页（服务端配置 / 本地设置）共用同一套组件，
 * 保证视觉风格一致。
 *
 * 交互（见需求确认）：
 * - 点击某一行 = 仅切换"当前选中查看/编辑"的 Provider，不等于立即启用，
 *   仍需右侧面板的保存按钮才会把它写成 activeProvider 并生效。这与
 *   MCCFConfigScreen 原有"点保存才生效"的整体交互模型保持一致，避免误触。
 * - "已启用"（等于当前 activeProvider）和"选中查看"是两种独立状态，
 *   视觉上分开：选中查看=整行高亮描边背景；已启用=行内 ✓ 图标 + 绿色 Provider 名。
 *   一个 Provider 可以同时是"已启用"又"被选中查看"（比如刚打开界面时）。
 */
public class ProviderListWidget extends AlwaysSelectedEntryListWidget<ProviderListWidget.ProviderEntry> {

	public interface ActiveProvider {
		String get();
	}

	public ProviderListWidget(MinecraftClient client, int width, int height, int top, int bottom,
							   String[] providerIds, String initiallySelected, ActiveProvider activeProvider,
							   Consumer<String> onSelect) {
		super(client, width, height, top, bottom);
		for (String id : providerIds) {
			ProviderEntry entry = new ProviderEntry(id, activeProvider, onSelect);
			addEntry(entry);
			if (id.equals(initiallySelected)) {
				setSelected(entry);
			}
		}
	}

	/** 从代码里（而非玩家点击）重新设定选中项——例如收到服务端快照确认后，把列表选中态跟回真正生效的 Provider。 */
	public void setSelectedProvider(String providerId) {
		for (int i = 0; i < getEntryCount(); i++) {
			ProviderEntry entry = getEntry(i);
			if (entry.providerId().equals(providerId)) {
				setSelected(entry);
				return;
			}
		}
	}

	public static class ProviderEntry extends AlwaysSelectedEntryListWidget.Entry<ProviderEntry> {
		private final String providerId;
		private final ActiveProvider activeProvider;
		private final Consumer<String> onSelect;

		ProviderEntry(String providerId, ActiveProvider activeProvider, Consumer<String> onSelect) {
			this.providerId = providerId;
			this.activeProvider = activeProvider;
			this.onSelect = onSelect;
		}

		@Override
		public Text getNarration() {
			return Text.translatable(ClientConfigState.providerNameKey(providerId));
		}

		@Override
		public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
							int mouseX, int mouseY, boolean hovered, float delta) {
			boolean isActive = providerId.equals(activeProvider.get());
			var textRenderer = MinecraftClient.getInstance().textRenderer;

			// 悬浮的行给一层很淡的背景，帮助鼠标定位；真正的"选中"高亮由父类
			// AlwaysSelectedEntryListWidget 在 renderItem 外层已经画了描边，
			// 这里只需要处理"已启用"的额外视觉标记。
			if (hovered) {
				context.fill(x, y, x + entryWidth, y + entryHeight, 0x22FFFFFF);
			}

			int textColor = isActive ? Colors.GREEN : Colors.WHITE;
			int textX = x + 6;
			if (isActive) {
				// 行首打勾标记"已启用/生效中"，与"仅选中查看"的描边区分开。
				context.drawTextWithShadow(textRenderer, Text.literal("✓"), textX, y + (entryHeight - textRenderer.fontHeight) / 2, Colors.GREEN);
				textX += textRenderer.getWidth("✓ ");
			}
			context.drawTextWithShadow(textRenderer,
					Text.translatable(ClientConfigState.providerNameKey(providerId)),
					textX, y + (entryHeight - textRenderer.fontHeight) / 2, textColor);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			onSelect.accept(providerId);
			return true;
		}

		public String providerId() {
			return providerId;
		}
	}
}
