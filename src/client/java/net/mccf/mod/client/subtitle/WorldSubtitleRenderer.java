package net.mccf.mod.client.subtitle;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/**
 * 渲染 VISIBLE 模式字幕：悬浮在说话者头顶，采用与原版玩家名牌相同的
 * "billboard"（始终面向摄像机）绘制方式，随玩家移动实时跟随。
 *
 * 采用 nameplate 渲染的经典模式：以实体世界坐标为锚点，减去摄像机位置得到
 * 相对坐标，压入 MatrixStack，再叠加一个与摄像机朝向相反的旋转，使文字
 * 始终朝向玩家——这与原版 EntityRenderer#renderLabelIfPresent 的做法一致，
 * 是 Minecraft 客户端渲染中最稳定、跨版本变动最小的部分。
 *
 * 挂载点：由 MCCFClient 在 onInitializeClient 中通过
 * WorldRenderEvents.AFTER_ENTITIES 注册本类的 {@link #render} 方法。
 */
public class WorldSubtitleRenderer {

	private static final float TEXT_SCALE = 0.025f;
	private static final double VERTICAL_OFFSET = 0.5; // 头顶再往上偏移多少格

	public void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) return;

		List<ActiveSubtitle> subtitles = SubtitleManager.getActiveAndPrune();
		if (subtitles.isEmpty()) return;

		Camera camera = context.camera();
		Vec3d cameraPos = camera.getPos();
		VertexConsumerProvider consumers = context.consumers();
		if (consumers == null) return;
		TextRenderer textRenderer = client.textRenderer;

		for (ActiveSubtitle subtitle : subtitles) {
			if (subtitle.mode() != ActiveSubtitle.Mode.VISIBLE) continue;

			Entity speakerEntity = findEntity(client, subtitle.speakerId());
			if (speakerEntity == null) continue;

			// 1.21.1 上方法名是 getTickDelta(boolean)；1.21.8 改名为 getTickProgress(boolean)。
			Vec3d entityPos = speakerEntity.getLerpedPos(context.tickCounter().getTickDelta(true));
			double x = entityPos.x - cameraPos.x;
			double y = entityPos.y - cameraPos.y + speakerEntity.getHeight() + VERTICAL_OFFSET;
			double z = entityPos.z - cameraPos.z;

			String line = subtitle.speakerName() + ": " + subtitle.translatedText();

			MatrixStack matrices = context.matrixStack();
			if (matrices == null) continue;

			matrices.push();
			matrices.translate(x, y, z);
			// Billboard：与摄像机旋转相反，使文字始终朝向玩家。
			matrices.multiply(camera.getRotation());
			matrices.scale(-TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);

			float textWidth = textRenderer.getWidth(line);
			int backgroundColor = MathHelper.floor(0.25f * 255.0f) << 24;

			matrices.translate(-textWidth / 2.0, 0, 0);
			textRenderer.draw(
					line,
					0, 0,
					// 关键修复：0xFFFFFF 缺少最高 8 位的 alpha 通道，等价于
					// 0x00FFFFFF（alpha=0，完全透明），导致字幕实际上被绘制了
					// 但完全不可见。ARGB 格式下 alpha 必须显式置满（0xFF）才是
					// 不透明白色：0xFFFFFFFF。这就是"两人可见面时字幕未显示"
					// 的根本原因——不是没有渲染，而是渲染成了透明的。
					0xFFFFFFFF,
					false,
					matrices.peek().getPositionMatrix(),
					consumers,
					TextRenderer.TextLayerType.SEE_THROUGH,
					backgroundColor,
					0xF000F0
			);

			matrices.pop();
		}
	}

	private Entity findEntity(MinecraftClient client, UUID entityUuid) {
		if (client.world == null) return null;
		if (client.player != null && client.player.getUuid().equals(entityUuid)) {
			return client.player;
		}
		for (net.minecraft.entity.player.PlayerEntity player : client.world.getPlayers()) {
			if (player.getUuid().equals(entityUuid)) {
				return player;
			}
		}
		return null;
	}
}
