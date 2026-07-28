package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

/**
 * 客户端 -> 服务端：请求当前 Provider 配置快照，用于打开配置界面时填充数据。
 *
 * 没有真正需要传输的数据，但为避免依赖不同 Minecraft 版本间可能变动的
 * "零字段 payload" 编解码写法，这里用一个恒定的哑字段（true）走
 * 已经在本项目其他地方验证过的 {@code PacketCodec.tuple} 模式，
 * 保证编译期行为可预测。
 */
public record RequestConfigPayload(boolean marker) implements CustomPayload {

	public static final CustomPayload.Id<RequestConfigPayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "request_config"));

	public static final PacketCodec<RegistryByteBuf, RequestConfigPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOL, RequestConfigPayload::marker,
			RequestConfigPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
