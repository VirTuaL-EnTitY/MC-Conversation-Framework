# MC Conversation Framework (MCCF)

**[English README](README_EN.md)** | 简体中文（当前）

---

## 这是什么？能给我带来什么好处？

你有没有在国际服里遇到过这种情况：外国玩家在世界频道打了一长串话，你完全看不懂；
或者你想跟旁边的老外交易/组队，却因为语言不通只能互相打问号？

**MCCF 就是解决这个问题的：装上它，服务器里说中文的和说英文的（以及日语、韩语、
德语、法语、西班牙语、俄语等）玩家可以直接用各自的母语聊天，其他人看到的会自动是
翻译好的内容。** 不需要开着翻译软件来回复制粘贴，聊天这件事本身就是"无缝"的。

它还做了几件让这套翻译更"沉浸"、更贴近真实说话的事：

- **翻译只给听得到的人看**：不是全服广播式的翻译，而是按游戏内实际的距离/是否
  被墙挡住来决定谁能"听到"这句话——离得近（看得见说话者）的消息出现在聊天栏，
  离得远看不到人只能听到的就显示在物品栏上方，跟真实说话的感觉一致，不会出现
  "全服都看到你在哪个角落嘀咕"的尴尬。
- **翻译服务你自己选，自己的 Key 自己填**：支持接入 OpenAI、Claude、Gemini、DeepL、
  Kimi、DeepSeek，也支持完全本地免费跑的 Ollama——服主可以在游戏内一个设置界面里
  切换和管理，不需要碰配置文件、不需要重启服务器。
- **不想让服务器统一管翻译？** 也可以只装客户端、自己配置翻译服务，不依赖服主
  是否安装了这个模组（详见下方"纯客户端模式"）。
- **错过的对话可以回看**：字幕会自动消失，但游戏里有一个"聊天历史记录"界面，
  能翻看这局游戏里发生过的所有对话，还会自动按"这是一段对话"分组显示，方便回顾。

## 怎么使用？

1. **下载**：去 [Modrinth](https://modrinth.com/mod/mc-conversation-framework)
   下载最新的 `.jar` 文件。（GitHub Release 只提供源码和更新日志，不含编译好
   的 jar——jar 的发布渠道统一在 Modrinth。）
2. **安装**：把下载的 `.jar` 放进 Minecraft 的 `mods` 文件夹。
   - 如果你是**服主**：服务器和你自己的客户端都需要装，才能获得完整的"空间化翻译"
     体验（谁能听到谁听不到、近处走聊天栏 / 远处走物品栏字幕等）。
   - 如果你只是**普通玩家**、连的服务器没装这个模组：你自己单独装客户端也能用——
     会自动切换成"纯客户端模式"，本地把聊天栏文字翻译给你自己看，见下方说明。
3. **同时装好 [Fabric API](https://modrinth.com/mod/fabric-api)**（必需）和
   [Fabric Loader](https://fabricmc.net/use/)。可选装
   [ModMenu](https://modrinth.com/mod/modmenu) 获得图形化设置入口。
4. **进游戏后配置翻译服务**：按 `Esc` 打开暂停菜单，点"聊天历史记录"旁边的设置
   入口（或用 ModMenu 打开"MCCF"的设置），选择一个翻译服务（比如 DeepL 或 OpenAI）
   填入你自己的 API Key 即可开始使用。
5. 不知道该填什么、遇到问题，或者想了解更详细的功能范围/开发细节，
   请继续阅读下面的技术文档，或前往
   [GitHub Issues](../../issues) 反馈。

---

## 技术简介

面向 Minecraft 多人服务器的沉浸式跨语言交流基础设施。核心链路：
文字聊天拦截 → 空间化听觉判定（距离 + 射线遮挡）→ 动态对话上下文（Conversation，
一个对话组从开始到结束作为完整翻译上下文）→ 可插拔翻译 Provider（OpenAI/Claude/
Gemini/DeepL/Kimi/DeepSeek/Ollama）→ 空间化分发（近处走聊天栏 / 远处走物品栏上方
字幕）→ 游戏内配置界面（ModMenu 集成 + 按键呼出）。

目标版本：**Minecraft 1.21.1 · Yarn Mappings 1.21.1+build.3 · Fabric Loader 0.15.11 ·
Fabric API 0.116.15+1.21.1 · Fabric Loom 1.7.3 · Gradle 8.8 · Java 21**

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
构建产物在 `build/libs/MCConversationFramework-<版本号>.jar`（版本号见
`gradle.properties` 里的 `mod_version`），把它丢进服务器和客户端的 `mods/` 文件夹即可
（服务器和客户端都要装，因为这是一个同时含服务端逻辑和客户端渲染的模组）。

同时确保服务器和客户端都装了对应版本的 **Fabric API**（`0.116.15+1.21.1` 或更高的
1.21.1 兼容版本），可以在 Modrinth 上下载。装了
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
- ✅ 双模式空间化分发：VISIBLE（看得见说话者，消息走原版聊天栏）/ AUDIBLE（看不到说话者，消息走物品栏上方 HUD 字幕，多人堆叠布局）
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
空间判定、也没法做距离/遮挡这类需要服务端参与的判定——所有人还是会照常
收到原版广播的聊天。这种情况下 MCCF 会自动降级为**纯客户端模式**：不做
空间判定、不渲染物品栏字幕，只是把收到的每一条聊天消息在本地翻译成
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
│   ├── ActiveSubtitle.java           客户端内存态字幕数据（仅 AUDIBLE 模式）
│   ├── SubtitleManager.java          接收、超时管理、多人去重排序（仅 AUDIBLE）
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
- **纯客户端模式（`ClientOnlyChatTranslator`）用到的 `ClientReceiveMessageEvents.CHAT`
  事件签名**：此前 README 里标注为"凭印象写、未本地编译验证"的风险点，在
  1.21.1 上经本地编译验证**签名正确**（5 参数：`message, signedMessage, sender, params, receptionTimestamp`），
  该风险点已消除。`CyclingButtonWidget.setValue(...)`（在"从服务器同步"按钮里
  用来刷新下拉框显示）此前同样标注为"凭印象写"，也已在 1.21.1 上编译通过，
  运行时行为正常。

---

## 八、更新日志

### 2026-08-02　0.16.2 修复：配置界面"显示原文"开关布局 + 灰色不可选 + 强制关闭思考按钮重复显示

本轮修复三个相关问题：

**1. "显示原文"两个开关从并排一行拆成各自一整行**
- 应用户反馈：配置界面里"字幕显示原文"（AUDIBLE）和"聊天栏显示原文"
  （VISIBLE）两个开关原来并排挤在同一行，各占半宽，布局拥挤。
- `ServerConfigPanel.buildRightPanel`：两个开关从并排一行拆成各自一整行
  （`panelWidth` 宽度），总控件行数从 7 增至 8。spacing 公式被减数从
  `140`（= 7×20）改成 `160`（= 8×20），分母从 `6` 改成 `7`。

**2. "显示原文"两个开关改为灰色不可选**
- 应用户要求：这两个开关在配置界面里设为 `active=false`，仅作只读展示，
  实际值由 `config/mccf/config.json` 直接修改。
- `ServerConfigPanel.applyEditability`：两个开关的 `active` 无条件置 `false`。

**3. 修复"强制关闭思考"按钮重复显示（真正根因）**
- **症状**：用户反馈"出现了第二个强制关闭思考按钮，上面的关、下面的开"。
- **误判过程**：第一轮我以为是 spacing 公式算错导致控件挤在一起、文字
  叠加被误看成两个按钮（改了 140→160）。用户明确指出"没在开玩笑，那就
  是强制关闭思考按钮"后，才定位到真正根因——不是 spacing，而是真的有
  两个 `disableThinkingButton` 同时显示。
- **真正根因**：`ServerConfigPanel` 和 `LocalConfigPanel` 用同一套
  `left/top/right/bottom` 坐标，各自的 `disableThinkingButton` 位置完全
  重叠。`refreshFieldsFromState` 里 `disableThinkingButton.visible =
  supportsThinking` 无条件设 visible，覆盖了 `setVisible(false)` 设的
  不可见状态。当非活动标签页的 Provider 支持思考时，它的
  `disableThinkingButton` 会错误显示，和活动标签页的同位置按钮叠在一起。
  `LocalConfigPanel.onTabVisibilityChanged` 会调 `refreshFieldsFromState`，
  所以切到"服务端配置"标签页时 Local 的按钮会漏出来；两个 Panel 的
  `disableThinking` 值可能不同，玩家就看到"上面关、下面开"两个按钮。
- **修复**：两个 Panel 的 `refreshFieldsFromState` 里
  `disableThinkingButton.visible = supportsThinking && tabVisible`，
  非活动标签页的按钮永远不可见。

纯 UI 修复，不涉及网络协议或翻译逻辑变化，按 9.1 规则升 patch：
`0.16.1` → `0.16.2`。

### 2026-08-02　0.16.4 调整：GitHub Release 改为源码 Release + 新增一键发版脚本

**1. GitHub Release 不再附带 jar，jar 发布渠道统一收敛到 Modrinth**

应用户需求：GitHub Release 改为只包含源码（GitHub 自动生成的 zip/tar）+
从 README 更新日志提取的 Release Notes，不再上传编译好的 jar。

- `.github/workflows/release.yml`：`build` job 仍然跑编译验证（确认代码能
  编译通过 + jar 产物确实生成），但不再上传 artifact；`release` job 去掉
  `download-artifact` 步骤和 `Create Release` 的 `files` 参数——GitHub 会
  自动附上 source code archives。
- 这样 GitHub Release 纯粹是版本记录 + changelog，不会和 Modrinth 上的
  jar 产生"两边版本不同步"的问题。早期版本（0.8.0~0.16.3）的旧 Release
  里仍然有历史版本的 jar，新版本不再这么做。
- README.md / README_EN.md 下载说明同步更新：主渠道改为 Modrinth，
  GitHub Release 只提供源码和更新日志。

**2. 新增 `release.py` 一键发版脚本**

- 从 `gradle.properties` 读取当前 `mod_version`，自动打 `v*.*.*` tag 并
  push 到远程，触发 GitHub Actions 创建源码 Release。
- 前置检查：工作区干净（防止改一半代码就发版）、tag 不存在（防止重复发），
  当前分支已推到远程。
- 支持 `--check` dry-run 模式。
- 不自动 bump 版本号、不自动 commit——发版是"确认这版可以发了"的显式
  动作，版本号由用户在开发过程中按 9.1 规则手动维护。

**3. 顺手修复 README 下载说明里"字幕悬浮位置"的过期描述**

- README.md"怎么使用"章节里"字幕悬浮位置等"是 0.16.0 移除世界空间字幕
  前的旧描述，本次顺手改成"近处走聊天栏 / 远处走物品栏字幕等"。

版本号 `0.16.3` → `0.16.4`（按 9.1 规则升 patch：CI/发布流程调整 + 脚本
新增 + 文档同步，不涉及模组运行时逻辑变化）。

### 2026-08-02　0.16.3 修复：配置界面输入框 placeholder 文字超出输入框宽度

应用户反馈：API Key 输入框的 placeholder"API 密钥（留空则保持当前值不变）"
有一部分文字超出输入框边界。

**根因**：`apiKeyField` 宽度是 `panelWidth - 44`（比 model/endpoint 输入框
窄 44px，给 clearApiKeyButton 留位），但它的 placeholder 却是最长的。各语言
版本长度差异极大——中文约 18 字符、英文约 36 字符，而德语/法语/西班牙语/
俄语版本长达 50+ 字符，在窄屏或小 GUI 比例下必然超出。`TextFieldWidget`
在 1.21.1 上渲染 placeholder 时不做 `trimToWidth` 截断，文字直接画到输入框
外面。endpoint 的 placeholder"默认地址（留空即可）"在德语/法语等语言下
也有同样问题。

**修复**：简化全部 9 种语言的 `api_key.placeholder` 和 `endpoint.placeholder`，
去掉冗长的"留空则保持当前值不变"解释，只保留核心提示（如"API 密钥"、
"默认地址"）。"留空保持当前值"这个行为对用户是直观的，且 `clearApiKeyButton`
已经区分了"留空保存"和"主动清除"两种操作，不需要在 placeholder 里重复说明。

纯语言文件文案修改，不涉及代码逻辑，按 9.1 规则升 patch：
`0.16.2` → `0.16.3`。

### 2026-08-02　0.16.1 文档调整：去除"固定在 1.21.1"的版本理由论证

应用户反馈：0.16.0 移除世界空间字幕（WorldRenderEvents）后，升级到更高
Minecraft 版本的最大技术障碍已经消除，README 里原本为"为什么固定在 1.21.1"
所做的论证（1.21.x 生态兼容性、WorldRenderEvents 在 1.21.9+ 被移除的版本
兼容顾虑等）不再需要保留为面向玩家的说明。

**改动**：
- `README.md`：删除"技术简介"中 `> **为什么固定在 1.21.1？**` 整段引用块，
  只保留"目标版本：……"这一行事实陈述。mappings 说明段落保留（与版本锁定
  无关，是 Yarn vs Mojang mapping 的命名差异提醒）。
- `README_EN.md`："This is Minecraft 1.21.1 only."条目原本附带的整段
  ecosystem compatibility / WorldRenderEvents 论证一并删除，只保留版本号
  事实。
- `gradle.properties`：Fabric Properties 注释从"目标版本固定为 1.21.1 +
  长篇理由"精简为"1.21.1 仅作为当前发布基线，不作为长期锁定"，如实反映
  当前状态——升级障碍已消除，未来可更自由地跟进新版本。

纯文档/注释调整，不涉及代码逻辑或构建配置变化，按 9.1 规则升 patch：
`0.16.0` → `0.16.1`。

### 2026-08-02　0.16.0 移除世界空间字幕（VISIBLE 走聊天栏转正）+ AI 上下文改为完整对话组

本轮改动响应用户两方面反馈：(1) 世界空间字幕（WorldSubtitleRenderer）代码存在
但实测一直不显示，根因始终未定位，不再作为当前版本功能保留；(2) AI 翻译上下文
原本有硬截断，希望改为"一个 Conversation 从开始到结束作为完整上下文"。

**1. 移除世界空间字幕，VISIBLE 走聊天栏转正**
- 删除 `WorldSubtitleRenderer.java` 文件，移除 `MCCFClient` 中
  `WorldRenderEvents.AFTER_ENTITIES` 的渲染器注册和相关 import。本项目不再依赖
  `WorldRenderEvents`——那个 API 在 1.21.9+ 因渲染管线重构被移除的版本兼容顾虑
  随之消除（README "为什么固定在 1.21.1" 段落已更新）。
- **决策历史**（保留在 `MCCFClient` / `HearingResolver` / `MCCFConfig` 注释里）：
  早期版本（0.3.0~0.4.0）曾尝试用世界空间渲染把字幕画到说话者模型旁边，但根因
  始终未定位、实测不显示。0.4.0 起临时把 VISIBLE 改走原版聊天栏作为绕开方案，
  0.16.0 正式确认这个绕开方案**转正**——VISIBLE 走聊天栏成为正式行为而非临时
  降级。HearingResolver 仍然区分 VISIBLE/AUDIBLE 两档（距离 + 视线判定），只是
  VISIBLE 的展示载体从"世界空间悬浮字幕"变成"原版聊天栏"——近处说话走聊天框、
  远处喊话走物品栏字幕的语义不变。
- `MCCFConfig.subtitleVisibleRange` 字段**保留不改名**：它承担的是 HearingResolver
  区分"看得见/听得到"两档的距离阈值职责，与展示载体无关；改名会破坏旧 config.json
  的向后兼容。字段注释已更新说明这一点。
- `ActiveSubtitle.Mode` 枚举（VISIBLE/AUDIBLE）和 `mode` 字段移除：删除
  WorldSubtitleRenderer 后，SubtitleManager 只接收 AUDIBLE 模式的 payload
  （VISIBLE 在 MCCFClient 接收时就被分流到聊天栏），Mode 枚举成了死代码。
  `SubtitleManager.onReceive` 不再解析 mode，`HotbarSubtitleRenderer` 不再按
  mode 过滤。
- 涉及文件：删除 `WorldSubtitleRenderer.java`；修改 `MCCFClient.java`、
  `ActiveSubtitle.java`、`SubtitleManager.java`、`HotbarSubtitleRenderer.java`、
  `HearingResolver.java`、`MCCFConfig.java`。

**2. AI 上下文改为完整对话组（去掉所有条数截断）**
- 应用户明确要求"一个 Conversation 从开始到结束作为完整上下文"，移除两处截断：
  - `Conversation.recordMessage`：移除 `MAX_CONTEXT_MESSAGES = 20` 硬截断，
    整个对话组生命周期内的所有消息都保留作为翻译上下文。
  - `ChatCompletionsSupport.buildSystemPrompt`：移除 `context.size() - 5` 的
    最近 5 条截断，所有上下文消息都写入 prompt。
- 上下文不会无限增长：ConversationManager 的 idle timeout（默认 120 秒无人发言）
  会在对话组沉寂后整体释放 Conversation，下一次有人发言新建新组。这意味着上下文
  不会跨对话组泄露，也不会无限累积——长对话期间确实会让 prompt 变长、token 消耗
  增加，**这是用户知情接受的取舍**（用户在本次需求中明确选择"完全去掉截断"而非
  软上限方案）。
- 涉及文件：`Conversation.java`、`ChatCompletionsSupport.java`、
  `TranslationProvider.java`（接口注释更新）。

**3. 文档同步**
- README 顶部介绍、技术简介核心链路、"为什么固定在 1.21.1"、"当前功能范围"、
  "目录结构"、"已知限制"、"9.2.5 版本踩坑"（WorldSubtitleRenderer 条目标注
  文件已删）均已同步更新。
- 移除"七、已知限制"中关于 VISIBLE 世界空间字幕不显示的两条 Bug 条目
  （0.3.0 字幕位置改造 + 0.4.0 临时绕开），这两条已随 WorldSubtitleRenderer
  删除而彻底解决。

版本号 `0.15.0` → `0.16.0`（按 9.1 规则升 minor：功能移除 + 行为变更 + AI 上下文
策略调整，均为非破坏性——网络协议字段不变，旧客户端/服务端仍可正常通信）。

**已知取舍（如实记录）**：
- 完全去掉上下文截断后，超长对话（比如几十分钟不停歇的讨论）的 prompt 会变长、
  token 消耗增加，可能超出某些 Provider 模型的上下文窗口导致请求失败。这是用户
  明确选择的方案，没有加软上限兜底——如果未来实测发现这个问题，可以在 Provider
  层单独加截断，不影响 Conversation / ChatCompletionsSupport 的通用逻辑。
- `subtitleVisibleRange` 字段名与其实际职责（距离阈值，与展示载体无关）已不完全
  匹配，但为向后兼容旧 config.json 不改名。字段注释已说明这一点。

### 2026-07-30　0.15.0 新增：五家 Provider 独立"强制关闭思考"开关 + 新增智谱 AI Provider

响应用户要求：DeepSeek 这类带思考功能的模型默认会先"想一遍"再给出译文，
拖慢翻译速度、浪费 token；新增一个可以强制关闭思考的开关。实现前对
DeepSeek、Kimi、Claude、Gemini 逐一做了调研（联网核实各家官方文档），
发现每家的支持情况、参数名、模型代次限制都不一样，最终方案和调研结论
如下。

**1. 调研结论（各家"思考模式"现状，2026-08 核实）**
- **DeepSeek**：V4 系列（默认 `deepseek-v4-flash`/`deepseek-v4-pro`，
  官方已在 2026-07-24 停用旧的 `deepseek-chat`/`deepseek-reasoner`）默认
  开启思考，支持 `"thinking":{"type":"disabled"}` 关闭。
- **Kimi**：默认模型 `kimi-k2.5`（K2.x 系列）默认开思考、支持同样的
  `thinking:{type:disabled}` 参数关闭；但 **K3 系列官方文档明确写
  "Reasoning is always on. There is no non-thinking mode."**——如果玩家
  把模型手动改成 K3，这个开关传参不会报错但也不会生效。
- **Claude**：默认模型 `claude-sonnet-4-6` 支持旧版
  `thinking:{type:"disabled"}` 参数（"extended thinking is deprecated on
  the Claude 4.6 models, requests using it still succeed"）；但 **Claude
  4.7 及更新模型不再支持这个参数结构，会返回 400 错误**，新模型改用
  `effort` 参数控制思考强度，无法简单"禁用"。
- **Gemini**：默认模型 `gemini-3.5-flash` 支持
  `generationConfig.thinkingConfig.thinkingBudget:0` 关闭思考；但 **Gemini
  2.5 Pro / Gemini 3 Pro 官方文档明确写思考无法关闭**（"Thinking can't be
  turned off"）。
- **智谱 GLM**：GLM-5/GLM-5.2 官方示例代码确认支持
  `thinking:{type:"disabled"}`（与 DeepSeek/Kimi 同款参数结构）。
- **没有任何一家的 `listModels()` 接口会返回"这个模型是否支持关闭思考"
  这个信息**——因此无法用联网查询自动判断某个具体模型是否真的支持，
  只能在打开开关时给出通用警告，交给玩家自己验证（见下方第 3 点）。
- OpenAI 默认模型 `gpt-4o-mini` 不是推理模型，本身没有"思考"概念，不在
  这次的处理范围内。

**2. 每个 Provider 独立一个开关（而非全局一个）**
- `ProviderConfig`（服务端）/ `ClientProviderConfig`（客户端）新增
  `disableThinking` 字段，随其他 Provider 配置项一起保存/同步——应用户
  明确要求"只想关 DeepSeek 的思考，不想关 Kimi 的"这类精细控制，不是
  全局一个开关影响所有 Provider。
- `ChatCompletionsSupport.buildRequestBody` 新增带 `disableThinking`
  参数的重载，DeepSeek/Kimi/智谱（三家共用 OpenAI 兼容格式）复用这个
  方法；Claude（Messages API）、Gemini（generateContent API）各自的
  `buildRequestBody` 也分别加了对应的参数注入逻辑（结构不同，不能复用
  同一份代码）。
- `ServerConfigPanel`/`LocalConfigPanel` 新增
  `THINKING_CAPABLE_PROVIDERS` 判断——只有 DeepSeek/Kimi/Claude/Gemini/
  智谱这五家在配置界面里会显示"强制关闭思考"这个开关，其余 Provider
  （OpenAI、DeepL、Ollama、Mock）不显示，避免展示一个没有意义的选项。

**3.（交互）打开开关时弹出确认警告，关闭直接生效**
- 开关默认关闭、可自由打开（不是"灰色禁用"）——应用户最终确认的方案。
- 打开（false → true）时用 `ConfirmScreen` 弹出警告：说明部分新一代
  模型可能不支持强制关闭思考、可能导致该 Provider 的翻译请求完全失败，
  同时说明关闭思考的好处（翻译更快、更省 token）；玩家取消则按钮显示值
  还原为"关"，不实际生效。关闭（true → false）不需要确认，直接生效——
  关掉一个"可能有风险的设置"永远是安全操作。
- 服务端配置面板（`ServerConfigPanel`）和纯客户端面板
  （`LocalConfigPanel`）各自独立实现这个交互（前者改动经
  `UpdateConfigPayload` 提交给服务端持久化，后者直接改本地
  `ClientProviderConfig` 对象、点"保存"时随其他字段一起落盘），警告
  弹窗文案共用同一组翻译键。
- `ClientOnlyTranslationConfig#copyPublicFieldsFrom`（"从服务器同步"
  按钮的实现）新增 `disableThinking` 字段的拷贝——这个偏好属于"配置
  偏好"而非"敏感信息"（不像 apiKey 那样故意不同步），跟随
  model/endpoint 一起同步是合理的。

**4. 新增智谱 AI（Zhipu AI / Z.ai）Provider**
- 新增 `ZhipuTranslationProvider`，OpenAI Chat Completions 兼容接口，
  与 `KimiTranslationProvider`/`DeepSeekTranslationProvider` 结构一致。
  默认 endpoint `https://open.bigmodel.cn/api/paas/v4`，默认模型
  `glm-5.2`（当前最新旗舰，应用户明确指定）。
- 注册进 `ProviderFactory`、`ProviderDefaults`、客户端
  `ClientConfigState.PROVIDER_IDS`（"zhipu" 插入在 "deepseek" 和
  "ollama" 之间）。新增 `mccf.provider.zhipu` / 
  `mccf.config.provider_hint.zhipu` 翻译键，9 种语言均已补全。

**5. 本地化**
- 新增 3 个翻译键（`mccf.config.disable_thinking`、
  `mccf.config.disable_thinking_warning_title`、
  `mccf.config.disable_thinking_warning_body`）+ 智谱相关 2 个键，
  合计 5 个新键 × 9 种语言均已补全。警告正文措辞经过仔细考量：
  同时说明好处（更快更省）和风险（部分模型可能拒绝请求），不是单纯的
  恐吓式警告。

**已知限制（如实记录，不回避）**：
- 判断"某个具体模型是否支持关闭思考"完全依赖玩家自己验证——没有任何
  官方 API 提供这个信息，配置界面无法做到"选了不支持的模型就自动置灰
  开关"这种智能提示，这是当前 AI 服务商生态的普遍限制，不是这个模组
  能绕过的技术问题。
- 参数名和支持范围可能随各家 API 版本更新而变化（本次调研截止
  2026-08-01），如果日后某家调整了参数结构，需要重新调研并更新对应
  Provider 实现类。

版本号 `0.14.0` → `0.15.0`（按 9.1 规则升 minor：新增功能，非破坏性——
不改动现有网络协议字段，仅新增，旧客户端/服务端仍可正常通信，只是新字段
会被忽略）。

### 2026-07-30　0.14.0 新增：聊天历史记录支持筛选与排序

聊天历史记录界面新增筛选和排序功能，应用户要求"再加一个聊天筛选以及更改
排序方式"。

**1.（协议无变化，纯客户端功能）`ChatHistoryManager` 新增筛选/排序支持**
- 新增 `FilterOptions`（record）：三个筛选维度——来源
  （`allowedSources`，`Set<ChatHistoryEntry.Source>`）、参与者
  （`participantFilter`，精确匹配玩家显示名）、关键词
  （`keyword`，匹配原文或译文，包含即可、大小写不敏感）——可以同时组合
  使用（AND 关系），任意维度为空表示该维度不参与筛选。
- 新增 `SortMode`（枚举）：`TIME_DESC`（默认，组内最后一条时间倒序）、
  `TIME_ASC`（组内最早一条时间正序）、`PARTICIPANT_COUNT_DESC`（参与
  人数从多到少）、`MESSAGE_COUNT_DESC`（消息条数从多到少）——后两者
  在排序键相同时都退回 `TIME_DESC` 作为次要排序键，保证多次渲染的结果
  顺序稳定，不会随机跳动。
- `groupedSnapshot()` 新增带参数重载 `groupedSnapshot(FilterOptions,
  SortMode)`，原有无参版本保留作为默认行为（内部调用新方法，传入
  "不筛选 + 时间倒序"），不破坏可能存在的其他调用方。
- **筛选粒度是"按对话分组"而不是"按单条消息"**：只要一个对话分组里有
  任意一条消息同时满足三个维度的条件，就把这个分组完整保留展示（包括
  组内所有消息和系统提示），不会只隐藏组内不满足条件的单条消息——那样
  容易让人看不懂某条消息为什么突然消失，按对话分组展示更符合"看对话"
  而非"看碎片消息"的使用场景。参与者筛选对没有服务端参与者名单的分组
  （CLIENT_ONLY 无归属消息）恒不通过，因为这类消息没有"对话参与者"概念。
- 新增 `knownSpeakerNames()`：收集当前历史记录里出现过的所有说话者显示名
  （去重、按首次出现顺序），过滤掉空白名字，供参与者筛选下拉框使用。

**2. `ChatHistoryScreen` 新增筛选/排序面板**
- 标题栏最右边新增一个"筛选"小按钮，点击展开/收起一个筛选/排序面板——
  平时收起不占用列表可用高度，展开时列表顶部相应下移让出 84px 空间
  （应用户明确要求"默认是一个小按钮，点开就会有详细的面板"）。
- 面板内容：四个来源筛选开关（SELF/VISIBLE/AUDIBLE/CLIENT_ONLY，独立
  `ButtonWidget`，点击切换是否勾选，文字前缀用 ✓ 提示当前状态）、参与者
  下拉（`CyclingButtonWidget<String>`，选项来自 `knownSpeakerNames()`
  + "全部"）、关键词文本输入框、排序方式下拉（`CyclingButtonWidget
  <SortMode>`，四选一）。
- 关键词输入框不是"改了就立刻生效"——打字过程中每敲一个字符都重建列表
  会很卡，也容易在打到一半时列表就跳来跳去。改为失去焦点（点击别处/
  收起面板）或按回车时才应用筛选，用 `render()` 里每帧检查一次输入框
  是否已失焦、以及 `keyPressed()` 拦截回车键两条路径共同保证及时应用。
- **修复一处真实 bug**：`rebuildList()` 每次筛选/排序变化都会创建新的
  `HistoryListWidget` 实例，旧实例之前没有从 Screen 的子控件集合里
  移除——会导致每次交互后残留一个失效的列表在原地，不仅浪费内存，旧
  列表的裁剪区域和输入响应还会跟新列表叠加，导致点击、滚动等交互错乱。
  修复为重建前先调用 `remove(listWidget)` 显式移除旧实例。
- 筛选/排序状态只存在于本次打开界面期间，不持久化——关闭界面后下次
  重新打开会回到默认的"不筛选 + 时间倒序"。

**3. 本地化**
- 新增 9 个翻译键（`mccf.history.filter.button`、`.participant`、
  `.participant_all`、`.keyword`、`mccf.history.sort.label`、
  `.time_desc`、`.time_asc`、`.participant_count`、`.message_count`），
  9 种语言均已补全。

版本号 `0.13.1` → `0.14.0`（按 9.1 规则升 minor：新增功能）。

### 2026-07-30　0.13.1 移除 CurseForge 相关说明 + 修正一处过期硬编码版本号

作者决定这个项目不打算发布到 CurseForge，只发 GitHub Release 和 Modrinth。
中英文 README 里"怎么下载/安装"章节原本提到的 CurseForge 全部去掉，只保留
GitHub Release 和 Modrinth 两个渠道。

之前（见 0.8.0 记录）写工作流时 README 里还提过"项目要发布到 GitHub 并上传
CurseForge / Modrinth"，以及"CurseForge / Modrinth 的自动上传本次没有加入
workflow"——这两处是当时的真实决策记录，属于历史事实，本次不做回溯修改，
只在下载安装说明这类"面向当前用户的操作指引"里去掉 CurseForge，避免用户
去 CurseForge 找但根本没有发布过的困惑。

顺手修正了 `README.md` 第 4 节里一处遗留的硬编码版本号（`例如当前是 0.8.0`
早就跟实际版本对不上了），改成纯粹引用 `gradle.properties` 的
`mod_version`，不再在文字里写死具体数字，避免以后每次发版又要手动改这一处。

涉及文件：`README.md`、`README_EN.md`（各去掉一处 CurseForge 提及）。

版本号 `0.13.0` → `0.13.1`（按 9.1 规则升 patch：纯文档修正，不涉及代码
逻辑或功能变化）。

### 2026-07-30　0.13.0 悬浮说明从顶部标题挪到左侧列表本身 + 修复编译错误

响应用户反馈：0.12.0 把 Provider 说明改成悬浮 tooltip 后，触发区域只有顶部
"当前选中查看"的标题一处，玩家想快速比较几个 Provider 的说明时，得先点选中
每一个才能看到对应说明，不够方便。这次把悬浮触发区域从顶部标题挪到左侧
Provider 列表本身，悬浮到列表里任意一项（不需要先点中它）就能看到该项的
说明，等于顺手预览了整个列表，不需要来回点选切换。

**1. 悬浮 tooltip 从顶部标题移到左侧列表**
- `ProviderListWidget#renderWidget`：在渲染每一行时判定悬浮命中（复用已有的
  `rowHovered` 变量），记录当前悬浮到的 `hoveredProviderId`；渲染循环和
  `disableScissor()` 之后统一画一次 tooltip——必须在 scissor 解除之后画，
  否则 tooltip 内容如果比列表本身宽，会被列表的裁剪区域裁掉右侧/底部超出
  的部分（scissor 只裁剪列表框内的绘制调用，tooltip 需要能画到列表框
  外面）。
- `ServerConfigPanel`/`LocalConfigPanel` 的 `renderExtra` 移除了原来挂在
  顶部标题上的悬浮判定逻辑（自行测量文字宽度构造判定矩形那段），标题
  恢复为纯静态展示，不再承担交互职责——tooltip 逻辑现在完全收敛在
  `ProviderListWidget` 内部，两个 Panel 不需要重复实现类似的悬浮判定。

**2.（编译错误修复）`MCCFClient.java` 缺少 `java.util.List` 的 import**
- 0.12.0 引入的 `ConversationRosterPayload` 接收器里用到了裸类名 `List<String>
  names = newlyAdded.stream()...`，但没有对应的 `import java.util.List;`——
  项目里这类地方通常要么走全限定名（`java.util.List<...>`）要么走顶部
  import，这处漏加了 import 又没用全限定名，本地编译直接报
  "找不到符号：类 List"。补上 `import java.util.List;` 即可，纯粹是
  0.12.0 遗留的一处疏漏，逻辑本身没有问题。

版本号 `0.12.0` → `0.13.0`（按 9.1 规则升 minor：交互体验改进 + 编译修复，
两者放在同一个版本号里发布）。

### 2026-07-30　0.12.0 提示区改为悬浮 tooltip + 聊天历史复用服务端 Conversation 分组 + 聊天栏可选显示原文 + 元信息补全

响应用户三方面反馈：(1) 配置界面提示文字位置不合理，截图显示大片空白区域；
(2) 聊天历史记录应该复用服务端已有的 Conversation（对话）分组机制，而不是
客户端自己按时间聚类猜测，并且要同时展示原文和译文；(3) 聊天栏（VISIBLE）
需要一个独立开关来决定要不要显示原文，以及作者信息/ModMenu 链接的收尾。

**1. 配置界面提示区改为悬浮 tooltip（修复大片空白问题）**
- 根因：`BOTTOM_HINT_AREA_HEIGHT` 之前固定预留 100px 给"Provider 说明 +
  状态消息"常驻文字，但日常使用中 Provider 说明这类不紧急信息完全占用不到
  这么多空间，导致界面下方出现用户反馈的"接近 1.8/4 屏幕高度"的空白。
- 修复：Provider 说明（"需要 API Key，支持上下文"这类）改为鼠标悬浮在
  顶部 Provider 标题上时才弹出的 `DrawContext.drawTooltip`，不再常驻占用
  空间；悬浮判定区域用 `textRenderer` 实际测量的文字宽度构造，不同语言的
  Provider 名称长度差异很大，不用硬编码宽度。状态消息（加载中/超时未安装/
  保存成功失败）继续常驻显示——这些是玩家必须立刻看到的信息，不适合藏进
  tooltip。`BOTTOM_HINT_AREA_HEIGHT` 相应从 100px 缩小到 50px。
- 涉及文件：`ServerConfigPanel.java`、`LocalConfigPanel.java`、
  `MCCFConfigScreen.java`。

**2.（协议扩展）聊天历史记录复用服务端 Conversation 分组机制**
- `SubtitlePayload` 新增 `conversationId`（所属 Conversation 的 id）、
  `sourceLang`/`targetLang`（说话者语言、听众目标语言，用于历史记录显示
  语言标签）三个字段。由于项目此前所有 payload 最多用到 5 字段的
  `PacketCodec.tuple(...)` 重载，字段数继续增加时是否有对应重载没有先例
  可核对，本次改为手写 `PacketCodec.of(encoder, decoder)` 实现编解码，
  不依赖任何不确定的重载数量。
- 新增 `ConversationRosterPayload`（独立网络包）：同步某个 Conversation
  当前完整的参与者名单（UUID + 显示名），只在参与者集合真正发生变化时
  才发送（不是每条消息都带完整名单，避免带宽浪费）——`Conversation
  .addParticipant` 改为返回 `boolean`（是否真的新增了成员，复用
  `Set.add()` 本身的语义），`ConversationManager.recordUtterance` 返回类型
  改为 `UtteranceResult`（Conversation + 这次真正新增的参与者集合），
  `SpatialChatHandler` 据此判断要不要调用新增的 `broadcastConversationRoster`。
- **执行顺序调整**：`Conversation` 的创建/归组这一步从"只在有听众时才执行"
  提前到"无论有没有听众都先执行"，让说话者本人始终先被归入一个
  Conversation（哪怕周围没人、只有他自己）——这样自己的"自言自语"回显
  消息在历史记录里也能正常归组，不需要特殊标识表示"不属于任何对话组"
  （应用户明确要求）。相应地，`dispatchSelfEcho` 现在携带 `conversationId`
  参数。
- 客户端新增 `ConversationRosterManager`（维护 conversationId → 参与者
  名单的本地映射）、`ChatHistorySystemEvent`（"开始了一段新对话"/
  "XX 加入了对话"系统提示，独立于 `ChatHistoryEntry` 建模，避免让聊天
  消息类型的大部分字段在系统提示场景下变成 null）、`ChatTimelineItem`
  （sealed interface，统一聊天消息和系统事件在同一条时间线上排序）。
- `ChatHistoryManager` 新增 `groupedSnapshot()`：按 conversationId 分组、
  组内按时间排序，包装成 `ConversationGroup` 列表直接供界面渲染——无归属
  消息（纯客户端模式的 CLIENT_ONLY，没有服务端 Conversation 概念）各自
  单独成组。
- `ChatHistoryScreen` 彻底重写：大标题列出该 Conversation 里出现过的
  所有参与者显示名（"LimAimo、test、Alex 的对话"，用带 `%s` 占位符的
  完整句子模板 `mccf.history.conversation_title`，而不是代码里拼接
  "名字+固定后缀"——不同语言的语序差异很大，比如英语是所有格后缀
  "%s's conversation"，中文是前缀"%s的对话"，固定拼接方式会强迫所有
  语言迁就同一种语序）；组内消息和系统提示按时间混排展示；每条消息同时
  显示原文和译文，语言不同时追加 `[源语言→目标语言]` 标签（语言代码
  按原始格式显示，如 "zh_cn"，不做本地化名称转换）。
- **第三者能否算"加入对话"完全由服务端下发的数据决定**：服务端只会把
  `ConversationRosterPayload` 发给"当时确实能收到这条对话消息的人"，
  所以 A 看不到 Alex、B 看得到 Alex 时，A 的历史记录里天然不会出现
  "Alex 加入了对话"——这是客户端完全被动接收数据的自然结果，不需要
  客户端做任何额外判断（应用户明确要求的判定基准）。

**3. 聊天栏（VISIBLE）显示原文独立开关**
- 新增 `MCCFConfig.showOriginalTextInChat`（默认关闭），与原有的
  `showOriginalText`（现更名注释为"控制 AUDIBLE 物品栏字幕"）分开维护——
  管理员可能只想让聊天栏显示原文，不想让物品栏字幕也变长。
  `SpatialChatHandler#dispatchTo` 按 `displayMode` 选用对应的开关。
- 客户端 `MCCFClient` 的 VISIBLE 分支：`originalText` 非空时（服务端开启
  了这个开关）展示两行——第一行 `<名字> 原文`，第二行灰色 `⇄ 译文`，
  格式模仿纯客户端模式 `ClientOnlyChatTranslator` 的追加样式（应用户
  明确要求"模仿一下客户端模式的那个字幕"）；为空时维持原来"仅译文一行"
  的格式，不产生视觉差异回归。
- `ServerConfigPanel` 新增两个并排的 `CyclingButtonWidget<Boolean>`
  开关（"字幕显示原文"/"聊天栏显示原文"），`ClientConfigState`/
  `ConfigSyncHandler` 相应新增这两个字段的快照读取与更新提交逻辑。

**4. 元信息补全**
- `fabric.mod.json`：`authors` 从占位符 `"You"` 改为 `"LimAimo"`；
  `contact.homepage`/`contact.issues`/`contact.sources` 从占位的
  `example.com` 改为指向 GitHub 仓库
  （`VirTuaL-EnTitY/MC-Conversation-Framework-1.21.1`）及其 Issues 页——
  ModMenu 的"网站"/"问题"按钮直接读取这两个字段渲染，之前因为字段缺失/
  占位而没有实际作用。

版本号 `0.11.0` → `0.12.0`（按 9.1 规则升 minor：网络协议扩展 + 重大功能
新增，非破坏性——旧客户端连接新服务端会因为 `SubtitlePayload` 字段数变化
导致反序列化失败，这是预期的行为，MCCF 客户端和服务端本来就要求版本匹配）。

**已知限制**：
- `SubtitlePayload` 协议改动意味着这个版本的客户端和服务端必须同时更新
  才能正常通信——字段数变化会导致新旧版本之间的 `PacketCodec` 解码不兼容。
  这不是新引入的限制（项目此前也没有做过跨版本协议兼容层），但值得记录：
  以后每次修改这类核心 payload 的字段结构，都应该视为"不兼容变更"对待。
- 语言标签使用原始语言代码（如 "zh_cn"），未做本地化名称转换（如显示
  "中文" 而非 "zh_cn"）——应用户明确要求保持简单，不引入额外的语言名称
  映射维护成本。

### 2026-07-30　0.11.0 提示文字迁移到左下角空白区 + 修复两处误导性状态显示（未连接检测行、无限期加载中）

响应用户两轮反馈：(1) 提示文字挪到左侧 Provider 列表下方原本空置的区域；
(2) 在此基础上暴露的两个真实问题——`LocalConfigPanel` 的服务器检测结果在
没进入任何世界时也会显示，`ServerConfigPanel` 在服务器没装 MCCF 时会
无限期卡在"正在加载配置"。

**1. 提示文字布局：从屏幕底部居中改为左侧列表正下方左对齐**
- 新增 `ProviderConfigPanel#renderLeftBottomHints`（父类方法，两个子类共用）：
  接收若干行 `HintLine`（文字+颜色），在左侧 Provider 列表正下方左对齐、
  从上往下排列，按 `LIST_WIDTH`（200px）自动换行——这个宽度比屏幕窄得多，
  换行处理是必需的（原来"屏幕居中"版本可以假设一行能放下，这次不能）。
- 返回值是"最后一行绘制完毕后的下一个可用 y 坐标"，供调用方在提示文字
  之后紧接着放置其他元素（用于下面第 3 点的"重试"按钮定位），避免用
  像素级硬编码"假设提示文字占几行"（换行行数是动态的，不同语言、不同
  Provider 的文案长度不同，硬编码位置几乎肯定会出错）。
- `MCCFConfigScreen.BOTTOM_HINT_AREA_HEIGHT` 从 54px 扩大到 100px，按
  "Provider 说明最多 2 行 + 状态/超时提示最多 2 行 + 重试按钮 24px"的
  最坏情况估算预留。

**2.（真实 bug 修复）LocalConfigPanel 检测结果行在未连接时也显示**
- 根因：`ClientOnlyModeManager.serverHasMod` 只在真正 `onJoinServer()` 时
  才被赋值为探测结果，未连接任何服务器时保持默认的 `false`——这跟"连接了
  但服务器确实没装"是同一个 `false`，界面上完全无法区分，于是主菜单/单机
  存档下也会显示"服务器未检测到 MCCF"这种带有误导性的提示。
- 修复：`LocalConfigPanel.renderExtra` 用 `MinecraftClient.getInstance()
  .player != null` 判断"当前是否处于某个世界中"（Minecraft 客户端判断
  "是否在游戏内"的标准方式，单人存档和联机服务器通用），未在任何世界中时
  这一行整行不显示（空文本会被 `renderLeftBottomHints` 自动跳过），不需要
  改动 `ClientOnlyModeManager` 本身的检测逻辑。

**3.（真实 bug 修复）ServerConfigPanel 服务器未装 MCCF 时无限期显示"正在加载"**
- 根因：`hasReceivedSnapshot` 只有收到服务端快照回包才会变 `true`，服务器
  没装 MCCF 时——能连接、能发送 `RequestConfigPayload`，但服务端不认识
  这个通道、永远不会回应——这个字段永远停在 `false`，界面因此永远显示
  "正在加载配置"，没有任何超时或失败提示。
- 修复：新增 `snapshotRequestedAtMillis`（请求发出时刻）和
  `SNAPSHOT_TIMEOUT_MS`（5000ms）。三态判断替代原来的二态逻辑：
  1) 从未发送过请求（未连接任何世界）——不显示任何加载/超时提示，留空；
  2) 已发送、未超时——正常显示"加载中"；
  3) 已发送、超过 5 秒仍未收到回包——判定为服务器未安装 MCCF 或无法连接，
     改为红色提示文字 + 显示"重试"按钮。
- 新增"重试"按钮（`retryButton`）：默认不可见不可交互（双重保险），只在
  超时状态下出现，位置紧跟在提示文字下方（用 `renderLeftBottomHints` 的
  返回值 + `ClickableWidget#setPosition` 每帧动态定位，而不是固定坐标——
  原因同上，换行行数不固定）。点击后重新调用 `requestSnapshot()`，
  重置超时计时。
- 新增翻译键 `mccf.config.not_installed`（超时提示文案）和
  `mccf.config.retry`（按钮文案），9 种语言均已补全。

**4.（代码清理）移除两处死代码**
- `ServerConfigPanel`/`LocalConfigPanel` 的 `renderExtra` 开头原来各有一句
  `if (!tabVisible) return;`——经排查这句永远不会执行到：
  `MCCFConfigScreen.render()` 只在 `activeTab` 匹配当前 Panel 时才会调用
  它的 `render()`/`renderExtra()`，非活动标签页的 `renderExtra` 根本不会
  被调用，`tabVisible` 在这个方法体内恒为 `true`。清理这两句死代码，
  并在两个类的 `renderExtra` 开头加注释说明原因，避免以后又被人以为这个
  判断有意义而依赖它。`ServerConfigPanel` 的 `retryButton` 可见性重置
  改为在 `onTabVisibilityChanged`（真正会在切走标签页时触发的回调）里
  处理，而不是放在不会被调用的 `renderExtra` 分支里。

版本号 `0.10.0` → `0.11.0`（按 9.1 规则升 minor：两处真实 bug 修复 + UI 改动）。

### 2026-07-30　0.10.0 修复：Provider 列表 DeepSeek 消失（滚动缺失）+ 移除"设为默认"按钮，改为保存即生效

**1.（真实 bug 修复）左侧 Provider 列表 DeepSeek 消失**
- 根因：`ProviderListWidget` 是纯静态渲染，8 个 Provider × 20px 行高需要
  160px 总高度，但渲染逻辑里 `y + ENTRY_HEIGHT > maxY` 会直接 `break` 掉
  超出可视区域的条目，完全没有滚动能力。一旦分配给列表的高度小于 160px
  （常见于较小分辨率，或者上一版本改动为底部提示文字预留空间后进一步压缩了
  可用高度），排在数组末尾的 Provider（`deepseek` 排第 7、`ollama` 排第 8）
  就会被直接截断、玩家完全看不到也点不到——这正是用户反馈的现象。
- 修复：`ProviderListWidget` 新增 `scrollOffset` 状态和 `mouseScrolled` 处理，
  支持鼠标滚轮滚动；渲染时用 `DrawContext.enableScissor`/`disableScissor`
  裁剪到列表自身可视矩形，避免滚动后条目画到列表框外；内容总高度超出可视
  区域时在右侧画一条简易滚动条（纯视觉提示，不支持拖拽——条目数量不多，
  滚轮足够，没必要再实现完整的拖拽交互）。`onClick` 的点击判定同步改为
  基于滚动后的实际行位置计算，且要求命中"实际可视范围内"的部分（滚动到
  一半、某行只露出一半时，点击被裁剪掉的那一半不会误触发选中）。

**2.（交互改造）移除"设为默认"/"保存并启用"按钮，选中 Provider 后点保存即代表设为默认**
- 原设计（见 0.6.0 记录）：左侧列表点击只切换"选中查看"，需要额外点一次
  "保存并启用"/"设为本地默认"按钮才会把当前查看的 Provider 记为待启用目标，
  普通"保存"只保存字段改动、不切换 activeProvider——这是有意设计的两步分离，
  但用户反馈这个流程不必要，多了一步操作。
- 新设计：`ServerConfigPanel` 和 `LocalConfigPanel` 都移除了 `activateButton`
  / `onActivate()`，改为在 `onSave()`（`ServerConfigPanel`）/
  `performSave()`（`LocalConfigPanel`）里直接把当前查看的 `selectedProvider`
  同时写入待启用目标（`state.pendingActiveProvider` /
  `config.activeProvider`）——点击左侧列表仍然只是切换查看，但点"保存"这
  一个动作现在同时完成"保存字段改动"和"设为默认"两件事，不再需要额外的
  确认步骤。
- 两个面板的控件行数各少了一行，`buildRightPanel` 里的动态间距公式分母
  相应调整（`ServerConfigPanel`：5→4；`LocalConfigPanel`：6→5），其余控件
  间距更宽松。`initialSelectedProvider()` 也相应改为直接读
  `state.activeProvider`/`config.activeProvider`（原先读的是
  `pendingActiveProvider`，现在这个字段只在提交时临时赋值，不再有独立于
  `activeProvider` 的语义）。
- 移除了不再使用的翻译键 `mccf.config.activate` / `.activate.current` /
  `.activate_pending`（9 种语言）。

版本号 `0.9.0` → `0.10.0`（按 9.1 规则升 minor：真实 bug 修复 + 交互改造）。

### 2026-07-30　0.9.0 优化：配置界面提示文字位置 + 修复潜在的文字/控件重叠

响应用户反馈"提示文字位置不合理"，重新梳理了 `ServerConfigPanel` /
`LocalConfigPanel` 底部提示文字的排布，顺带修了一个原设计就存在但一直没
被处理的潜在重叠问题。

**1.（真实 bug 修复）控件区与底部提示文字共用同一条基准线，行数一多会重叠**
- 根因：`MCCFConfigScreen.init()` 里 `contentBottom = this.height - MARGIN`，
  这个值同时作为"右侧设置区最后一行控件的下边界"（传给 Panel 的 `bottom`
  参数）和"底部提示文字区域"的坐标基准（Panel 的 `renderExtra` 里
  `screenBottom = screen.height - 20`，与 `contentBottom` 数值相同）——
  一个从上往下排列控件，一个从下往上排列文字，行数一多（`LocalConfigPanel`
  最多时是"Provider 说明 + 强制服务器模式警告（可能换行）+ 检测状态 +
  操作状态消息"四行）就会在中间区域视觉重叠。
- 修复：新增 `MCCFConfigScreen.BOTTOM_HINT_AREA_HEIGHT`（54px，按最多 3 行
  提示文字 × 18px 行距预留），`contentBottom` 计算改为
  `this.height - MARGIN - BOTTOM_HINT_AREA_HEIGHT`，让控件区和提示文字区
  拥有独立的、不重叠的空间。两个标签页统一按这个值预留（即使
  `ServerConfigPanel` 只用得上 2 行），保证切换标签页时控件区下边界位置
  一致，不会有界面"跳动"的感觉。

**2. 提示文字间距拉开**
- `ServerConfigPanel` 和 `LocalConfigPanel` 的底部提示文字统一改为 18px
  固定行距（原来是 12～16px 不等，多行时显得拥挤），两个面板保持一致的
  视觉风格。

**3.（交互改造）"强制服务器模式但未检测到服务器"从常驻警告文字改成拦截式确认弹窗**
- 原设计：这条警告是一段常驻渲染在 `LocalConfigPanel` 底部的红色文字，
  动态换行、行数不固定，容易被忽略，也是上面提到的重叠问题的主要诱因之一
  （换行行数不确定，没法在排布其他行时预留出准确空间）。
- 新设计：只在玩家点击"保存"、且当前选择结算为"强制服务器模式"、且
  客户端确实检测不到服务器已安装 MCCF 时，弹出 Minecraft 原版风格的
  `ConfirmScreen`（与"连接不受信任服务器"用的是同一种确认弹窗），标题+
  正文说明后果，玩家需要点"是"才会真正执行保存，点"否"或直接关闭弹窗
  则取消本次保存、已填写的其他字段（API Key、模型名等）原样保留在界面上。
  这不是"仅供参考"的风险提示，而是"确定会出问题"的操作后果，弹窗拦截
  比常驻文字更能确保玩家真的看到并做出选择。
- `ConfirmScreen` 用的是最基础的 3 参数构造函数
  （`BooleanConsumer, Text, Text`），按钮固定显示原版"是/否"文案——
  1.21.1 环境下没有可核对的本地反编译源码，`ConfirmScreen` 支持自定义
  按钮文字的重载在不同 Minecraft 版本间签名有过变化，贸然使用有编译
  失败风险，故未采用；语言文件里为此预留过 `warn_force_server_proceed`/
  `warn_force_server_cancel` 两个键，因为改用基础版本没有用上而移除。
- 弹窗正文复用已有的 `mccf.localconfig.warn_force_server_no_mod` 翻译键
  （原本就是常驻警告文字的内容，文案本身适合直接作为弹窗正文，未改动），
  新增 `mccf.localconfig.warn_force_server_title`（弹窗标题），9 种语言
  均已补全。

版本号 `0.8.0` → `0.9.0`（按 9.1 规则升 minor：功能性修复 + 交互改造）。

### 2026-07-30　0.8.0 新增：英文 README + GitHub Actions 自动发布工作流

面向开源发布的准备工作：项目要发布到 GitHub 并上传 CurseForge / Modrinth，
需要一份英语玩家能看懂的说明，以及打 tag 后自动编译打包发布的 CI 流程。

**1. 中文 README 顶部新增面向玩家的介绍板块**
- 原 README 开头直接是技术术语堆砌（"空间化听觉判定"、"可插拔翻译
  Provider"……），普通玩家大概率看不下去。新增"这是什么？能给我带来什么
  好处？"和"怎么使用？"两节，用大白话说明模组解决什么问题、有什么亮点、
  怎么下载安装配置，原技术内容整体下移到"技术简介"小节，不做任何删减。
- 顺手修正了一处过时的硬编码版本号（`构建产物在 build/libs/
  MCConversationFramework-0.2.0.jar` 早就不是当前版本号了），改为引用
  `gradle.properties` 里的 `mod_version`，避免以后每次发版都要手动改这一处。

**2. 新增英文 README（`README_EN.md`）**
- 不是逐行对照翻译（1000+ 行的技术细节、开发过程记录、注释规范这些内容
  对英语玩家实际帮助有限，逐行翻译且长期双语同步维护的成本也过高，容易
  两份文档越改越不一致）。而是聚焦"这是什么 / 有什么好处 / 怎么用 / 几条
  必须知道的注意事项"这个玩家最需要的部分，完整认真地写（不是中文版的
  缩水版），末尾链接回中文版作为完整技术参考。
- 中英两个 README 顶部互相有跳转链接（当前语言用加粗标出，另一语言是链接）。

**3. 新增 GitHub Actions 工作流（`.github/workflows/release.yml`）**
- 触发策略：push 到 `main` 只编译 + 跑测试（验证代码没坏，不发布）；
  push 一个 `v*.*.*` 格式的 tag（如 `v0.8.0`）才会编译 + 打包 + 自动创建
  GitHub Release 并附带 jar；也支持 `workflow_dispatch` 手动触发（用于调试
  workflow 本身，行为等同于 push 到 main，只编译不发布）。
- 两个 job：`build`（编译 + 跑 `RateLimiterTest` 等单元测试 + 定位并上传
  构建产物）和 `release`（仅 tag 触发，下载构建产物、校验 tag 版本号与
  `gradle.properties` 里的 `mod_version` 一致、提取对应版本的更新日志、
  创建 Release）。
- 新增 `.github/scripts/extract_changelog.py`：从本 README 的"八、更新
  日志"章节里，按版本号精确匹配对应的 `### YYYY-MM-DD　<版本号> ...`
  标题行，截取到下一条日志之前的内容，作为 Release Notes 自动填充。找不到
  对应版本号时会让 workflow 显式失败（而不是发布一个空描述的 Release），
  提醒开发者：**打 tag 前必须先在 README 更新日志里补好对应版本号的条目**，
  这是本次新增的一条硬性前置步骤，不遵守会导致 Release 发布失败。
- CurseForge / Modrinth 的自动上传本次**没有**加入 workflow——这两个平台
  目前是手动上传，工作流只负责 GitHub Release 这一部分。

版本号 `0.7.0` → `0.8.0`（按 9.1 规则升 minor：新增项目基础设施——CI 工作流
和面向发布的文档，参照 0.1.0→0.2.0 那次"JAR 命名 + 版本号策略"的先例，
非代码逻辑但属于新增基础设施，同样记为 minor 而非 patch）。

### 2026-07-30　0.7.0 修复：聊天历史界面行高 bug（一句话占满屏）+ 新增对话分组

**问题 1（严重 bug）：聊天历史界面一条消息占满整个屏幕**
- 根因：1.21.1 的 `AlwaysSelectedEntryListWidget` / `EntryListWidget` 构造函数签名是
  `(client, width, height, y, itemHeight)`（yarn 1.21.1+build.3 实测 + 在线 javadoc 核对），
  第 5 参数是 **itemHeight（行高）**，不是 bottom。但 `ChatHistoryScreen` 与
  `ModelSelectionScreen` 误以为是老版本 `(client, width, height, top, bottom)`，把
  `this.height - 40`（本应是 bottom）传成了 itemHeight，导致每条记录行高 = 整个列表
  区域高度，一屏只能看到一条消息——这正是用户反馈"一句话占用整个界面"的根因。
- 这是项目早期"1.21.1 itemHeight 不可配置"这一误判的延续：反编译 jar 只能看到参数类型
  `(client, int, int, int, int)` 看不到参数名，叠加"1.20.x 是 6 参数 (top,bottom,itemHeight)"
  的先入为主，把第 5 参数想成了 bottom。实际上 itemHeight 一直可配置，只是被误用了。
- 修复：`HistoryListWidget` / `ModelListWidget` 构造函数保留 top/bottom 语义方便传入，
  内部换算成 `super(client, width, bottom-top, top, itemHeight)`。行高设 12px（单行 9px
  + 上下 padding），一屏可显示十几条。
- 同时纠正 `ProviderListWidget`、`ChatHistoryScreen`、`ModelSelectionScreen` 注释及本
  README 中 4 处关于"itemHeight 固定 36px 不可配置"的错误陈述（见 9.2.5 修正）。

**问题 2（功能）：历史界面新增"对话分组"显示谁和谁的聊天**
- 消息按时间间隔聚类成对话组（相邻间隔超 30 秒视为新对话），每组顶部显示参与者标题：
  多人参与显示"A、B、C 的对话"（绿色 + 浅绿背景），单人连续发言显示"X 的自言自语"
  （灰色 + 浅灰背景）。
- 分组纯客户端按时间推断，不依赖服务端 Conversation——纯客户端模式没有服务端分组
  信息可用，统一用时间聚类避免两套数据源/两套渲染逻辑。代价是边界不够精确（两个
  间隔不足 30 秒的独立对话会被合并、一个沉默超 30 秒的长对话会被拆开），对"回看个
  大概"可接受，不满意可调 `GROUP_GAP_MS`。
- 涉及文件：`ChatHistoryScreen.java`（行高修正 + 分组渲染 + 新增 `GroupHeaderWidget`）、
  `ModelSelectionScreen.java`（行高修正）、`ProviderListWidget.java`（注释勘误）。
- **版本号**：含 bug 修复 + 新功能，按 9.1 规则升 minor：`0.6.2` → `0.7.0`。

### 2026-07-30　0.6.2 新增：RateLimiter 单元测试 + 限流逻辑抽取

把 `ClientOnlyChatTranslator` 里的固定窗口限流逻辑抽取到独立的 `RateLimiter` 类
（`net.mccf.mod.util.RateLimiter`），并写了 JUnit 5 单元测试覆盖以下场景：

1. **单线程基本限流**：窗口内前 N 条放行，超出拒绝
2. **窗口过期重置**：等待窗口过期后重新放行
3. **并发竞争**：50 个线程同时调用，放行数不超过 maxRequests（核心断言——
   检测 synchronized/双检查是否有并发缺陷）
4. **构造器参数校验**：非正数参数抛 IllegalArgumentException
5. **currentCount 准确性**：被拒绝的请求也计入计数

为什么只测限流不测缓存：`TranslationService` 的缓存逻辑需要 mock
`TranslationProvider` + `WorldDictionary`，mock 代码量可能超过测试价值。
限流逻辑是纯并发控制、不依赖任何 MC 类，抽取后可以直接测，收益比最高。
并发竞争场景（50 线程同时调用）是手动测试几乎无法覆盖的——如果 synchronized/
双检查有缺陷，这个测试会在 CI 里立刻报警。

新增文件：
- `src/main/java/net/mccf/mod/util/RateLimiter.java` — 限流器
- `src/test/java/net/mccf/mod/util/RateLimiterTest.java` — 单元测试

修改文件：
- `ClientOnlyChatTranslator.java` — 移除内部限流逻辑，改用 `RateLimiter` 实例
- `build.gradle` — 加 JUnit 5 依赖 + `test { useJUnitPlatform() }`

运行测试：`gradle test`（不需要进游戏，不需要 Minecraft 运行时）

版本号 `0.6.1` → `0.6.2`（minor：新增测试基础设施 + 重构）。

### 2026-07-30　0.6.1 新增：暂停菜单入口 + 修复聊天历史记录找不到入口

聊天历史记录界面（0.5.0 引入）之前只有快捷键入口（默认未绑定），玩家在游戏内
看不到任何可见按钮，反馈"找不到入口"。本次新增暂停菜单按钮入口：

- 用 Fabric API 的 `ScreenEvents.afterInit` 事件在 `GameMenuScreen`（Esc 暂停菜单）
  初始化后动态追加"聊天历史记录"按钮，点击即打开 `ChatHistoryScreen`。
- 不用 Mixin——ScreenEvents 已足够，Mixin 为加一个按钮属于杀鸡用牛刀。
- 按钮位置在原版按钮组下方，宽度 204（跨两列），不和原版布局冲突。
- 快捷键入口（`key.mccf.open_history`，默认未绑定）保留，两种方式等价。
- 新增翻译键 `mccf.history.button`，9 种语言全部补齐。

版本号 `0.6.0` → `0.6.1`（minor：新增入口，虽然改动小但属于功能新增）。

### 2026-07-30　0.6.0 新增：纯客户端本地设置面板的"获取模型列表"功能

之前只有"服务端配置"标签页有"获取模型列表"按钮（走服务端中转），纯客户端模式
下玩家主要用的"本地设置"标签页没有这个按钮——用户反馈"看不到任何一个一键获取
模型的按钮"，因为纯客户端模式下服务端可能没装 MCCF，服务端面板的按钮即使存在
也发不出请求。

本次改动：

1. **`LocalConfigPanel` 新增"获取模型"按钮**：点击后客户端直接构造 Provider 调用
   `listModels()` 发 HTTP 请求拉取模型列表，不经过服务端中转。用输入框里当前填的
   apiKey/endpoint（可能还没保存）构造一次性 Provider，方便"填完 Key 立刻测一下"。
   拉取成功后弹出独立的 `ModelSelectionScreen` 列表界面供选择，点击条目即应用。

2. **`ModelSelectionScreen` 解耦 `ClientConfigState`**：原来这个 Screen 硬编码从
   `ClientConfigState` 读写模型字段，导致用 `ClientOnlyTranslationConfig` 的本地面板
   无法复用。改造后通过 `currentModel` 参数 + `selectionCallback` 回调传值，不耦合
   任何配置类，两个面板都能用。

3. **`ServerConfigPanel` 适配**：调用 `ModelSelectionScreen` 时传入
   `state.getOrCreate(providerId).model` 作为 `currentModel`，功能不变。

版本号 `0.5.1` → `0.6.0`（minor：新增功能）。

### 2026-07-29　0.5.1 修复：补齐配置界面布局改动后遗漏的翻译键

0.5.0 的配置界面改版（标签页布局 + 聊天历史记录界面 + "设为默认"按钮）新增了
13 个 `Text.translatable()` 调用，但对应语言键没有同步补进 lang 文件，导致
所有 9 种语言下这些位置都显示为原始键名（如 `mccf.config.tab.server`）。

补齐的键（全部 9 种语言 en_us/zh_cn/zh_tw/ja_jp/ko_kr/es_es/fr_fr/de_de/ru_ru）：

| 键 | 用途 |
|----|------|
| `mccf.config.tab.server` / `.local` | 顶部标签页"服务端配置"/"本地设置" |
| `mccf.config.activate` / `.activate.current` / `.activate_pending` | "设为默认"按钮的三种状态文字 |
| `mccf.history.title` / `.close` / `.empty` | 聊天历史记录界面的标题/关闭按钮/空列表提示 |
| `mccf.history.source.self` / `.visible` / `.audible` / `.client_only` | 历史记录每行的来源标签 |
| `key.mccf.open_history` | 打开聊天历史记录的按键绑定名称 |

版本号 `0.5.0` → `0.5.1`（patch：纯 lang 文件补漏，不改代码逻辑）。

### 2026-07-29　0.5.0 修复：说话者收不到自己消息的回显 + Mock Provider 醒目警告 + 新增聊天历史记录

本轮改动响应用户两类反馈：(1) 纯客户端模式默认 Provider 是 Mock（占位符），
玩家容易误以为翻译没生效；(2) VISIBLE 降级为聊天框后，说话者自己看不到自己
发的消息（AUDIBLE 情况同理）。

**1. 说话者自己消息回显修复（核心问题）**
- **问题**：`SpatialChatHandler` 计算候选听众时会排除说话者本人
  （`!p.getUuid().equals(sender.getUuid())`），且拦截了原版聊天广播，导致
  说话者永远收不到自己刚发消息的任何回显——无论 VISIBLE（聊天框）还是
  AUDIBLE（物品栏字幕），自己都看不到自己说了什么。
- **修复**：新增 `SpatialChatHandler#dispatchSelfEcho`，独立于候选听众列表，
  始终给说话者本人发一份不经过翻译的原文回显（复用 `SubtitlePayload`，
  `originalText`/`translatedText` 均为原文）。`displayMode` 跟随"本次发言时
  其他听众的主导模式"：VISIBLE 听众更多时回显走聊天框，AUDIBLE 听众更多时
  回显走物品栏字幕，没有任何听众时默认 VISIBLE（聊天框更保险，不会一闪而过）。
- 客户端 `MCCFClient` 新增 `isSelf` 判定分支（`payload.speakerId()` 等于本机
  玩家 UUID），按 `displayMode` 分流到 `addVisibleToChatHud`（聊天框，原版
  `<名字> 原文` 格式）或 `SubtitleManager.onReceive`（物品栏字幕）。
- 涉及文件：`SpatialChatHandler.java`、`MCCFClient.java`。

**2. Mock Provider 醒目警告**
- Mock Provider 只是给原文加 `[语言代码]` 前缀的占位符，不调用任何真实翻译
  API——这是纯客户端模式和服务端配置的**默认值**，玩家装完模组直接体验很
  容易把这个占位效果误认成"翻译没生效"（真实反馈案例）。
- 两个配置界面（`MCCFConfigScreen`、`ClientOnlyConfigScreen`）在选中 Mock
  Provider 时，于 Provider 说明文字下方追加一行红色警告
  （`mccf.config.mock_warning`），不进聊天栏、不做强制弹窗，只在配置界面内
  静态提示。为容纳这行新警告，两个界面的 `providerButton`→`apiKeyField`
  间距从 12px（`ClientOnlyConfigScreen`）/ 0px 额外间距（`MCCFConfigScreen`）
  统一扩大到 24px，避免文字与输入框重叠。
- 涉及文件：`MCCFConfigScreen.java`、`ClientOnlyConfigScreen.java`，9 种语言
  文件新增 `mccf.config.mock_warning` 键。

**3. 新增聊天历史记录界面**
- 新增 `ChatHistoryManager`（客户端内存环形缓冲区，容量 500 条，不落盘，
  断线清空）与 `ChatHistoryEntry`（记录说话者、原文、译文、来源分类
  SELF/VISIBLE/AUDIBLE/CLIENT_ONLY、时间戳）。
- 三个数据入口都接入历史记录写入：`MCCFClient` 的 `SubtitlePayload` 接收器
  （自己回显 SELF / VISIBLE / AUDIBLE 三分支）、`ClientOnlyChatTranslator`
  （纯客户端模式下自己发的消息 SELF、翻译完成后的 CLIENT_ONLY）。
- 新增 `ChatHistoryScreen`：只读、可滚动的历史记录列表，按时间倒序展示。
  每条记录压缩成单行 `[来源] 说话者: 原文 ⇄ 译文`，右侧对齐时间戳，超宽裁剪加省略号。
  > ⚠️ 此处原写"1.21.1 行高固定、不支持 itemHeight 自定义（1.21.8 才引入）"是误判：
  > 1.21.1 构造函数第 5 参数本就是 itemHeight，当时误当 bottom 用导致行高=列表高度。
  > 已于 0.7.0 修正，详见该条目与 9.2.5。
- 两个入口：主配置界面新增"聊天历史记录"按钮（`mccf.config.chat_history`），
  以及独立按键绑定 `key.mccf.open_history`（默认未绑定，同 `openConfigKey`
  的约定，需要玩家自己在按键设置里指定）。两个入口都不受 op 权限限制——
  历史记录是纯本地展示数据。
- 涉及新文件：`ChatHistoryEntry.java`、`ChatHistoryManager.java`、
  `ChatHistoryScreen.java`。涉及修改：`MCCFClient.java`（接入写入 + 按键绑定）、
  `ClientOnlyChatTranslator.java`（接入写入）、`MCCFConfigScreen.java`
  （新增入口按钮），9 种语言文件新增 `mccf.config.chat_history` /
  `mccf.history.*` / `key.mccf.open_history` 键。

**改动文件汇总**：`SpatialChatHandler.java`、`MCCFClient.java`、
`ClientOnlyChatTranslator.java`、`MCCFConfigScreen.java`、
`ClientOnlyConfigScreen.java`、新增 `ChatHistoryEntry.java` /
`ChatHistoryManager.java` / `ChatHistoryScreen.java`，9 种语言文件，
`gradle.properties`（`0.4.0` → `0.5.0`）。

版本号 `0.4.0` → `0.5.0`（按 9.1 规则升 minor：新增网络分发分支
`dispatchSelfEcho`、新增客户端界面与按键绑定，均为功能性新增/修复，
无破坏性变更）。

**已知限制**：
- 历史记录不落盘，重进游戏/断线重连后清空——这是有意为之的取舍（见
  `ChatHistoryManager` 类注释），不是遗漏。
- 纯客户端模式（`ClientOnlyChatTranslator`）下的 CLIENT_ONLY 历史记录条目
  没有可靠的 `speakerName`（这条路径没有服务端下发的说话者展示名，只有聊天
  原文），历史界面对这类条目显示 `?` 占位。

### 2026-07-29　0.4.0 修复：VISIBLE 模式字幕不显示，临时改回聊天框

**问题**：能看见对方（VISIBLE 模式）时，字幕不会显示在玩家模型旁边。早期记录
的"alpha 通道修复"（`0xFFFFFF` → `0xFFFFFFFF`）实测**无效**——字幕仍不显示，
说明 alpha 不是（或不是唯一的）根因。WorldSubtitleRenderer 的世界空间渲染存在
未定位的底层问题（候选根因见下），暂不具备运行环境实测确认。

**临时方案**（本轮改动）：
- VISIBLE 模式（看得到说话者）的消息**改走原版聊天框**，格式为 `<Steve> 译文`
  （仅译文一行，翻译由服务端已完成）。因为服务端已按距离/视线把听众拆成
  visible / audibleOnly 两批、只给看得到的人发 VISIBLE 包，所以聊天框里天然只
  出现"我看得见的那几位"说的话——满足"聊天框内容只能是我看到的这几位"。
- AUDIBLE 模式（看不到对方）**完全不变**：依旧走 `HotbarSubtitleRenderer`
  在物品栏上方堆叠显示字幕，原时长（2.5s + 60ms/字符，上限 8s）保持不变，
  对应用户"看不到的情况下还是按照原来的时间来"。
- `WorldSubtitleRenderer.java` 代码**保留不删**，类 Javadoc 顶部诚实标注了
  "当前不显示 + alpha 修复无效 + 根因未定位"及候选根因，待后续具备运行环境时
  定位修复后再决定是否切回世界空间字幕。

**WorldSubtitleRenderer 根因候选**（均未实测确认，按怀疑程度排序）：
1. `findEntity` 按 UUID 在 `client.world.getPlayers()` 匹配说话者实体，若匹配失败
   会直接跳过、什么都不画（理论上游程 UUID 应一致，但未实测过）。
2. `WorldRenderEvents.AFTER_ENTITIES` 阶段的 `context.consumers()` 是世界渲染管线
   的 Immediate，其 SEE_THROUGH 层缓冲可能在帧末统一 flush；若被其他渲染阶段提前
   `end()` 或深度/混合状态异常，文字可能被画了但肉眼不可见。
3. 负 Y 缩放 `matrices.scale(-scale,-scale,scale)` + `camera.getRotation()` 的
   billboard 组合在某些视角下可能被背面剔除或被深度遮挡。

**改动文件**：`MCCFClient.java`（VISIBLE 路由到聊天框 + 新增
`addVisibleToChatHud`）、`WorldSubtitleRenderer.java`（类 Javadoc 标注根因候选）、
`gradle.properties`（`0.3.2` → `0.4.0`）。

版本号 `0.3.2` → `0.4.0`（按 9.1 规则升 minor：功能性行为变更，字幕渲染目标
从世界空间改为聊天框）。

### 2026-07-29　0.3.2 新增 7 种语言本地化

在原有 `en_us` / `zh_cn` 基础上，新增 7 种常用语言的完整本土化翻译：

| 语言文件 | 语言 | 本土化要点 |
|---------|------|-----------|
| `zh_tw.json` | 繁体中文（台湾） | 用台湾习惯用语：伺服器/設定/金鑰/匯出/除錯 |
| `ja_jp.json` | 日语 | Minecraft 日语官方风格：サーバー/設定/キーバインド/プロバイダー |
| `ko_kr.json` | 韩语 | 설정/서버/프로바이더/키 바인딩 |
| `es_es.json` | 西班牙语 | Proveedor/Clave API/Asignación de teclas |
| `fr_fr.json` | 法语 | Fournisseur/Clé API/Assignation des touches |
| `de_de.json` | 德语 | 保留英文术语 Provider/Endpoint/Log（德国 IT 社区习惯） |
| `ru_ru.json` | 俄语 | Провайдер/API-ключ/Назначение клавиш |

各语言均遵循该语言区的 Minecraft 官方菜单翻译习惯（如菜单路径
"Settings → Controls → Key Binds" 在日语为「設定 → 操作 → キーバインド」、
德语为「Einstellungen → Steuerung → Tastenbelegung」），避免机翻味道。
Provider 名称（OpenAI/Claude/Gemini 等）保留原名，括号里的公司名按
各语言习惯处理（中文保留公司中文名，其他语言统一用英文公司名）。

版本号 `0.3.1` → `0.3.2`（按 9.1 规则升 minor：新增功能，虽然只是资源文件）。

### 2026-07-29　0.3.1 编译错误修复：TextFieldWidget 密码遮盖 API + 注释 Unicode 转义

0.3.0 本地编译验证时发现两处编译错误，均为 API 适配遗漏：

1. **`TextFieldWidget.setRenderPasswordReveal(boolean)` 在 1.21.1 上不存在**：
   `MCCFConfigScreen.java` 和 `ClientOnlyConfigScreen.java` 用了这个方法开启
   API Key 输入框的密码遮盖。该方法在 1.20.x 及之前存在，1.21.1 已移除。
   改为 `setRenderTextProvider(BiFunction)`，传入一个把字符替换成 `•`（U+2022）
   的函数实现同等效果。代价：失去原版"按住可短暂显示明文"的交互（本项目用不到）。
2. **`HttpProviderSupport.java` 注释里的 `\uXXXX` 触发"非法的 Unicode 逃逸"**：
   Java 编译器在词法分析阶段（早于注释识别）就会处理 `\uXXXX` 序列，注释里的
   `\uXXXX`（`X` 非法十六进制）会导致编译失败。改为 `U+XXXX` 形式。

两条踩坑已补入 9.2.5 版本兼容性踩坑汇总。版本号 `0.3.0` → `0.3.1`（patch）。

### 2026-07-29　0.3.0 重大更新：字幕位置改造 + 强制客户端模式修复 + 性能与稳定性优化

本轮更新基于 QA 审查报告，修复了多个功能与性能问题。版本号 `0.2.1` → `0.3.0`
（按 9.1 规则升 minor：涉及功能修复 + 新增网络包 + 渲染逻辑改造）。

**1. 字幕渲染位置改造（VISIBLE 模式）**
- 字幕从"说话者头顶上方一点"改为"说话者模型旁边、靠近相机的一侧"（腰部高度，
  水平偏移）。计算方式：取"说话者→相机"的水平方向向量并归一化，字幕位置 =
  实体位置 + 水平偏移 + 腰部高度偏移。相机几乎在说话者正上方时默认偏右侧。
- 长文本支持自动换行（按字符数估算宽度，超出时分行），避免单行过长遮挡画面。
- 远距离字幕有距离衰减（alpha 随距离降低），避免远处字幕干扰视线。
- 背景色统一为半透明黑色，保证不同亮度环境下可读性。
- 涉及文件：`WorldSubtitleRenderer.java`。

**2. 强制客户端模式 bug 修复（核心问题）**
- **问题**：玩家在配置界面设置了"强制纯客户端模式"后，如果服务器装了 MCCF，
  客户端仍按服务器模式运行（不翻译聊天栏），而不是只做本地翻译。
- **根因**：服务端 `SpatialChatHandler` 只看"服务器是否装了 MCCF"，不知道
  玩家的客户端模式偏好，依旧拦截原版聊天改发 `SubtitlePayload`，导致客户端
  收不到原版 CHAT 事件、`ClientOnlyChatTranslator` 不触发。
- **修复**：新增 `ModePreferencePayload`（C2S 网络包），客户端在加入服务器
  和切换模式时通过它通知服务端自己的模式偏好。服务端 `SpatialChatHandler`
  收到后，对 client-only 玩家跳过聊天拦截和字幕分发，让原版聊天广播通过，
  客户端 `ClientOnlyChatTranslator` 正常触发本地翻译。
- **退回方案**：旧服务端不认识 `ModePreferencePayload`（`canSend` 返回 false），
  客户端会从 `SubtitlePayload` 里提取原文走本地翻译，保证向下兼容。
- 新增文件：`ModePreferencePayload.java`、`ClientOnlyModeRegistry.java`。
- 涉及文件：`SpatialChatHandler.java`、`ClientOnlyModeManager.java`、
  `MCCFClient.java`、`ClientOnlyChatTranslator.java`、`MCCF.java`。

**3. 翻译服务缓存与稳定性优化**
- `TranslationService`：翻译失败的结果不再写入缓存（此前网络瞬断时失败结果被
  永久缓存，导致网络恢复后仍无法翻译）。
- 缓存改为 LRU 策略（`LinkedHashMap` access-order），最大 5000 条，超出自动
  淘汰最久未访问的条目。
- 缓存条目增加 TTL（1 小时），过期条目下次访问时自动删除。
- 涉及文件：`TranslationService.java`。

**4. ClientOnlyChatTranslator 优化**
- 抽取 `translateAndAppend` 方法复用翻译逻辑（CHAT 事件监听器和 SubtitlePayload
  退回方案两个调用点共用），避免重复代码。
- 新增 Provider 实例缓存（按 providerId 缓存，避免高频聊天时每条消息都 new
  Provider 短命对象）。
- 新增固定窗口限流（每秒最多 5 条翻译请求），超出丢弃并记警告日志，防止
  聊天刷屏时 API Key 被封。
- 涉及文件：`ClientOnlyChatTranslator.java`。

**5. 配置界面优化**
- `ModelSelectionScreen` 调用修复：此前模型选择界面未被正确调用（死代码），
  现在获取模型成功后会正确打开 `ModelSelectionScreen` 供玩家选择。
- `ClientOnlyConfigScreen` 新增强制服务器模式警告：当玩家选择"强制服务器模式"
  但服务器未检测到 MCCF 时，显示红色警告提示。
- 新增 Provider 提示文字（`mccf.config.provider_hint.*`），帮助玩家了解每个
  Provider 的配置要求。
- 涉及文件：`MCCFConfigScreen.java`、`ClientOnlyConfigScreen.java`。

**6. 首次加入提示**
- 客户端首次进入游戏世界时，在聊天栏显示一条提示（`mccf.tip.first_join`），
  引导玩家到"设置→控制→按键绑定"为 MCCF 绑定配置按键。整个客户端生命周期
  只提示一次，不随换服务器重置。
- 涉及文件：`MCCFClient.java`、`zh_cn.json`、`en_us.json`。

**7. 其他优化**
- `WorldDictionary`：缓存编译后的 `Pattern` 对象，词条变更时清除缓存，
  避免每次翻译都重新编译正则。
- `ProviderFactory`：未知 Provider ID 静默 fallback 到 Mock 时，新增
  `LOGGER.warn` 日志提示可能存在配置错误。
- `HttpProviderSupport`：用 Gson 替代手写 JSON 转义方法，提高可靠性。
- `LogExporter`：改为流式读取日志文件（逐行过滤），避免大日志文件 OOM。

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
> ⚠️ 此处对 5 参数构造器的语义判断有误：1.21.1 的 5 参数是
> `(client, width, height, y, itemHeight)`，第 5 参数是 itemHeight 不是 bottom。
> 当时误当 bottom 导致行高 bug，已于 0.7.0 修正，详见该条目与 9.2.5。

### 2026-07-28　记入注释风格规范 + 新增"已确认规则"章节
- 用户要求参考 Claude 的注释风格，把以下 6 条规则记入项目规则：
  1. 注释写"为什么"不写"是什么"（叙述性长句解释决策动机）。
  2. 把决策历史写进注释（谁反馈的、旧版本错在哪、为什么改成现在这样、
     保留为特性的部分显式声明"不再处理"）。
  3. 类级别声明职责边界（Javadoc 开头明确"只管 X，不管 Y"）。
  4. 对比论证（非显然的 API 选择说明为什么选 A 不选 B，包括 A 的代价）。
  5. 版本兼容性踩坑留在注释里（方便未来升级时少踩坑）。
  6. 诚实承认不确定性（没查清楚的直接写明"索性完全绕开"，不要假装完全理解了）。
- 同时要求 README 维护独立的"已确认规则"章节，和更新日志区分开：
  更新日志是按时间的事件流，规则是稳定的约束。
- **已完成**：
  - 规则同步记入项目记忆文件 `project_memory.md`（供 AI 跨会话记忆）。
  - README 新增"九、已确认规则"章节，包含 9.1（版本号与 README 更新规则）和
    9.2（注释风格规范，含 6 条子规则），并在 9.2.5 汇总了本项目已记入的 4 处
    版本兼容性踩坑（`BOOL`/`getTickDelta`/`HudRenderCallback`/`AlwaysSelectedEntryListWidget`）。
- **版本号**：本次属于工程规范重大变更（影响后续所有代码风格），按 9.1 规则
  升 minor：`0.2.1` → `0.3.0`。

### 2026-07-28　注释修正 + 版本号策略记入项目规则
- `HotbarSubtitleRenderer.java` 类注释里还残留 "由 MCCFClient 通过 `HudElementRegistry.addLast` 注册"
  的旧描述（上一轮 API 适配时漏改），更新为 "通过 `HudRenderCallback.EVENT.register` 注册
  （1.21.1 上用旧 API；1.21.6+ 才有 `HudElementRegistry.addLast`）"。
- 全项目扫描 `TODO/FIXME/XXX/HACK` 标记：无残留。
- 全项目扫描源码中的过时注释：仅上述 1 处，已修复。
- **记入项目规则**：以后每次完成功能或更改后，视情况更新 `gradle.properties` 的 `mod_version`
  并在 README 更新日志写明。版本号策略：patch=bug/注释修复，minor=功能/版本切换/API 适配，
  major=重大重写。
- 本次属于"只改注释"，按规则升 patch：`0.2.0` → `0.2.1`。

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
   > ⚠️ 此条认知有误：1.21.1 的 5 参数构造器第 5 参数就是 itemHeight（不是 bottom），
   > 上面"改为 5 参数 (client,w,h,top,bottom)"实际把 bottom 传给了 itemHeight，
   > 导致每条记录行高=列表高度。已于 0.7.0 修正为正确换算，详见该条目与 9.2.5。

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

---

## 九、已确认规则

> 本章节记录与团队/用户对齐过的**稳定规则**，供实现对照，避免后续改动时逻辑漂移。
> 和"八、更新日志"不同：更新日志是按时间的事件流（谁在什么时候改了什么），
> 本章节是稳定的约束（代码必须长期遵循的规范）。新规则记入这里之后，
> 只有在显式声明废弃时才能移除，不能因为某次改动"看起来不需要了"就悄悄省略。

### 9.1 版本号与 README 更新规则
- **触发条件**：每次完成一个功能或更改后，视情况更新项目版本号，并在 README 更新日志写明本次更改。
- **版本号位置**：`gradle.properties` 的 `mod_version` 字段。
- **版本号策略**（语义化版本）：
  - `patch`（x.y.**Z**）：小 bug 修复、注释修正、文档调整等不影响功能/构建的改动。
  - `minor`（x.**Y**.0）：新功能、版本切换（如 MC 版本适配）、API 适配修复、构建配置重大调整、工程规范重大变更。
  - `major`（**X**.0.0）：重大重写、架构变更。
- **README 更新位置**："八、更新日志"章节，按日期倒序新增条目。
- **"视情况"判断标准**：
  - 纯探索性查询、仅读不改 → 不更新版本号，可记可不记 README。
  - 改了源代码/构建配置/资源文件 → **必须**更新版本号 + 写 README 更新日志。
  - 只改了 README/注释 → 可只升 patch 并在 README 记一笔，也可不升版本号只记日志。
- **记入时间**：2026-07-28。

### 9.2 注释风格规范
本节的规则适用于**所有新写的代码注释和 Javadoc**，改老代码时如果碰到不符合规范的注释
也应该顺手修正。参考 Claude 的注释风格，核心原则：**注释的价值在于解释"为什么"，
而不是重复"是什么"**——代码本身已经说明了"是什么"。

#### 9.2.1 注释写"为什么"，不写"是什么"
- 用**叙述性长句**解释决策动机，而不是干巴巴描述代码行为。
- 反例：`// 重置血量`
- 正例：`// 玩家重生后必须显式 setHealth(20.0) 重置血量，因为死亡时残留的"当前血量"
  会被 RespawnScreen 复用到新角色身上；不能用 heal() 或治疗指令，那会触发 Regen
  效果的事件链，和"重生瞬间满血"的语义不符；这一步必须在 teleport(toSpawn)
  之前调用，否则复活点坐标更新后 PlayerEvent.Respawn 已经触发，血量会被
  respawn 逻辑二次覆盖。`

#### 9.2.2 把决策历史写进注释
- 遇到曾经改过的逻辑，注释里保留**决策脉络**：谁反馈的、旧版本错在哪、为什么改成现在这样。
- 团队决定**保留为特性**的部分（不是 bug），要显式声明
  "此行为经讨论后保留，不再处理"，防止后续接手的人当成 bug 再"修"一遍。
- 目的：让后人能还原决策过程，而不只是看到当前状态。

#### 9.2.3 类级别声明职责边界
- 每个**类的 Javadoc 开头**必须明确写："这个类只管 X，不管 Y（那是 Z 类的职责）"。
- 目的：防止越界改动。如果某个类的方法开始调用它"不该管"的东西，评审时一眼能看出来。
- 模板：
  ```java
  /**
   * <职责的一句话描述>
   *
   * 职责边界：
   * - 只管：X、Y
   * - 不管：A（那是 B 类的职责）、C（那是 D 类的职责）
   */
  ```

#### 9.2.4 对比论证：非显然的 API 选择必须说明
- 遇到非显然的 API 选择（比如 `setHealth` vs `damage()`、`teleport` vs 手动构造
  `TeleportTarget`），在注释里说明**为什么选 A 不选 B**，包括 A 的代价。
- 模板：`// 这里用 setHealth 而不是 damage()：damage 会触发 LivingEntityDamage 事件，
  被护甲/药水/模组拦截后扣血量不可控；setHealth 是直接写入，代价是绕过了所有
  "伤害处理"逻辑（包括死亡判断），所以调用前必须手动检查 currentHealth > 0。`
- 目的：避免后人"优化"代码时把 A 换成 B，触发当初已经踩过的坑。

#### 9.2.5 版本兼容性踩坑留在注释里
- 任何因为 MC / Fabric / Yarn 版本差异踩过的坑，都要在**对应代码位置**留注释。
- 格式：`// 1.21.1 的 DynamicRegistryManager 用 get() 而不是 getOrThrow()
  （后者是 1.21.5+ 才改的名字）。升级到 1.21.5+ 时这里要同步改名。`
- 已记入的版本踩坑（供查阅，代码位置也有对应注释）：
  - `PacketCodecs.BOOL`（1.21.1）↔ `PacketCodecs.BOOLEAN`（1.21.8+）——`RequestConfigPayload.java`
  - `RenderTickCounter.getTickDelta(boolean)`（1.21.1）↔ `getTickProgress(boolean)`（1.21.8+）——原 `WorldSubtitleRenderer.java`（0.16.0 已删除该文件，但这个版本差异知识点保留：未来若重新引入世界空间渲染会再次踩到）
  - `HudRenderCallback.EVENT.register`（1.21.1）↔ `HudElementRegistry.addLast`（1.21.6+）——`MCCFClient.java` / `HotbarSubtitleRenderer.java`
  - `AlwaysSelectedEntryListWidget` 构造器：1.21.1 是 5 参数 `(client, width, height, y, itemHeight)`（第 5 参数即 itemHeight），1.21.8+ 扩为 6 参数 `(client, width, height, y, bottom, itemHeight)`（重新加回 bottom）。**踩坑**：曾误判 1.21.1 第 5 参数为 bottom，把列表高度值传成 itemHeight 导致行高 bug（一句话占满屏），0.7.0 修正——`ModelSelectionScreen.java` / `ChatHistoryScreen.java`
  - `TextFieldWidget.setRenderTextProvider(BiFunction)`（1.21.1）替代已移除的 `setRenderPasswordReveal(boolean)`（1.20.x 及之前）——`MCCFConfigScreen.java` / `ClientOnlyConfigScreen.java`。1.21.1 没有"一行开启密码框"的开关，需自己传一个把字符替换成圆点的 `BiFunction`。
  - Java 源码注释中**不要写 `\uXXXX`**：Java 编译器在词法分析阶段（早于注释识别）就会处理 Unicode 转义，注释里的 `\uXXXX` 若 `X` 不是合法十六进制会报"非法的 Unicode 逃逸"。写 Unicode 码点请用 `U+XXXX` 形式——`HttpProviderSupport.java`。

#### 9.2.6 诚实承认不确定性
- 遇到没查清楚的底层行为，**直接写明**"没查清楚""索性完全绕开"，不要假装完全理解了。
- 模板：`// 清零 timeUntilRegen 仍不足以保证扣血生效，具体是哪个环节吞掉的没有查清楚
  （疑似 LivingEntity.damage 里有额外的 invulnerability 检查），索性完全绕开：
  用 setHealth 直接写入，不依赖 damage 管线。`
- 目的：让后人知道"这里还有未解之谜，不要盲目重构"，而不是误以为代码已经完全验证过。
