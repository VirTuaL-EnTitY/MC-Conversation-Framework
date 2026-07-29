package net.mccf.mod.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.mccf.mod.MCCF;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * 日志导出：把 MCCF 相关的日志内容整理到一个独立文件，方便玩家/管理员
 * 反馈问题时打包发送，不用自己去 logs 目录里翻找、也不用发送整个
 * latest.log（可能包含其他模组的无关内容或过大）。
 *
 * 导出两部分内容，玩家可自由选择：
 * 1. "MCCF 专属日志"：从 latest.log 里过滤出所有带 "[MCCF]" 前缀的行
 *    （翻译请求/错误、配置变更记录等，见 MCCF.LOGGER 的调用约定）。
 * 2. "完整游戏日志"：直接复制一份 latest.log。
 *
 * 输出文件统一放在 <游戏目录>/mccf-exports/ 下，带时间戳，避免互相覆盖。
 */
public final class LogExporter {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private LogExporter() {}

	public enum ExportMode { MCCF_ONLY, FULL_LOG, BOTH }

	/**
	 * 执行导出。
	 *
	 * @return 导出成功时返回结果描述（包含文件路径）；失败时返回错误描述，
	 *         调用方（Screen）负责展示给玩家，不抛异常，避免导出失败也把
	 *         界面搞崩溃。
	 */
	public static String export(ExportMode mode) {
		try {
			Path gameDir = FabricLoader.getInstance().getGameDir();
			Path latestLog = gameDir.resolve("logs").resolve("latest.log");
			Path exportDir = gameDir.resolve("mccf-exports");
			Files.createDirectories(exportDir);

			String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
			StringBuilder resultMessage = new StringBuilder();

			if (!Files.exists(latestLog)) {
				return "Export failed: latest.log not found at " + latestLog;
			}

			if (mode == ExportMode.MCCF_ONLY || mode == ExportMode.BOTH) {
				Path mccfOnlyOutput = exportDir.resolve("mccf-log-" + timestamp + ".txt");
				// 流式读取 + 过滤写入，而不是 Files.readAllLines 一次性读入内存。
				// 为什么改流式：有些玩家长期不重启游戏，latest.log 可能积累到几百 MB，
				// readAllLines 会把整个文件读成 List<String> 常驻堆内存，极端情况下 OOM。
				// Files.lines() 返回的 Stream 是惰性读取，内存占用恒定（只缓冲一行），
				// 配合 BufferedWriter 直接逐行写出，对任意大小的日志都能稳定工作。
				//
				// 为什么用 String.contains 而不是正则 .*\\[MCCF\\].*：这里只是固定
				// 子串匹配，contains 是 JDK 内部优化的原生方法，省去了 Pattern 编译
				// 和正则状态机的开销，在百万行级别日志上性能差距明显。
				try (Stream<String> lines = Files.lines(latestLog, StandardCharsets.UTF_8);
					 BufferedWriter writer = Files.newBufferedWriter(mccfOnlyOutput, StandardCharsets.UTF_8,
							 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
					lines.filter(line -> line.contains("[MCCF]"))
							.forEach(line -> {
								try {
									writer.write(line);
									writer.write('\n');
								} catch (IOException e) {
									// forEach 的 lambda 不能直接抛 IOException，这里包一层。
									// 实际写入失败概率极低（磁盘满才会触发），交给外层 catch 兜底不现实，
									// 只能记录日志后继续——部分行丢失也比整个导出崩溃好。
									MCCF.LOGGER.error("[MCCF] Failed to write a log line during export.", e);
								}
							});
				}
				resultMessage.append("MCCF log: ").append(mccfOnlyOutput.getFileName());
			}

			if (mode == ExportMode.FULL_LOG || mode == ExportMode.BOTH) {
				Path fullLogOutput = exportDir.resolve("full-log-" + timestamp + ".txt");
				Files.copy(latestLog, fullLogOutput, StandardCopyOption.REPLACE_EXISTING);
				if (!resultMessage.isEmpty()) resultMessage.append(" | ");
				resultMessage.append("Full log: ").append(fullLogOutput.getFileName());
			}

			MCCF.LOGGER.info("[MCCF] Log exported to {}", exportDir);
			return "Exported to mccf-exports/ (" + resultMessage + ")";
		} catch (IOException e) {
			MCCF.LOGGER.error("[MCCF] Log export failed.", e);
			return "Export failed: " + e.getMessage();
		}
	}
}
