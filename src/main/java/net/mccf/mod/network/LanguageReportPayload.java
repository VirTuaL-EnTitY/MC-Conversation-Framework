package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

/**
 * 客户端 -> 服务端：玩家加入时自动上报当前 Minecraft 客户端语言设置
 * （对应设计确认："客户端自动检测 Minecraft 语言设置"）。
 *
 * 服务端收到后写入 {@link net.mccf.mod.spatial.PlayerLanguageRegistry}，
 * 后续该玩家作为"听众"时，翻译服务会以此作为目标语言。
 *
 * @param languageCode Minecraft locale 格式，例如 "zh_cn"、"en_us"、"ja_jp"
 */
public record LanguageReportPayload(String languageCode) implements CustomPayload {

	/**
	 * languageCode 字段的最大字符数。
	 *
	 * Minecraft locale 形如 "zh_cn"、"en_us"，最长不超过 10 字符（语言-国家两段）。
	 * 这里设 64 是给未来可能扩展的 BCP 47 风格 locale（如 "zh-Hans-CN"）留余量，
	 * 同时作为防御性上限——避免恶意客户端发数 MB 字符串进入 PlayerLanguageRegistry
	 * 的 Map 占用内存。任何超出此长度的值都极不可能是合法 locale，应直接拒绝。
	 */
	private static final int MAX_LANGUAGE_LENGTH = 64;

	public LanguageReportPayload {
		if (languageCode != null && languageCode.length() > MAX_LANGUAGE_LENGTH) {
			throw new IllegalArgumentException(
					"LanguageReportPayload languageCode exceeds max length " + MAX_LANGUAGE_LENGTH +
					" (was " + languageCode.length() + ")");
		}
	}

	public static final CustomPayload.Id<LanguageReportPayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "language_report"));

	public static final PacketCodec<RegistryByteBuf, LanguageReportPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, LanguageReportPayload::languageCode,
			LanguageReportPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
