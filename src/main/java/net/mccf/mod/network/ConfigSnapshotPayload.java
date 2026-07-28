package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.mccf.mod.MCCF;

/**
 * 服务端 -> 客户端：配置快照，用于填充配置界面。
 *
 * 整个快照打包为一个 JSON 字符串（而不是拆成多个 tuple 字段），原因是
 * Provider 数量后续可能增加、每个 Provider 又有多个子字段，用固定数量的
 * tuple 字段编码容易在字段数超出 codec 支持上限时出问题、也不便扩展。
 * JSON 字符串则可以自由增删字段而不影响网络协议本身。
 *
 * JSON 结构（由 {@link net.mccf.mod.command.ConfigSyncHandler} 负责序列化/反序列化）：
 * {
 *   "canEdit": true,
 *   "activeProvider": "openai",
 *   "providers": {
 *     "openai": { "apiKey": "sk-...", "model": "gpt-4o-mini", "host": "" },
 *     "ollama": { "apiKey": "", "model": "llama3.2", "host": "http://localhost:11434" },
 *     ...
 *   }
 * }
 *
 * 当 canEdit 为 false（接收方不是 op）时，服务端会把所有 apiKey 字段
 * 替换为空字符串，避免向非管理员玩家泄露服务器配置的敏感信息。
 */
public record ConfigSnapshotPayload(String json) implements CustomPayload {

	public static final CustomPayload.Id<ConfigSnapshotPayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "config_snapshot"));

	public static final PacketCodec<RegistryByteBuf, ConfigSnapshotPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, ConfigSnapshotPayload::json,
			ConfigSnapshotPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
