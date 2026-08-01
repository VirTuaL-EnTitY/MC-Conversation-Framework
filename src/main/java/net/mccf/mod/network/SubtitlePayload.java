package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.mccf.mod.MCCF;

import java.util.UUID;

/**
 * 服务端 -> 客户端 的字幕数据包。
 *
 * 每当一名玩家说的话被判定为"某个监听者能收到"，服务端就为该监听者
 * 单独构造一份 SubtitlePayload 并发送——注意这是"点对点"而非广播，
 * 保证了"信息只会传播到真正能够接收到它的人"（不同玩家收到的字幕
 * 内容、显示方式都可能不同，因为各自的距离/遮挡/目标语言都不同）。
 *
 * @param speakerId      说话者 UUID（客户端用于定位其头顶悬浮字幕的位置）
 * @param speakerName    说话者显示名（用于屏幕下方字幕的前缀，如 "Alice: ..."）
 * @param originalText   原文
 * @param translatedText 译文（若客户端语言与说话者相同，等于原文）
 * @param displayMode    "VISIBLE" 悬浮在说话者头顶 / "AUDIBLE" 显示在物品栏上方
 * @param conversationId 这句话所属的 Conversation（服务端 {@link net.mccf.mod.context.Conversation}
 *                       的 id）。客户端聊天历史记录界面据此把消息分组展示（同一个
 *                       Conversation 的消息归为同一个对话块，参见
 *                       {@code ChatHistoryEntry#conversationId}）。玩家自己回显
 *                       （{@code SpatialChatHandler#dispatchSelfEcho}）时同样携带
 *                       这个 id——说话者自己发的消息当然也属于这个 Conversation。
 * @param sourceLang     说话者的语言代码（{@code PlayerLanguageRegistry.getLanguage(speakerId)}
 *                       在服务端算出的值）。客户端聊天历史记录界面用它和 targetLang
 *                       一起渲染语言标签（例如"中文→英语"），单纯依赖文字内容本身
 *                       猜不出这两种语言分别是什么，必须由服务端一并告知。
 * @param targetLang     接收这条消息的听众自己的语言代码（即 translatedText 翻译成
 *                       的目标语言）。自己回显（SELF）时 sourceLang == targetLang
 *                       （因为回显不翻译，原文=译文，语言也相同）。
 */
public record SubtitlePayload(
		UUID speakerId,
		String speakerName,
		String originalText,
		String translatedText,
		String displayMode,
		UUID conversationId,
		String sourceLang,
		String targetLang
) implements CustomPayload {

	/**
	 * originalText / translatedText 的最大字符数。
	 *
	 * 为什么要限制：PacketCodecs.STRING 本身不限制长度，恶意客户端可以构造
	 * 超大网络包（几十 MB 的字符串），导致服务端在反序列化时分配大量内存
	 * 甚至 OOM 崩溃。4096 字符足以覆盖任何正常的聊天消息（原版 Minecraft
	 * 聊天上限也才 256 字符，这里给翻译后的长文本留足余量）。
	 */
	private static final int MAX_TEXT_LENGTH = 4096;

	public SubtitlePayload {
		if (originalText != null && originalText.length() > MAX_TEXT_LENGTH) {
			throw new IllegalArgumentException(
					"SubtitlePayload originalText exceeds max length " + MAX_TEXT_LENGTH +
					" (was " + originalText.length() + ")");
		}
		if (translatedText != null && translatedText.length() > MAX_TEXT_LENGTH) {
			throw new IllegalArgumentException(
					"SubtitlePayload translatedText exceeds max length " + MAX_TEXT_LENGTH +
					" (was " + translatedText.length() + ")");
		}
	}

	public static final CustomPayload.Id<SubtitlePayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "subtitle"));

	// 手动实现 PacketCodec（encode/decode），而不是用 PacketCodec.tuple(...) 辅助方法：
	// 项目里此前所有 payload 最多只用到 5 个字段的 tuple 重载，字段数一多是否有
	// 对应重载没有先例可以核对，手写 encode/decode 完全不依赖这个不确定因素，
	// 只用最基础、确定存在的 RegistryByteBuf 读写方法和 PacketCodec.of(...) 两参数
	// 版本（encoder + decoder 各自一个 lambda，这是 PacketCodec 接口最原始的构造
	// 方式，不涉及任何"重载数量"的不确定性）。
	public static final PacketCodec<RegistryByteBuf, SubtitlePayload> CODEC = PacketCodec.of(
			(payload, buf) -> {
				Uuids.PACKET_CODEC.encode(buf, payload.speakerId());
				PacketCodecs.STRING.encode(buf, payload.speakerName());
				PacketCodecs.STRING.encode(buf, payload.originalText());
				PacketCodecs.STRING.encode(buf, payload.translatedText());
				PacketCodecs.STRING.encode(buf, payload.displayMode());
				Uuids.PACKET_CODEC.encode(buf, payload.conversationId());
				PacketCodecs.STRING.encode(buf, payload.sourceLang());
				PacketCodecs.STRING.encode(buf, payload.targetLang());
			},
			buf -> new SubtitlePayload(
					Uuids.PACKET_CODEC.decode(buf),
					PacketCodecs.STRING.decode(buf),
					PacketCodecs.STRING.decode(buf),
					PacketCodecs.STRING.decode(buf),
					PacketCodecs.STRING.decode(buf),
					Uuids.PACKET_CODEC.decode(buf),
					PacketCodecs.STRING.decode(buf),
					PacketCodecs.STRING.decode(buf)
			)
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
