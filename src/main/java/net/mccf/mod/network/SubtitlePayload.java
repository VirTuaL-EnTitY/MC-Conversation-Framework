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
 */
public record SubtitlePayload(
		UUID speakerId,
		String speakerName,
		String originalText,
		String translatedText,
		String displayMode
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

	public static final PacketCodec<RegistryByteBuf, SubtitlePayload> CODEC = PacketCodec.tuple(
			Uuids.PACKET_CODEC, SubtitlePayload::speakerId,
			PacketCodecs.STRING, SubtitlePayload::speakerName,
			PacketCodecs.STRING, SubtitlePayload::originalText,
			PacketCodecs.STRING, SubtitlePayload::translatedText,
			PacketCodecs.STRING, SubtitlePayload::displayMode,
			SubtitlePayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
