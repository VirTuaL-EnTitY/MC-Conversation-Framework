package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

/**
 * 客户端 -> 服务端：声明本客户端当前的"模式偏好"。
 *
 * 职责边界——只管"客户端把模式偏好告诉服务端"，不管服务端是否采纳：
 * 服务端收到后决定是否为该玩家跳过空间化处理，那是服务端 {@link net.mccf.mod.spatial.SpatialChatHandler}
 * 与 {@link net.mccf.mod.spatial.ClientOnlyModeRegistry} 的职责，本类不掺和。
 *
 * 为什么需要这个包：装了 MCCF 的服务端默认会拦截原版全服聊天广播，改发
 * {@link SubtitlePayload}。但玩家可能明确想要"纯客户端本地翻译"（见
 * {@link net.mccf.mod.client.mode.ClientOnlyModeManager.Override#FORCE_CLIENT_ONLY}），
 * 此时服务端不该替他做空间化/字幕——否则强制客户端模式形同虚设。客户端在
 * 模式切换或加入服务器时主动上报当前偏好，服务端据此决定是否拦截该玩家的聊天。
 *
 * 退回方案：旧服务端不认识这个包，{@code ClientPlayNetworking.canSend(ID)} 会返回
 * false，客户端据此跳过发送（见 ClientOnlyModeManager.sendModePreference），
 * 不会产生报错或警告日志；此时客户端改从 SubtitlePayload 里提取文本走本地翻译。
 *
 * @param clientOnlyMode true 表示客户端要求纯客户端模式（服务端别拦截其聊天、别发字幕）
 */
public record ModePreferencePayload(boolean clientOnlyMode) implements CustomPayload {

	public static final CustomPayload.Id<ModePreferencePayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "mode_preference"));

	public static final PacketCodec<RegistryByteBuf, ModePreferencePayload> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOL, ModePreferencePayload::clientOnlyMode,
			ModePreferencePayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
