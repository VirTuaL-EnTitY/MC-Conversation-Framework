package net.mccf.mod.spatial;

import net.mccf.mod.config.MCCFConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 判定"谁能听到" / "谁能看到"说话者，是整个空间化系统的地基。
 *
 * 三档结果对应设计文档的空间化字幕规则：
 * - VISIBLE:  在 subtitleVisibleRange 内 且 视线未被方块完全阻挡 -> 字幕悬浮在说话者头顶
 * - AUDIBLE:  在 hearingRange 内，但视线被阻挡或距离超过 visible range -> 字幕显示在屏幕下方
 * - NONE:     超出 hearingRange -> 完全收不到任何字幕/消息
 *
 * 射线检测使用方块碰撞形状判定（不含实体），足以模拟"隔墙听不清、看不见"的效果，
 * 同时避免因遮挡判定过于复杂而拖慢每次聊天消息的处理。
 */
public class HearingResolver {

	private final MCCFConfig config;

	public HearingResolver(MCCFConfig config) {
		this.config = config;
	}

	public enum HearingLevel {
		VISIBLE,
		AUDIBLE,
		NONE
	}

	public HearingLevel resolve(ServerPlayerEntity speaker, ServerPlayerEntity listener) {
		if (speaker.getWorld() != listener.getWorld()) {
			return HearingLevel.NONE;
		}

		double distance = speaker.getPos().distanceTo(listener.getPos());

		if (distance > config.hearingRange) {
			return HearingLevel.NONE;
		}

		boolean withinVisibleRange = distance <= config.subtitleVisibleRange;
		boolean hasLineOfSight = !config.enableOcclusionCheck || hasLineOfSight(speaker, listener);

		if (withinVisibleRange && hasLineOfSight) {
			return HearingLevel.VISIBLE;
		}
		// 在听力范围内，但要么距离超过可见范围、要么视线被挡 -> 只显示"闻其声不见其人"字幕。
		return HearingLevel.AUDIBLE;
	}

	/**
	 * 简单的方块射线遮挡检测：从说话者眼部位置向听者眼部位置发射一条射线，
	 * 若命中任何不透明方块（射线在到达目标前被 BLOCK 类型命中），视为被遮挡。
	 */
	private boolean hasLineOfSight(ServerPlayerEntity speaker, ServerPlayerEntity listener) {
		World world = speaker.getWorld();
		Vec3d from = speaker.getEyePos();
		Vec3d to = listener.getEyePos();

		RaycastContext context = new RaycastContext(
				from,
				to,
				RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE,
				speaker
		);

		HitResult result = world.raycast(context);
		if (result.getType() == HitResult.Type.BLOCK) {
			BlockHitResult blockHit = (BlockHitResult) result;
			// 命中点若比目标更远（几乎重合），视为未被真正阻挡（避免边界抖动误判）。
			double hitDistanceSq = blockHit.getPos().squaredDistanceTo(from);
			double targetDistanceSq = to.squaredDistanceTo(from);
			return hitDistanceSq >= targetDistanceSq - 0.1;
		}
		return true;
	}

	/**
	 * 在给定的候选玩家列表中，找出所有能"听到"说话者的听众（AUDIBLE 或 VISIBLE），
	 * 并按等级分类返回，供 SpatialChatHandler 决定字幕展示方式与是否加入 Conversation。
	 */
	public HearingResult resolveAll(ServerPlayerEntity speaker, List<ServerPlayerEntity> candidates) {
		List<ServerPlayerEntity> visible = new ArrayList<>();
		List<ServerPlayerEntity> audibleOnly = new ArrayList<>();

		for (ServerPlayerEntity candidate : candidates) {
			if (candidate.getUuid().equals(speaker.getUuid())) continue;
			HearingLevel level = resolve(speaker, candidate);
			switch (level) {
				case VISIBLE -> visible.add(candidate);
				case AUDIBLE -> audibleOnly.add(candidate);
				case NONE -> { /* 收不到任何内容，忽略 */ }
			}
		}

		return new HearingResult(visible, audibleOnly);
	}

	public record HearingResult(List<ServerPlayerEntity> visible, List<ServerPlayerEntity> audibleOnly) {
		public List<ServerPlayerEntity> allListeners() {
			List<ServerPlayerEntity> all = new ArrayList<>(visible);
			all.addAll(audibleOnly);
			return all;
		}
	}
}
