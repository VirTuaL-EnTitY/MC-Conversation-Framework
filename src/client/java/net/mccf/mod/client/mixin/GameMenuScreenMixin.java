package net.mccf.mod.client.mixin;

import net.mccf.mod.client.config.ChatHistoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给游戏暂停菜单（Esc 菜单）注入"聊天历史记录"按钮。
 *
 * 职责边界：
 * - 只管：在 GameMenuScreen 的 initWidgets 末尾追加一个按钮，点击后打开 ChatHistoryScreen
 * - 不管：ChatHistoryScreen 内部如何渲染历史记录（那是 ChatHistoryScreen 的职责）
 *
 * 为什么用 Mixin 而不是 ScreenEvents.afterInit：
 * ScreenEvents.afterInit 的回调拿到的是 Screen 对象，但 Screen.addDrawableChild 是
 * protected 方法，外部包无法调用——Fabric API 没有提供 public 的 widget 添加接口，
 * 直接往 Screens.getButtons() 返回的 list 里 add 只会加到 Fabric 注入的额外存储里，
 * 不会被注册到原版的渲染/点击管线。Mixin 把代码注入到 GameMenuScreen 内部，
 * 此时调用 addDrawableChild 是合法的（protected 在子类内部可访问），这是 Fabric
 * 模组给原版界面加按钮的标准做法。
 *
 * 为什么方法名是 initWidgets：1.20.5 起 Screen.init() 改名为 initWidgets()，
 * 1.21.1 沿用此名。如果升级到更早的版本（1.20.4 及以下）需要改回 init。
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

	protected GameMenuScreenMixin(Text title) {
		super(title);
	}

	@Inject(method = "initWidgets", at = @At("TAIL"))
	private void mccf$addChatHistoryButton(CallbackInfo ci) {
		// 动态计算按钮 Y 坐标：遍历原版已添加的所有子元素，找到最底部的那个，
		// 在它下方 4 像素处放置我们的按钮。这样无论原版布局怎么变（单人/多人、
		// 不同版本间距调整、其他模组也注入了按钮），都不会和原版按钮重叠。
		//
		// 之前用固定 y = height/4 + 104，实测在某些布局下和"保存并回到标题屏幕"
		// 按钮重叠——原版按钮的实际位置和文档/记忆里的可能有出入，硬编码 Y 太脆弱。
		int maxBottom = 0;
		for (Element child : this.children()) {
			if (child instanceof net.minecraft.client.gui.widget.ClickableWidget widget) {
				int bottom = widget.getY() + widget.getHeight();
				if (bottom > maxBottom) {
					maxBottom = bottom;
				}
			}
		}
		int buttonX = this.width / 2 - 102;
		int buttonY = maxBottom + 4;
		this.addDrawableChild(ButtonWidget.builder(
						Text.translatable("mccf.history.button"),
						button -> MinecraftClient.getInstance().setScreen(new ChatHistoryScreen(this)))
				.dimensions(buttonX, buttonY, 204, 20)
				.build());
	}
}
