package net.mccf.mod.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.mccf.mod.MCCF;

import java.util.List;
import java.util.UUID;

/**
 * 服务端 -> 客户端：同步某个 Conversation 当前的完整参与者名单（UUID + 显示名）。
 *
 * 为什么不把参与者名单直接塞进 {@link SubtitlePayload}：那个包的字段数已经
 * 用满（{@code PacketCodec} 手写 encode/decode 的做法本身没有硬性字段数限制，
 * 但每条消息都携带完整参与者名单会造成明显的带宽浪费——一个活跃的 5 人对话组
 * 每人每次发言都要重复广播全部 5 个人的 UUID+名字，而这份名单在多数时候
 * 相邻两次发言之间根本没有变化）。改为独立的包，只在参与者集合真正发生变化
 * （有人加入/离开这个 Conversation）时才发送一次，收件人是"变化后仍在这个
 * Conversation 里的所有参与者"。
 *
 * 客户端收到后更新本地"conversationId -> 参与者名单"的映射（见客户端
 * ChatHistoryManager 相关逻辑），聊天历史记录界面据此渲染大标题
 * （"LimAimo、test、Alex 的对话"）和"XX 加入了对话"提示。
 *
 * @param conversationId 对应服务端 {@link net.mccf.mod.context.Conversation} 的 id
 * @param participantIds   当前参与者 UUID 列表，顺序即为服务端 Set 的遍历顺序
 *                          （{@code LinkedHashSet}，插入顺序——也就是"谁先加入对话
 *                          的顺序"，客户端渲染大标题时按这个顺序列名字，符合直觉）
 * @param participantNames 与 participantIds 一一对应的显示名列表，长度必须相同
 */
public record ConversationRosterPayload(
		UUID conversationId,
		List<UUID> participantIds,
		List<String> participantNames
) implements CustomPayload {

	/** 单个 Conversation 参与者数量上限，防御性限制，避免恶意/异常数据构造超大包。 */
	private static final int MAX_PARTICIPANTS = 128;

	public ConversationRosterPayload {
		if (participantIds.size() != participantNames.size()) {
			throw new IllegalArgumentException(
					"ConversationRosterPayload participantIds/participantNames size mismatch: " +
					participantIds.size() + " vs " + participantNames.size());
		}
		if (participantIds.size() > MAX_PARTICIPANTS) {
			throw new IllegalArgumentException(
					"ConversationRosterPayload participant count exceeds max " + MAX_PARTICIPANTS +
					" (was " + participantIds.size() + ")");
		}
	}

	public static final CustomPayload.Id<ConversationRosterPayload> ID =
			new CustomPayload.Id<>(Identifier.of(MCCF.MOD_ID, "conversation_roster"));

	// 同 SubtitlePayload：手写 encode/decode 而不是 PacketCodec.tuple(...)，避免
	// 依赖不确定的重载数量。List 字段的编解码同样不借助 PacketCodecs 里可能存在
	// 也可能不存在的集合包装工具方法（没有本地反编译源码可核对具体方法名），
	// 改为最基础、确定存在的手写循环：先写入元素个数（varInt，Minecraft 协议里
	// 长期稳定使用的变长整数编码，RegistryByteBuf 作为 Netty ByteBuf 的扩展
	// 具备 writeVarInt/readVarInt 是有把握的），再逐个 encode/decode 每个元素。
	public static final PacketCodec<RegistryByteBuf, ConversationRosterPayload> CODEC = PacketCodec.of(
			(payload, buf) -> {
				Uuids.PACKET_CODEC.encode(buf, payload.conversationId());

				List<UUID> ids = payload.participantIds();
				buf.writeVarInt(ids.size());
				for (UUID id : ids) {
					Uuids.PACKET_CODEC.encode(buf, id);
				}

				List<String> names = payload.participantNames();
				buf.writeVarInt(names.size());
				for (String name : names) {
					PacketCodecs.STRING.encode(buf, name);
				}
			},
			buf -> {
			UUID conversationId = Uuids.PACKET_CODEC.decode(buf);

			// 1.1.2 安全修复：readVarInt 返回值必须在上限校验**之前**使用——
			// 旧代码直接把 idCount 传给 new ArrayList<>(idCount)，恶意客户端
			// 发送 idCount=Integer.MAX_VALUE 会预分配约 16GB 内存直接 OOM 服务端。
			// 上限校验放在 ArrayList 构造之前，先 clamp 到 MAX_PARTICIPANTS 再预分配容量。
			// nameCount 同理处理。校验失败时抛 IllegalArgumentException 会被 Fabric
			// 的 Payload 反序列化层捕获并丢弃该包（不会让服务端崩溃），等价于"无视恶意包"。
			int idCount = buf.readVarInt();
			if (idCount < 0 || idCount > MAX_PARTICIPANTS) {
				throw new IllegalArgumentException(
						"ConversationRosterPayload idCount out of range [0, " + MAX_PARTICIPANTS +
						"]: " + idCount);
			}
			List<UUID> ids = new java.util.ArrayList<>(idCount);
			for (int i = 0; i < idCount; i++) {
				ids.add(Uuids.PACKET_CODEC.decode(buf));
			}

			int nameCount = buf.readVarInt();
			if (nameCount < 0 || nameCount > MAX_PARTICIPANTS) {
				throw new IllegalArgumentException(
						"ConversationRosterPayload nameCount out of range [0, " + MAX_PARTICIPANTS +
						"]: " + nameCount);
			}
			List<String> names = new java.util.ArrayList<>(nameCount);
			for (int i = 0; i < nameCount; i++) {
				names.add(PacketCodecs.STRING.decode(buf));
			}

			return new ConversationRosterPayload(conversationId, ids, names);
		}
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
