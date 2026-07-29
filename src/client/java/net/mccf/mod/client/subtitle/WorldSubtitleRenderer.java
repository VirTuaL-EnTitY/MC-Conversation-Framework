package net.mccf.mod.client.subtitle;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 渲染 VISIBLE 模式字幕：显示在说话者模型旁边（靠近相机的一侧），采用与原版玩家名牌相同的
 * "billboard"（始终面向摄像机）绘制方式，随玩家移动实时跟随。
 *
 * 只管"把字幕画到世界空间里说话者模型旁边"这一件事，不管字幕的生命周期管理（由
 * SubtitleManager 负责）、不管 AUDIBLE 模式的 HUD 渲染（由 HotbarSubtitleRenderer 负责）。
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
	// 字幕在说话者旁边的水平偏移量（格）。太近（<0.5）会贴在模型身上不美观且容易穿模，
	// 太远（>1.2）会让字幕脱离说话者语境、难以关联是谁在说话；0.8 格是视觉关联性和
	// 美观度之间的经验平衡点。
	private static final double SIDE_OFFSET = 0.8;
	// 字幕高度 = 实体高度 × 此值。0.5 对应腰部到胸部位置——太高（>0.8）像头顶名牌，
	// 容易和其他玩家的名牌、头顶光环类模组冲突；太低（<0.3）像脚牌，视线不容易自然扫到。
	private static final double HEIGHT_RATIO = 0.5;
	// 超过此距离（格）不渲染字幕——远处说话者根本看不清字幕内容，强行渲染只会增加
	// 帧开销和画面杂乱度。
	private static final double RENDER_DISTANCE = 32.0;
	// 超过此距离（格）开始缩小字号——近距离保持原大小保证可读性，远距离缩小避免
	// 占据过多屏幕空间。
	private static final double FADE_START_DISTANCE = 16.0;
	// 世界空间字幕每行最多字符数。世界空间字幕跟随实体移动、尺寸小，过长的单行文本
	// 会横向拉伸过宽、遮挡画面中的其他视觉元素。15 个字符（含中英文混合）是
	// 在 0.025 缩放比例下单行视觉宽度约 0.4 格的近似上限。
	private static final int MAX_CHARS_PER_LINE = 15;
	// 世界空间字幕最多行数。超过 3 行后视觉上会变成"文字墙"，且随实体移动时
	// 多行文本的抖动感更强，影响阅读体验。
	private static final int MAX_LINES = 3;
	// TextRenderer 原生空间中的行高（像素）。原版字体高 8 像素 + 2 像素行间距 = 10。
	private static final int WORLD_LINE_HEIGHT = 10;
	// 背景色：约 69% 不透明度黑。世界空间字幕的背景是复杂的世界场景（天空、地形、
	// 实体模型），低不透明度会导致文字与背景混淆看不清。参考原版辅助字幕（Options >
	// Accessibility > Show Subtitles）的背景风格，69% 是可读性和不遮挡视野之间的
	// 经验平衡值。此值与 HotbarSubtitleRenderer 保持一致以保证两套渲染器视觉统一。
	private static final int BACKGROUND_COLOR = 0xB0000000;

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

			// 相机空间坐标（世界坐标减去相机坐标）
			double dx = entityPos.x - cameraPos.x;
			double dy = entityPos.y - cameraPos.y;
			double dz = entityPos.z - cameraPos.z;

			// 3D 距离用于距离衰减和剔除
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance > RENDER_DISTANCE) continue;

			// 距离衰减：16~32 格之间字号从 1.0 线性缩小到 0.5，保证远处字幕不占据过多屏幕
			float scaleFactor = 1.0f;
			if (distance > FADE_START_DISTANCE) {
				float t = (float) ((distance - FADE_START_DISTANCE) / (RENDER_DISTANCE - FADE_START_DISTANCE));
				scaleFactor = 1.0f - t * 0.5f;
			}
			float scale = TEXT_SCALE * scaleFactor;

			// 计算从说话者指向相机的水平方向向量（归一化），用于决定字幕偏移到模型的哪一侧。
			// 选"靠近相机的一侧"而非随机一侧或固定方向，是因为靠近相机的一侧从玩家视角看
			// 不会被说话者自身的身体模型遮挡——如果偏移到远离相机的一侧，玩家视角下说话者
			// 的身体会挡住字幕。这个设计决策来源于早期版本"头顶悬浮"方案在多人密集场景下
			// 的体验问题：多个玩家站在一起时头顶字幕会互相重叠遮挡，改到旁边且靠近相机侧
			// 后，即使多人紧挨着站立，各自的字幕也不会互相遮挡。
			double dirX = -dx;
			double dirZ = -dz;
			double horizLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
			if (horizLen > 1e-6) {
				dirX /= horizLen;
				dirZ /= horizLen;
			} else {
				// 相机和说话者几乎在同一水平位置（俯视/仰视），水平方向退化，不做偏移
				dirX = 0;
				dirZ = 0;
			}

			// 字幕位置 = 实体位置 + 水平偏移（靠近相机侧 0.8 格）+ 垂直偏移（腰部高度）
			double x = dx + dirX * SIDE_OFFSET;
			double y = dy + speakerEntity.getHeight() * HEIGHT_RATIO;
			double z = dz + dirZ * SIDE_OFFSET;

			// 构造显示文本并按字符数换行——世界空间字幕不宜太长，过长的文本会随实体移动
			// 抖动且遮挡画面，因此每行限 15 字符、最多 3 行，超出部分截断加省略号
			String line = subtitle.speakerName() + ": " + subtitle.translatedText();
			List<String> lines = wrapForWorld(line);

			MatrixStack matrices = context.matrixStack();
			if (matrices == null) continue;

			matrices.push();
			matrices.translate(x, y, z);
			// Billboard：与摄像机旋转相反，使文字始终朝向玩家。
			matrices.multiply(camera.getRotation());
			matrices.scale(-scale, -scale, scale);

			// 垂直居中多行文本：第一行上移、最后一行下移，整体以锚点为中心。
			// TextRenderer 空间中 Y 增大 = 屏幕下方，配合 scale 的 Y 翻转后正 Y 对应屏幕下方。
			float totalHeight = (lines.size() - 1) * WORLD_LINE_HEIGHT;
			float startY = -totalHeight / 2.0f;

			for (int i = 0; i < lines.size(); i++) {
				String l = lines.get(i);
				float lineWidth = textRenderer.getWidth(l);
				float lineY = startY + i * WORLD_LINE_HEIGHT;
				textRenderer.draw(
						l,
						-lineWidth / 2.0f, lineY,
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
						BACKGROUND_COLOR,
						0xF000F0
				);
			}

			matrices.pop();
		}
	}

	/**
	 * 按字符数对世界空间字幕做换行和截断。
	 *
	 * 选用按字符数而非按像素宽度（TextRenderer.trimToWidth）截断的原因：世界空间字幕
	 * 在 0.025 缩放下像素宽度对最终视觉宽度的影响是非线性的，且 CJK 字符宽度是 ASCII
	 * 的两倍，按字符数截断能保证 CJK 和英文混合文本的行宽视觉上基本一致。代价是
	 * 英文单词可能被从中间截断，但世界空间字幕本就是短文本场景，可接受。
	 */
	private static List<String> wrapForWorld(String text) {
		List<String> lines = new ArrayList<>();
		if (text.length() <= MAX_CHARS_PER_LINE) {
			lines.add(text);
			return lines;
		}
		int start = 0;
		while (start < text.length() && lines.size() < MAX_LINES) {
			boolean isLastLine = lines.size() == MAX_LINES - 1;
			int remaining = text.length() - start;
			if (isLastLine && remaining > MAX_CHARS_PER_LINE) {
				// 已是最后一行且还有剩余文本，截断并加省略号
				int keep = Math.max(0, MAX_CHARS_PER_LINE - 3);
				lines.add(text.substring(start, start + keep) + "...");
				return lines;
			}
			int end = Math.min(start + MAX_CHARS_PER_LINE, text.length());
			lines.add(text.substring(start, end));
			start = end;
		}
		return lines;
	}

	/**
	 * 在客户端世界中查找指定 UUID 的实体。
	 *
	 * 当前仅查找 PlayerEntity，因为 MCCF 的 SpatialChatHandler 只拦截玩家聊天——
	 * 只有玩家发出的消息才会触发字幕流程，非玩家实体（如村民、怪物）不会说话。
	 * 如果未来支持 NPC 说话（例如通过 MythicMobs、Citizens 等模组让非玩家实体
	 * 发送字幕），这里需要扩展为 LivingEntity 或 Entity 查找。届时还需注意
	 * world.getPlayers() 是 O(玩家数) 的小集合遍历，而遍历所有实体需要改用
	 * world.iterateEntities() 并关注性能影响。
	 */
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
