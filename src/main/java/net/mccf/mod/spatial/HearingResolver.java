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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

	/**
	 * 同一说话者 -> 同一听众的射线判定结果短时缓存。
	 *
	 * 为什么用嵌套 Map（speaker -> listener -> result）而不是单个 {@code Map<UUID, CachedResult>}：
	 * 缓存键本质上是(说话者, 听众)这个二元组——单 UUID 既可能是说话者也可能是听众，
	 * 没法区分 (A 说给 B) 和 (B 说给 A)，用嵌套结构能让两层 key 的语义各自明确。
	 * 外层 speaker 是"每次聊天都在变的活跃维度"，内层 listener 是"对同一说话者复用"的维度。
	 */
	private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, CachedResult>> HEARING_CACHE = new ConcurrentHashMap<>();

	/** 缓存有效期：500ms。为什么是 500ms 见 {@link #resolve} 的注释。 */
	private static final long CACHE_TTL_MILLIS = 500L;

	/** 一条缓存的判定结果：等级 + 过期时刻（绝对毫秒时间戳）。 */
	private record CachedResult(HearingLevel mode, long expiresAtMillis) {}

	public HearingLevel resolve(ServerPlayerEntity speaker, ServerPlayerEntity listener) {
		// 跨维度永远 NONE，且不参与缓存——维度切换是离散的传送事件，不像同维度内位置漂移，
		// 缓存它反而会让"刚传送到下界还能听到主世界对话"这种荒谬结果存活一个 TTL 窗口。
		// 把世界判定放在缓存查询之前，保证跨维度永远是即时正确判断。
		if (speaker.getWorld() != listener.getWorld()) {
			return HearingLevel.NONE;
		}

		// S5 短时缓存：同说话者 -> 同听众 500ms 内复用上次的射线判定结果。
		// 为什么缓存 500ms：太短（如 50ms）缓存命中率几乎为零，等于没缓存；
		// 太长（如 5s）玩家移动后判定不更新，会出现"人已经走远还能听到"或"已经绕到墙后还能看见"
		// 的 bug。500ms 是一个保守折中——玩家在该窗口内通常还没移动到能显著改变遮挡关系的程度，
		// 同时又足以吃下"连续刷屏式发言"这种最需要缓存的高频场景。
		UUID speakerId = speaker.getUuid();
		UUID listenerId = listener.getUuid();
		ConcurrentHashMap<UUID, CachedResult> perSpeaker = HEARING_CACHE.get(speakerId);
		if (perSpeaker != null) {
			CachedResult cached = perSpeaker.get(listenerId);
			if (cached != null) {
				if (System.currentTimeMillis() < cached.expiresAtMillis()) {
					return cached.mode();
				}
				// 过期了，惰性移除——不做主动全局扫描，因为 TTL 很短、活跃玩家会被新结果覆盖，
				// 离线玩家残留条目内存占用可忽略（最多 players^2 条），不值得为此单独起一个清理任务。
				perSpeaker.remove(listenerId);
			}
		}

		double distance = speaker.getPos().distanceTo(listener.getPos());

		HearingLevel result;
		if (distance > config.hearingRange) {
			result = HearingLevel.NONE;
		} else {
			boolean withinVisibleRange = distance <= config.subtitleVisibleRange;
			boolean hasLineOfSight = !config.enableOcclusionCheck || hasLineOfSight(speaker, listener);

			if (withinVisibleRange && hasLineOfSight) {
				result = HearingLevel.VISIBLE;
			} else {
				// 在听力范围内，但要么距离超过可见范围、要么视线被挡 -> 只显示"闻其声不见其人"字幕。
				result = HearingLevel.AUDIBLE;
			}
		}

		writeCache(speakerId, listenerId, result);
		return result;
	}

	private static void writeCache(UUID speakerId, UUID listenerId, HearingLevel mode) {
		HEARING_CACHE.computeIfAbsent(speakerId, k -> new ConcurrentHashMap<>())
				.put(listenerId, new CachedResult(mode, System.currentTimeMillis() + CACHE_TTL_MILLIS));
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

		// S5 距离平方粗筛：先用 O(1) 的平方距离判定排除掉明显超出听力范围的远距离玩家，
		// 只对粗筛通过的候选者才进入 resolve()（resolve 内部才会触发射线检测这个 O(n) 的方块遍历）。
		// 为什么先用距离粗筛而不是直接 resolve：射线检测要从说话者眼部一路追方块追到听众眼部，
		// 距离越远追的方块越多越贵；而大部分候选玩家（同一服务器但隔得很远的）根本不在听力范围内，
		// 用一次平方比较就能排除掉，省掉绝大多数 raycast 调用。用平方距离而不是真距离是为了
		// 避开 Math.sqrt（虽然现代 CPU 上 sqrt 已经不慢，但少算一次总是没坏处，也和 MC 自己
		// 内部 squaredDistanceTo 的惯用写法保持一致）。
		double hearingRangeSq = config.hearingRange * config.hearingRange;

		for (ServerPlayerEntity candidate : candidates) {
			if (candidate.getUuid().equals(speaker.getUuid())) continue;

			double distSq = speaker.squaredDistanceTo(candidate);
			if (distSq > hearingRangeSq) {
				// 远到不可能听见，连 resolve 都不用进——后续缓存也不会为这类玩家建条目，
				// 因为 500ms 内他们大概率还是听不到，建条目反而是浪费内存。
				continue;
			}

			HearingLevel level = resolve(speaker, candidate);
			switch (level) {
				case VISIBLE -> visible.add(candidate);
				case AUDIBLE -> audibleOnly.add(candidate);
				case NONE -> { /* 粗筛通过但 resolve 仍判 NONE 的边缘情况（理论上不该发生），忽略 */ }
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
