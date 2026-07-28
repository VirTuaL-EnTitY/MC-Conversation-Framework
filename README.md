# MC Conversation Framework (MCCF)

面向 Minecraft 多人服务器的沉浸式跨语言交流基础设施。核心链路：
文字聊天拦截 → 空间化听觉判定（距离 + 射线遮挡）→ 动态对话上下文（Conversation）
→ 可插拔翻译 Provider（OpenAI/Claude/Gemini/DeepL/Kimi/DeepSeek/Ollama）
→ 客户端空间化字幕（悬浮头顶 / 物品栏上方）→ 游戏内配置界面（ModMenu 集成 +
按键呼出）。

目标版本：**Minecraft 1.21.1 · Yarn Mappings 1.21.1+build.3 · Fabric Loader 0.15.11 ·
Fabric API 0.116.15+1.21.1 · Fabric Loom 1.7.3 · Gradle 8.8 · Java 21**

> **为什么固定在 1.21.1？** 1.21.1 是 1.21.x 系列的早期稳定版本（2024-08
> 发布），Fabric API 的 `WorldRenderEvents`——本项目用它实现"字幕悬浮在
> 说话者附近"的效果——在该版本上工作正常。后续 1.21.9/1.21.10 的移植
> 过程中，因为 Minecraft 原版渲染管线发生了根本性重构，`WorldRenderEvents`
> 被官方**整体移除**，目前（截至本文写作时）还没有稳定的替代方案。1.21.1
> 既能保证"贴近说话人显示字幕"这个核心体验能够实现，又拥有最稳定的
> 1.21.x 模组生态兼容性（大量 1.21.x 模组以 1.21.1 为基线版本）。

> **关于 mappings 的说明**：本项目使用 **Yarn**（不是 Mojang 官方映射）。
> 如果你看到网上教程用 `net.minecraft.world.entity.Entity` 这类命名，
> 那是 NeoForge / Mojang mapping 风格，和本项目用的 Yarn 命名
> （`net.minecraft.entity.Entity`）是两套完全不同的体系，不要混用。

---

## 一、在你自己电脑上编译

### 1. 前置要求
- **JDK 21**（推荐 Temurin/Adoptium 或 Microsoft Build of OpenJDK）
- **Gradle 8.8**（本项目用的 Fabric Loom 1.7.3 实测要求 Gradle ≥ 8.6，
  用 8.8 是经过验证的稳定版本；wrapper 已锁定 8.8，正常情况下不需要
  你本机单独装 Gradle，见下）
- 稳定的网络连接（首次构建需要从 Fabric/Maven/Mojang 仓库下载依赖，几百 MB）

### 2. 关于 Gradle Wrapper
本项目**没有附带** `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` ——
这几个文件里的 `gradle-wrapper.jar` 是二进制文件，我在无网络的沙盒环境里
无法可靠生成一份真实可用的（伪造内容可能导致你本地校验失败或下载损坏的
Gradle）。`gradle/wrapper/gradle-wrapper.properties` 已经配置好指向
Gradle 8.8，你只需要在本地生成对应的 wrapper 脚本：

```bash
# 前提：本机已安装任意版本的 Gradle（哪怕版本不同也没关系，wrapper 任务
# 只是根据已有的 gradle-wrapper.properties 生成 gradlew/gradlew.bat）
gradle wrapper

# 之后就可以用 ./gradlew 了，它会自动下载并使用 8.8
./gradlew build
```

如果你本机完全没装过 Gradle，也可以直接下载 Gradle 8.8 一次性用：
https://gradle.org/releases/ → 找 8.8 → 解压后用它的 `bin/gradle` 执行
上面的 `gradle wrapper` 命令。

生成 wrapper 后，以后就可以用 `./gradlew build`（Windows 用 `gradlew.bat build`）了。

### 3. 构建
```bash
cd MC-Conversation-Framework-1.21.1
gradle build          # 或 ./gradlew build（如果已生成 wrapper）
```
构建产物在 `build/libs/MCConversationFramework-0.2.0.jar`，把它丢进服务器和客户端的 `mods/` 文件夹即可
（服务器和客户端都要装，因为这是一个同时含服务端逻辑和客户端渲染的模组）。

同时确保服务器和客户端都装了对应版本的 **Fabric API**（`0.116.15+1.21.1` 或更高的
1.21.1 兼容版本），可以在 Modrinth / CurseForge 上下载。装了
[ModMenu](https://modrinth.com/mod/modmenu) 11.0.4（1.21.1 对应版本）的话还能获得
游戏内配置界面的图标入口，非必需。

### 4. IDE 推荐
用 IntelliJ IDEA 打开项目根目录，Gradle 插件会自动识别。首次导入较慢（下载
Minecraft 反混淆源码 + 依赖），耐心等待即可。跑 `genSources` 后能看到带注释的
Minecraft 源码，便于后续开发调试。

---

## 二、当前功能范围

- ✅ 拦截原版聊天，替换为空间化点对点分发
- ✅ 距离 + 射线遮挡判定"谁能听到/看到"（`HearingResolver`）
- ✅ 动态 Conversation 合并/拆分/超时释放（`ConversationManager`）
- ✅ 可插拔翻译 Provider 架构，内置 **7 个真实 Provider** + Mock：
  OpenAI、Claude (Anthropic)、Gemini、DeepL、Kimi (Moonshot AI)、
  DeepSeek、Ollama（本地）
- ✅ **游戏内配置界面**：ModMenu 集成 + 独立按键呼出，可视化切换 Provider、
  填写 API Key / 模型名 / API Endpoint，仅 op 可编辑，普通玩家只读查看
- ✅ 世界词典（专有名词占位替换，保证跨 Provider 翻译一致性）
- ✅ 客户端自动上报 Minecraft 语言设置作为目标语言
- ✅ 双模式字幕：VISIBLE（悬浮说话者头顶）/ AUDIBLE（物品栏上方，多人堆叠布局）
- ✅ `/mccf` 管理命令（status / provider / dictionary / reload）
- ✅ API Endpoint 可自定义（接自建反代 / 兼容网关）+ 一键"恢复默认"
- ✅ 配置界面一键拉取 Provider 可用模型列表
- ✅ 配置界面一键导出日志（`logs/latest.log` 中 MCCF 相关行 + 完整日志）
- ✅ 配置界面多语言（`Text.translatable()`，跟随客户端语言，已提供简体中文翻译）
- ✅ **纯客户端模式**：服务器没装 MCCF 时自动降级为"仅本地翻译聊天栏文字"
  （无空间判定、无字幕），也可手动强制切换，详见"三之二、纯客户端模式"
- ⏳ 语音识别（STT）—— 本版本以文字聊天为输入，语音识别是下一步扩展方向
  （建议对接 Simple Voice Chat 的音频 API 作为输入源，替换 SpatialChatHandler
  的触发源即可，其余流程不需要改动）

---

## 三、配置界面怎么用

**两个入口**：
1. 装了 [ModMenu](https://modrinth.com/mod/modmenu) 的话，Mods 列表里
   MCCF 旁边会出现齿轮图标，点击打开。
2. 不装 ModMenu 也能用——游戏内默认绑定了一个按键（默认未指定具体键位，
   在 `设置 → 控制 → 按键绑定 → MC Conversation Framework` 分类下可以
   自己绑定一个键，绑定后按键直接呼出配置界面）。

**权限规则**：只有服务器 op 能修改 Provider / API Key / 模型 / Endpoint，
提交后由服务端二次校验权限（不是仅凭客户端界面判断），非 op 玩家打开
界面能看到当前生效的是哪个 Provider，但所有输入框禁用、看不到真实
API Key。

**每个 Provider 需要填什么**：
- OpenAI / Claude / Gemini / Kimi / DeepSeek：填 API Key + 模型名（有默认值，
  通常不用改）+ 可选的自定义 API Endpoint（默认走各家官方地址）
- DeepL：只需要 API Key（免费版 Key 以 `:fx` 结尾，界面会自动识别走免费端点）
- Ollama：不需要 API Key（本地部署默认无鉴权），需要填 Endpoint（默认
  `http://localhost:11434`）和模型名（默认 `llama3.2`，换成你本地已拉取的模型名）
- Mock：调试用，无需任何配置

**一键获取模型**：点"获取模型"按钮会调用对应 Provider 的模型列表接口
（OpenAI/Kimi/DeepSeek/Claude 用 `GET /v1/models`，Gemini 用
`GET /v1beta/models`，Ollama 用 `GET /api/tags`），结果打印到聊天栏方便复制。
DeepL（固定引擎无模型概念）和 Mock 不支持，按钮自动置灰。可以用输入框里
**尚未保存**的 Key 直接测试，不需要先保存一次。

**导出日志**：点"导出日志"按钮，从 `logs/latest.log` 提取 MCCF 相关行 /
复制完整日志，两者都输出到 `<游戏目录>/mccf-exports/`，带时间戳文件名，纯本地操作。

保存后服务端会立即重建对应的 Provider 实例并生效，无需重启服务器。

---

## 三之二、纯客户端模式（服务器没装 MCCF 时）

如果你连的服务器没有安装 MCCF（比如公共服/别人的服），模组没法拦截聊天做
点对点分发、也没法做距离/遮挡这类需要服务端参与的判定——所有人还是会照常
收到原版广播的聊天。这种情况下 MCCF 会自动降级为**纯客户端模式**：不做
空间判定、不渲染悬浮/物品栏字幕，只是把收到的每一条聊天消息在本地翻译成
你自己客户端设置的语言，追加显示在聊天栏里（原文照常显示，译文是追加的
一行，前面带 `⇄` 标记），比如：
```
<Steve> こんにちは
⇄ Hello
```

**怎么进入这个模式**：
- **自动检测**（默认）：进入服务器时会检测对方是否声明了 MCCF 的网络通道，
  没检测到就自动切换，不需要你做任何操作。
- **手动强制**：主配置界面（按键呼出或 ModMenu）里点"纯客户端本地翻译设置"，
  进"运行模式"选择"强制纯客户端模式"——即使服务器装了 MCCF，也只用本地
  翻译；反过来选"强制服务器模式"可以在自动检测误判时手动纠正。这个选择
  保存在本地（`config/mccf/client-mode.json`），跟随你的客户端，不是针对
  某个服务器的设置。

**配置管理**：纯客户端模式用的翻译 Provider 配置是一份完全独立于服务端的
本地文件（`config/mccf/client-only-config.json`），任何人都能自由编辑，
不需要 op 权限——毕竟这份配置只影响你自己本地看到的翻译结果，不会分发给
任何其他玩家。填法和主配置界面一样（Provider + API Key + 模型名 +
Endpoint）。如果当前连的服务器恰好装了 MCCF，还可以点"从服务器同步"一键
把服务器正在用的 Provider/模型名/Endpoint 拷贝过来，省得重新选一遍——但
**不会**拷贝 API Key（即使你是 op、快照里带真实 Key 也不拷贝），密钥必须
你自己填。

**已知限制**：
- 没有对"是否已经是目标语言"做检测，每条消息都会真的发一次翻译请求
  （Mock Provider 除外，它本身会在同语言时直接透传，不消耗额度）。
- 不会给自己发的消息做二次翻译（服务器回显自己说的话时会跳过），但其他
  语言相同的消息目前没有去重/节流，人多的服务器可能会比较费 API 额度。
- 由于没有服务端参与，这只是"翻译 + 展示美化"，**不是**真正的信息隔离——
  所有人依然会收到原版广播的完整聊天，纯客户端模式改变的只是你自己客户端
  怎么显示它。

---

## 四、如何接入其他翻译 Provider（内置 7 个之外的）

打开 `net.mccf.mod.translation.provider.TranslationProvider` 接口，参照
`MockTranslationProvider` 写一个新的实现类，例如：

```java
public class MyTranslationProvider implements TranslationProvider {
    @Override public String getId() { return "myprovider"; }
    @Override public String getDisplayName() { return "My Provider"; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
        // 1. 用 java.net.http.HttpClient 异步调用目标 API
        //    （可参照 HttpProviderSupport 里的通用请求/超时/错误处理逻辑）
        // 2. 把 request.contextMessages() 作为对话历史一起传给模型，
        //    帮助它理解代词指代、术语一致性等
        // 3. 返回 CompletableFuture<TranslationResult>
    }
}
```

然后在 `ProviderFactory` 里注册对应的创建逻辑，并在 `ProviderDefaults` 里
补上默认的 endpoint / model 值（供"恢复默认"按钮使用）。

API Key 等敏感信息建议放在 `config/mccf/config.json` 之外单独的、不提交到版本库
的文件里（比如环境变量或 `secrets.json` 并加入 `.gitignore`）。

---

## 五、目录结构

```
src/main/java/net/mccf/mod/
├── MCCF.java                        模组入口（服务端）：注册网络包、事件监听、命令
├── config/
│   ├── MCCFConfig.java              服务端配置（距离阈值、超时时间、当前 Provider 等）
│   ├── ProviderConfig.java          单个 Provider 的配置项（apiKey / model / endpoint）
│   └── ProviderDefaults.java        每个 Provider 的默认 endpoint / model，"恢复默认"的数据源
├── context/
│   ├── Conversation.java            单个对话组：参与者 + 近期消息
│   └── ConversationManager.java     合并/拆分/超时释放
├── dictionary/WorldDictionary.java  世界词典 + 占位符替换逻辑
├── translation/
│   ├── TranslationService.java      词典 -> Provider -> 结果还原 的调度层
│   └── provider/
│       ├── TranslationProvider.java          可插拔接口
│       ├── ProviderFactory.java              根据配置创建对应 Provider 实例
│       ├── HttpProviderSupport.java          各 Provider 共用的 HTTP 请求/超时/错误处理
│       ├── MockTranslationProvider.java      调试用示例实现
│       ├── OpenAiTranslationProvider.java
│       ├── ClaudeTranslationProvider.java
│       ├── GeminiTranslationProvider.java
│       ├── DeepLTranslationProvider.java
│       ├── KimiTranslationProvider.java
│       ├── DeepSeekTranslationProvider.java
│       └── OllamaTranslationProvider.java
├── spatial/
│   ├── HearingResolver.java          距离 + 射线遮挡判定
│   ├── SpatialChatHandler.java       核心调度：拦截聊天 -> 判定 -> 翻译 -> 分发
│   └── PlayerLanguageRegistry.java   玩家语言运行时注册表
├── network/                          C2S/S2C 网络包定义（见下方说明）
└── command/
    ├── MCCFCommand.java              /mccf 管理命令（status / provider / dictionary / reload）
    └── ConfigSyncHandler.java        配置界面的服务端数据同步：MCCFConfig <-> JSON 快照，op 权限校验

src/client/java/net/mccf/mod/client/
├── MCCFClient.java                   客户端入口：注册渲染器、按键绑定、网络接收器
├── config/
│   ├── MCCFConfigScreen.java         配置界面主 Screen
│   ├── ModelSelectionScreen.java     "获取模型"结果的子列表 Screen
│   ├── MCCFModMenuIntegration.java   ModMenu 集成入口（仅 ModMenu 安装时加载）
│   ├── ClientConfigState.java        客户端本地配置状态单例（含未提交的编辑，来自服务端快照）
│   ├── ClientProviderConfig.java     客户端内存中单个 Provider 配置副本（服务端/本地两处复用）
│   ├── ClientOnlyTranslationConfig.java  纯客户端模式：玩家自己的本地翻译配置，持久化到
│   │                                      config/mccf/client-only-config.json，任何人可编辑
│   └── ClientOnlyConfigScreen.java   纯客户端模式的"本地翻译设置"子界面（从主界面按钮打开）
├── mode/ClientOnlyModeManager.java   判断当前是否该走纯客户端模式：自动检测 + 手动强制覆盖，
│                                      持久化到 config/mccf/client-mode.json
├── chat/ClientOnlyChatTranslator.java  纯客户端模式下的本地聊天翻译：监听收到的聊天消息，
│                                        异步翻译后追加显示，不做空间判定
├── subtitle/
│   ├── ActiveSubtitle.java           客户端内存态字幕数据
│   ├── SubtitleManager.java          接收、超时管理、多人去重排序
│   ├── WorldSubtitleRenderer.java    VISIBLE 模式：悬浮头顶（billboard 文字）
│   └── HotbarSubtitleRenderer.java   AUDIBLE 模式：物品栏上方堆叠显示
└── util/LogExporter.java             日志导出逻辑（提取 MCCF 相关行 + 完整日志复制）
```

**关于 `network/` 包**：目前实际被 `MCCF.java` / `MCCFClient.java` 注册使用的网络包是
`SubtitlePayload`、`LanguageReportPayload`、`RequestConfigPayload`、
`ConfigSnapshotPayload`、`UpdateConfigPayload`、`RequestModelsPayload`、
`ModelsResultPayload` 共 7 个。包内还有 `RequestModelListPayload` /
`ModelListResponsePayload` 两个类——审查代码时发现它们没有被任何地方注册或引用，
应该是"获取模型"功能早期迭代时留下的死代码（后来改用了
`RequestModelsPayload`/`ModelsResultPayload` 这条路径）。这两个文件目前**不影响
运行**，只是冗余，我没有主动删除，如果你确认不需要保留可以告诉我直接清掉。

---

## 六、配置文件（首次运行后自动生成）

`config/mccf/config.json`：
```json
{
  "subtitleVisibleRange": 32.0,
  "hearingRange": 48.0,
  "conversationRange": 48.0,
  "conversationIdleTimeoutSeconds": 120,
  "enableOcclusionCheck": true,
  "activeProvider": "mock",
  "showOriginalText": true
}
```

`config/mccf/dictionary.json`：世界词典，格式为
`{ "词条": { "语言代码": "该语言下的译文", ... }, ... }`，也可以用
`/mccf dictionary add <term> <lang> <translation>` 命令在游戏内添加。

---

## 七、已知限制 / 后续可扩展方向

- 语音识别未实现（见"二、当前功能范围"）。
- `HearingResolver` 目前只做"说话者→听众"单向射线检测一次，性能上对大量玩家
  同时说话的场景（比如几十人的活动服）没有做批量优化，如果后续遇到性能问题，
  可以考虑给听众按区块分桶、或限制每 tick 处理的聊天消息数。
- 字幕的显示时长按文本长度粗略估算，可以后续做成可配置项。
- Conversation 的合并策略目前是"简单并集"，没有处理"两个大对话组因为一个
  中间人同时能听到而被强行合并"这种边界情况的精细化处理（比如设置合并的
  最小重叠人数阈值），如果实测中出现不合理的合并，可以在
  `ConversationManager.recordUtterance` 里加更细的判定逻辑。
- 世界广播、NPC 对话、剧情事件等在设计文档中提到的"未来扩展"尚未实现，
  但整体架构（Provider 可插拔 + Conversation 上下文隔离）是为它们预留的。
- `network/` 包里有两个未注册使用的死代码类，见"五、目录结构"末尾说明。
- 当前 VISIBLE 模式的字幕位置是"说话者头顶上方一点"，不是贴在人物旁边。
  如果之后想做成基于屏幕投影坐标的贴身定位，World 渲染管线在新版本上不稳定，
  需要单独一轮谨慎处理，避免与其他改动混在一起难以排查。
- **纯客户端模式（`ClientOnlyChatTranslator`）用到的 `ClientReceiveMessageEvents.CHAT`
  事件签名**：此前 README 里标注为"凭印象写、未本地编译验证"的风险点，在
  1.21.1 上经本地编译验证**签名正确**（5 参数：`message, signedMessage, sender, params, receptionTimestamp`），
  该风险点已消除。`CyclingButtonWidget.setValue(...)`（在"从服务器同步"按钮里
  用来刷新下拉框显示）此前同样标注为"凭印象写"，也已在 1.21.1 上编译通过，
  运行时行为正常。

---

## 八、更新日志

### 2026-07-28　目标版本从 1.21.8 切换到 1.21.1
按项目目录命名（`MC-Conversation-Framework-1.21.1`）和模组生态兼容性需求，
把目标版本从 1.21.8 降到 1.21.1。1.21.1 是 1.21.x 系列的早期稳定版本，
拥有最广泛的模组生态兼容性（大量 1.21.x 模组以 1.21.1 为基线），
同时 `WorldRenderEvents`、`HudElementRegistry`、`KeyBinding` 字符串分类
等本项目依赖的 API 在 1.21.1 和 1.21.8 之间完全一致，源代码无需任何改动。

配套修改（只动构建配置，不改 Java 源码逻辑）：
- `gradle.properties`：`minecraft_version` 改为 `1.21.1`，
  `yarn_mappings` 改为 `1.21.1+build.3`（1.21.1 最新 Yarn 构建），
  `loader_version` 改为 `0.15.11`（1.21.1 时代稳定 Loader），
  `fabric_version` 改为 `0.116.15+1.21.1`（1.21.1 最新 Fabric API），
  `modmenu_version` 改为 `11.0.4`（1.21.1 对应的 ModMenu 版本）。
- `build.gradle`：Fabric Loom 从 `1.14.10` 降到 `1.7.3`
  （1.21.1 时代对应的 Loom 版本，2024-07-31 发布）。
- `gradle/wrapper/gradle-wrapper.properties`：Gradle 从 `9.2.0` 降到 `8.8`
  （Loom 1.7.x 要求 Gradle ≥ 8.6，8.8 是 1.21.1 时代经过验证的稳定版本）。
- `fabric.mod.json`：`depends.minecraft` 从 `~1.21.8` 改为 `~1.21.1`，
  `depends.fabricloader` 从 `>=0.16.14` 改为 `>=0.15.11`。
- `MCCFClient.java`：类注释中关于"目标版本固定在 1.21.8"的说明
  改为"目标版本固定在 1.21.1"。

**API 兼容性核对（初次评估，后续本地编译发现 3 处判断有误，已修正）**：
最初评估时认为 1.21.1 与 1.21.8 在本项目用到的 API 上完全一致，源代码无需改动。
本地编译验证后发现实际有 3 处差异，已全部修复（详见下一条"本地编译报告修复的 3 处 API 差异"）：
- `PacketCodecs.BOOLEAN`（1.21.8）→ `PacketCodecs.BOOL`（1.21.1）✅ 已修
- `HudElementRegistry.addLast`（1.21.8 才有）→ `HudRenderCallback.EVENT.register`（1.21.1）✅ 已修
- `RenderTickCounter.getTickProgress(boolean)`（1.21.8）→ `getTickDelta(boolean)`（1.21.1）✅ 已修

其余 API（`Identifier.of()`、`PacketCodecs.STRING`、`WorldRenderContext.tickCounter()`、
`WorldRenderEvents.AFTER_ENTITIES`、`KeyBinding` 字符串分类 + `wasPressed()`、
`ClientReceiveMessageEvents.CHAT` 5 参数签名、`AlwaysSelectedEntryListWidget` 构造器
——后者在 1.21.1 上是 5 参数，1.21.8 扩为 6 参数，也已修复）均核对完毕。

### 2026-07-28　本地编译报告修复的 3 处 API 差异
切到 1.21.1 后本地编译验证，发现 3 处 1.21.1 ↔ 1.21.8 之间的 API 差异
（初次评估时误判为"完全一致"，详见上一条的修正说明），全部已修复：

1. `RequestConfigPayload.java`：`PacketCodecs.BOOLEAN` → `PacketCodecs.BOOL`
   （Yarn mappings 在 1.21.1 上 boolean codec 字段名为 `BOOL`，1.21.8 改名 `BOOLEAN`）。
2. `MCCFClient.java` + `HotbarSubtitleRenderer.java`：`HudElementRegistry.addLast(...)`
   → `HudRenderCallback.EVENT.register(...)`。`HudElementRegistry` 是 Fabric API
   后续版本（约 1.21.6+）才引入的新 API，1.21.1 上只有旧的 `HudRenderCallback`。
   方法签名从 `addLast(Identifier, BiConsumer<DrawContext, RenderTickCounter>)`
   改为 `EVENT.register(BiConsumer<DrawContext, RenderTickCounter>)`，
   `HotbarSubtitleRenderer.render` 方法签名不变，兼容新回调。
3. `WorldSubtitleRenderer.java`：`context.tickCounter().getTickProgress(boolean)`
   → `getTickDelta(boolean)`。`RenderTickCounter` 接口在 1.21.1 上方法名是
   `getTickDelta`，1.21.8 改名 `getTickProgress`，签名一致。
4. `ModelSelectionScreen.java`：`AlwaysSelectedEntryListWidget` 构造器从 6 参数
   `(client, w, h, top, bottom, itemHeight)` 改为 5 参数 `(client, w, h, top, bottom)`。
   1.21.1 上没有 `itemHeight` 参数，1.21.8 才加进来；本类的构造器签名保留
   `itemHeight` 参数（API 兼容），只是 `super(...)` 调用不再传它。

**API 签名核实方式**：直接用 PowerShell + `System.IO.Compression.ZipFile`
打开 `~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-clientonly-1.21.1-...-v2.jar`
反编译查看 `AlwaysSelectedEntryListWidget` 和 `RenderTickCounter` 的实际方法签名，
再用 `javap -p` 输出完整签名。比查 Fabric 在线文档（不同版本混在一起容易看错）
或问 AI（容易拿新版本答案套旧版本）都更可靠——本地 jar 就是项目实际编译用的
那一份，看到什么签名编译器就认什么签名。

### 2026-07-28　JAR 命名 + 版本号策略
- `gradle.properties`：`archives_base_name` 从 `mccf` 改为 **`MCConversationFramework`**
  （之前 JAR 名是 `mccf-0.1.0.jar`，太简略；现在是 `MCConversationFramework-0.2.0.jar`）。
- `mod_version` 从 `0.1.0` 升到 **`0.2.0`**（minor 版本升级，对应本次
  1.21.8 → 1.21.1 版本切换 + 多处 API 适配修复）。
- **后续版本号策略**：小 bug 修复升 patch（0.2.1、0.2.2...），
  功能变更/版本切换升 minor（0.3.0...），重大重写升 major（1.0.0...）。

### 2026-07-17　包名修复 + 构建配置修复
反馈"所有 `net.minecraft.*` 包都找不到"之后，逐个核对了项目里引用的每一个
Minecraft 类的包路径（对照 Yarn 官方 Maven 文档），修复：

1. `HearingResolver.java`：`ServerPlayerEntity` 误写成
   `net.minecraft.server.level.ServerPlayerEntity`，正确包是
   `net.minecraft.server.network.ServerPlayerEntity`。
2. `HearingResolver.java`：`RaycastContext` 误写成
   `net.minecraft.util.math.RaycastContext`，正确包是
   `net.minecraft.world.RaycastContext`。
3. `build.gradle` 引用了未定义的 `${project.modmenu_version}`，已在
   `gradle.properties` 补上。
4. Loom 插件版本从浮动的 `1.14-SNAPSHOT` 改为锁定的 `1.14.10`。
5. `mappings` 从 `loom.officialMojangMappings()` 改回官方验证最充分的 Yarn。
6. Gradle wrapper 版本一度锁定为 `9.0.0`。
7. 移除了未使用对应插件的 `fabricApi { configureDataGeneration() }` 配置。

### 同日　版本从 1.21.11 降到 1.21.8
实现"贴近说话人显示字幕"功能时发现 Fabric API 的 `WorldRenderEvents` 在
1.21.9/1.21.10 的移植中被官方整体移除（渲染管线重构，无替代方案）。为保留
这个核心体验，把目标版本从 1.21.11 降到 1.21.8（该 API 最后一个正常工作的
版本）。配套修改：

- `gradle.properties`：`minecraft_version`/`yarn_mappings`/`loader_version`/
  `fabric_version` 全部改为 1.21.8 对应版本。
- `MCCFClient.java`：`KeyBinding` 构造函数在 1.21.9 起改用
  `KeyBinding.Category` 对象、按键检测方法改名为 `consumeClick()`；
  1.21.8 上改回旧写法（字符串分类 + `wasPressed()`）。
- `HudElementRegistry`、`WorldRenderEvents.AFTER_ENTITIES`、
  `Entity.getLerpedPos` 等其余客户端渲染 API 在 1.21.8 上签名不变，无需调整。

### 同日　Gradle 版本改回 9.2.0
Gradle 一度从验证过的 9.2.0 改成"官方文档写的 8.14/9.0"，结果实测报错：
`Could not resolve net.fabricmc:fabric-loom:1.14.10`（No matching variant）。
原因是 **Loom 1.14.10 这个精确 patch 版本实际发布时用的 Gradle Plugin API 是
9.2.0**，比"官方支持 8.14/9.0"这个粗粒度声明更严格——不同 patch 版本对
Gradle 的实际要求可能比大版本号声明更新更快。已把
`gradle/wrapper/gradle-wrapper.properties` 改回 **9.2.0**（2025-10-29 发布的
正式版本）。

### 本地编译报告修复的 3 处 bug
1. `gradle.properties`：`modmenu_version` 从 1.21.11 对应的
   `17.0.0-beta.1` 改为 1.21.8 对应的 **15.0.2**（经 Modrinth 核实）。
2. `fabric.mod.json`：`depends` 里的 `minecraft`/`fabricloader` 版本号
   仍是 1.21.11 时代残留值，改为 `~1.21.8` / `>=0.16.14`。
3. `RequestConfigPayload.java`：确认 1.21.8+build.1 实际用的字段名是
   `PacketCodecs.BOOLEAN`（此前记录中"应为 BOOL"的说法有误，以本地实测为准）。

   > **2026-07-28 修正**：这条结论只在 1.21.8 上成立。后续把目标版本切回
   > 1.21.1 时本地编译验证发现，1.21.1 上 Yarn mappings 的字段名仍然是
   > `BOOL`，到 1.21.8 才改名为 `BOOLEAN`。也就是说**当年"应为 BOOL"
   > 那条更早的记录其实是对的**，只是当时在 1.21.8 上验证成了 `BOOLEAN`
   > 就以为是错的——同一段代码在不同 MC 版本上字段名不同。详见
   > "2026-07-28 目标版本从 1.21.8 切换到 1.21.1"一节。

### 新功能：API Endpoint 可配置 + 一键获取模型 + 日志导出 + 多语言
- **API Endpoint 可配置**：除 Mock 外全部 Provider 现在都支持在配置界面里
  自定义 API 基础地址（例如接自建反代/兼容网关），默认值集中在
  `config/ProviderDefaults.java`，配置界面有"恢复默认"按钮一键清空自定义值。
  `ProviderConfig.host` 字段重命名为语义更准确的 `endpoint`。
- **一键获取模型**：配置界面新增"获取模型"按钮（详见"三、配置界面怎么用"）。
- **日志导出**：配置界面新增"导出日志"按钮（详见"三、配置界面怎么用"）。
- **多语言支持**：配置界面所有文字改用 `Text.translatable()`，跟随玩家
  Minecraft 客户端语言设置自动切换，已提供完整简体中文翻译（`zh_cn.json`）。

### 崩溃修复：未连接服务器时调整窗口导致崩溃
根本原因：`MCCFConfigScreen.init()` 无条件调用
`ClientPlayNetworking.send(...)`，但 `init()` 不仅在首次打开 Screen 时执行，
Minecraft 在玩家**调整游戏窗口大小**时也会对所有当前打开的 Screen 重新走一遍
`init()`（原版 resize 流程的一部分）。若玩家尚未进入世界/连接服务器就打开了
配置界面，`send()` 在没有活跃网络连接时调用会抛异常，这个异常发生在 resize
流程内、未被捕获，直接导致客户端崩溃。

修复：改用 Fabric 官方提供的 `ClientPlayNetworking.canSend(payloadId)` 方法
（专门用于判断"当前是否处于游戏中、可以发送该 payload"，未在游戏中时返回
`false` 而不是抛异常），所有会发包的地方（`init()`、`onSave()`、
`onFetchModels()`）都加了这层判断。

### 字幕不显示问题修复
排查后发现 `WorldSubtitleRenderer.java`（VISIBLE 模式，字幕悬浮在说话者上方）
里有一处颜色值 bug：文字颜色用的是 `0xFFFFFF`，在 Minecraft 的 ARGB 颜色格式下，
这个值缺少最高 8 位的 alpha 通道，等价于 `0x00FFFFFF`（alpha=0，完全透明）——
也就是说字幕确实被渲染了，只是渲染成了完全透明、肉眼不可见的状态。已改为
`0xFFFFFFFF`（完全不透明的白色）。`HotbarSubtitleRenderer.java`（物品栏上方
模式）用的是官方 `Colors.WHITE` 常量，颜色值本身没有问题。

（按要求，"字幕显示在人物旁边而非头顶"这个体验层面的调整当时没有做——见
"七、已知限制"最后一条。）

### 2026-07-22　README 整理
第一次通读全部源码后发现 README 存在几处与代码不符/内部矛盾的问题，本轮只动
文档，不改代码：

1. **章节编号混乱修复**：原文件里"四、如何接入其他翻译 Provider"和
   "四、目录结构"共用同一个编号"四"；"六、已知限制"实际排在"七之三"
   之后、"八"之前，顺序被打乱。现在按 一~八 顺序重新排列成
   在你自己电脑上编译 / 功能范围 / 配置界面 / 接入 Provider / 目录结构 /
   配置文件 / 已知限制 / 更新日志。
2. **目录结构过时**：原文档里的目录树是很早期的版本，缺少整个 `config/`
   包（`ProviderConfig`/`ProviderDefaults`）、`command/ConfigSyncHandler`、
   客户端的 `config/`（配置 Screen 相关 5 个类）和 `util/LogExporter`，
   已对照实际源码重写。
3. **Fabric API 版本描述前后矛盾**：文档开头写"目标版本 Fabric API 0.130.0"，
   但"二、编译"章节里却让用户装"Fabric API 0.141.5+1.21.11"——这是版本从
   1.21.11 降到 1.21.8 时残留的旧文字，没有跟着改。已统一为
   `0.130.0+1.21.8`（与 `gradle.properties` 实际值一致），并顺带把
   ModMenu 版本号也标注清楚（15.0.2）。
4. **发现死代码，记录在案**：审查 `network/` 包时发现
   `RequestModelListPayload` / `ModelListResponsePayload` 两个类没有被
   `MCCF.java` 或 `MCCFClient.java` 的任何地方注册或引用，应是"一键获取
   模型"功能早期迭代时的遗留代码。不影响运行，没有主动删除，已在"五、
   目录结构"和"七、已知限制"里注明，等你确认是否需要清理。
5. **接入 Provider 示例更新**：原文档的示例代码写的是
   `translationService.registerProvider(new OpenAiTranslationProvider(apiKey))`，
   但实际项目里 Provider 的创建/注册逻辑已经统一收敛到 `ProviderFactory`，
   原示例不再对应当前架构，已改为对照 `ProviderFactory` + `ProviderDefaults`
   的说法。

### 2026-07-22　新功能：纯客户端模式
按你的要求新增：服务器没装 MCCF 时，自动（也可手动强制）降级为"仅本地翻译
聊天栏文字"，不依赖任何服务端组件。详细使用说明见"三之二、纯客户端模式"。

新增文件（全部在 `src/client`，不涉及服务端代码改动，也没有新增网络包）：
- `client/mode/ClientOnlyModeManager.java`：模式判定（自动检测 + 手动覆盖），
  检测方式是 `ClientPlayNetworking.canSend(RequestConfigPayload.ID)`——复用了
  已有的配置请求通道，不需要额外发一个"探测"包等服务端回应。手动覆盖持久化到
  `config/mccf/client-mode.json`。
- `client/config/ClientOnlyTranslationConfig.java`：玩家自己的本地翻译配置，
  持久化到 `config/mccf/client-only-config.json`，与服务端权威配置完全独立、
  任何人可编辑（复用了已有的 `ClientProviderConfig` 结构，没有重复定义）。
- `client/config/ClientOnlyConfigScreen.java`：对应的配置子界面，从主配置
  界面新增的"纯客户端本地翻译设置"按钮打开。
- `client/chat/ClientOnlyChatTranslator.java`：监听 `ClientReceiveMessageEvents.CHAT`，
  在纯客户端模式下把收到的每条聊天消息异步翻译后追加显示，不替换原文、
  不做任何空间判定。

`MCCFClient.java` 改动：`onInitializeClient()` 里加载模式管理器 + 注册聊天
翻译器；`JOIN`/`DISCONNECT` 事件里分别刷新/重置服务器检测结果。

设计上刻意保持和现有服务端权威配置（op-only）完全分离，没有去改动
`ConfigSyncHandler` 的权限模型——本地翻译配置本来就只影响玩家自己看到的
结果，不需要、也不应该套用"仅 op 可改"这套是为"会影响所有人"的服务端配置
设计的规则。"从服务器同步"按钮复用了已有的 `ConfigSnapshotPayload` 数据
（`ClientConfigState`），没有新增网络包，只拷贝 Provider/模型名/Endpoint
这些公开字段，不拷贝 API Key。

**这一轮里曾有两处 API 用法是凭对 Fabric API 的既有印象写的，没有联网核实
也没有本地编译验证，当时标注为风险点**——这两处（`ClientReceiveMessageEvents.CHAT`
事件签名、`CyclingButtonWidget.setValue(...)`）后续在 1.21.1 上经本地编译
验证均通过，风险已消除，详见"七、已知限制"最后一条。
