# ai-agent —— AI 代理（AI Agent）

`特殊:AI代理` / `Special:AiAgent`（别名 `Agent` / `AIAgent`）：让 AI **阅读词条 / 模板并起草编辑**，
代理只能 propose，**用户核对渲染预览与差异、点「确认提交」后才会保存**（以用户本人名义 + 隐藏水印），
**不进审核队列**。

> 对应 MediaWiki 的什么？**没有对应扩展**：这是 WikiKernel 自研的 AI 代理功能（内置多厂商适配器与
> 「只读工具 + 待确认提案」协议）；MediaWiki 侧只有 `AI`（WMF 的 AI 实验性功能）与 `mw.ai`（Scribunto
> 的 AI 库）这类零散能力，没有与本插件对等的「提案 → 人工确认 → 以本人名义保存」流程。

## 迁移范围（原 core 内置，已拆出）

| 迁移前进 core | 迁移后 |
| --- | --- |
| `ai/*`（16 个类：`AiAgentService` 会话编排 / 工具调用 / 提案、`AiAgentSession`、`AiMessage`、`AiProposal`、`AiHttp`、`AiProvider`、`AiProviderRegistry`、`AiVendorCatalog`、`OpenAiProvider`、`AnthropicProvider`、`GeminiProvider`、`MockProvider`、`Socks5Bridge`、`AiException`、`AiAgentException`、`AiKeyStore`） | 插件 `cc.thonly.wikikernel.plugin.aiagent.*`（`AiKeyStore` 的功能并入 core 的 `common.service.AiConfig` 实现） |
| `wikitext/MarkdownRenderer`（只被 AI 回复使用） | 插件 `MarkdownRenderer`（CommonMark + GFM 表格 / 删除线，运行时由 core 提供依赖） |
| `controller/AiAgentApiController`（`/api/ai-agent/*`） | 插件 `AiAgentApiController`（同一批端点） |
| `controller/AiProviderKeysApiController`（`/api/ai-provider-keys`） | 插件 `AiKeysApiController`（**`/api/ai-keys`**，含站点开关 `POST /api/ai-keys/allow-normal`） |
| `special/AiAgentSpecialPage` + `templates/special/ai-agent.html` | 插件 `AiAgentSpecialPage` + `templates/special/ai-agent.html`（模板随 jar 走），新增 `?keys=1` 的站点密钥面板 |
| `参数设置 → AI 代理`（core 的 `preferences.html` 标签页 + `PreferencesSpecialPage` 的模型） | 插件 `AiKeysPreferenceSection`（`PreferenceSection`，标签 id `ai`，表单 `POST /api/ai-keys`） |
| `站点设置 → AI 厂商（站点密钥）` 表单与「允许普通用户使用」复选框（`access-control.html` / `AccessControlSpecialPage` / `SetupController` / `SetupService` / `CoreSetup`） | 插件 `特殊:AI代理?keys=1`（管理员 / 行政员）与 `POST /api/ai-keys`、`POST /api/ai-keys/allow-normal` |
| `static/js/ai-agent.js` + `ai-keys.js`（+ `.d.ts`）、`base.css` 里的 `.ai-agent-*` / `.ai-key-*` 样式 | 插件 `static/plugins/ai-agent/{ai-agent.js,ai-agent.d.ts,ai-keys.js,ai-keys.d.ts,ai-agent.css}`（由 `AiAgentHeadProvider` 站点级注入） |
| `aiAgent.*`（除水印标题）/ `preferences.ai.*` / `preferences.tab.ai` / `special.aiAgent` / `settings.aiVendors*` / `settings.aiAgentAllowNormal*` 文案 | 插件 `AiAgentMessages`（键名不变，core 里已删除） |

## core 保留了什么

- **功能开关** `features.ai-agent`（默认**关闭**）：`/init` 安装向导、`特殊:权限配置 → 站点设置`、
  `LocalSettings.yaml` 的 `wikikernel.features.ai-agent` 三处照旧；插件经 `common.service.SiteConfig` 读同一个键。
- **页面元数据**：`wiki_revision.ai_assisted` / `ai_model` 与词条底部的隐藏水印
  （`templates/wiki/view.html` 的 `.mw-ai-watermark`、文案键 `aiAgent.watermarkTitle`）——水印渲染与
  版本字段读取属于「页面元数据」，不随插件迁走。
- **限流规则** `wikikernel.rate-limit.ai-agent`（默认 30 次/小时；插件经 `common.service.RateLimit` 桥用同一实现）。
- **「AI代理用户」用户组**（`PermissionTypeSeeder` / `PermissionService.GROUP_AI_AGENT`）。

**随插件走的东西（core 里已经搜不到）**：`wikikernel.ai.*` 配置项（插件自己的 `AiProperties`，
`@ConfigurationProperties` 由插件子容器绑定）、`wiki_user_ai_key` 表与实体（插件 `schemaEntities()` 声明，
core 自动建表 / 补列，数据访问走 `common.service.PluginDatabase`）、`AiConfig` 桥与实现
（`plugin/ai-agent/.../service/AiConfig.java` + `AiConfigService`）、站点密钥键的读写
（`ai.provider.<id>.*` / `ai-agent.allow-normal-users`，经 `common.service.SiteConfig` 的 `get` / `set`
写进与 core `/init` 相同的存储；`wikikernel.ai.allow-normal-users` 在插件启动时导入站点设置）。

**插件未加载时**：`特殊:AI代理` 与 `/api/ai-agent/*`、`/api/ai-keys/*` 都不存在，参数设置里没有「AI 代理」
标签页，站点设置里没有 AI 厂商密钥表单，页面不注入 `/plugins/ai-agent/…` 资源；
`features.ai-agent` 开关仍在（打开也没有任何功能）。core 侧由 `AiAgentTest` 断言这些语义。

## 厂商适配器清单

`AiVendorCatalog`（**适配器全部内置**，用户 / 管理员只填密钥，也可改 base-url / 模型 / 代理）：

| 厂商 id | 协议 | 默认模型 | 密钥 |
| --- | --- | --- | --- |
| `mock` | mock | mock | 不需要（离线演示：读 → 提案 → 预览 / 差异 → 确认提交，含多条目与配套模板） |
| `openai` | openai | gpt-4o-mini | 需要 |
| `deepseek` | openai | deepseek-chat | 需要 |
| `groq` | openai | llama-3.3-70b-versatile | 需要 |
| `gemini` | gemini | gemini-2.0-flash | 需要（本机常需代理） |
| `anthropic` | anthropic | claude-3-5-sonnet-latest | 需要（本机常需代理） |
| `openrouter` | openai | `deepseek/deepseek-chat-v3-0324:free` | 需要 |
| `siliconflow` | openai | `deepseek-ai/DeepSeek-V3` | 需要 |
| `ollama` | openai | llama3.1 | 不需要（本地模型） |
| `custom` | openai | 自己填 | 需要（任意 OpenAI 兼容端点） |

四种协议适配器：`openai`（OpenAI 兼容）、`anthropic`（Messages API）、`gemini`（generateContent）、
`mock`（离线演示）。`LocalSettings.yaml` 的 `wikikernel.ai.providers` 里还能加**目录之外**的厂商 id
（协议 / 显示名 / 温度 / max-tokens 都从 yaml 读）。

**优先级（同一厂商）**：用户密钥（`参数设置 → AI 代理`）&gt; 站点密钥（`特殊:AI代理?keys=1`）&gt;
`LocalSettings.yaml`。`mock` 永远出现在下拉里；界面只显示尾号掩码（密钥只留在服务端）。

`Socks5Bridge`：JDK 的 `HttpClient` 不支持 SOCKS，插件在 `127.0.0.1` 上开一个迷你 HTTP CONNECT 桥，
所以 `socks5://` / `socks5h://` 也能直接用（只监听回环地址）。

## 配置键

| 键 | 位置 | 默认 |
| --- | --- | --- |
| `features.ai-agent` | `/init`、站点设置、LocalSettings.yaml | **关闭** |
| `ai-agent.allow-normal-users` | 插件站点入口（`特殊:AI代理?keys=1`）、`/init` 写入的 `wikikernel.ai.allow-normal-users` | false |
| `ai.provider.<id>.key` / `.base-url` / `.model` / `.proxy` | 站点设置（由插件写：`SiteConfig.set`，与 core `/init` 同一存储） | 空 |
| `wikikernel.ai.*` | LocalSettings.yaml（厂商表 / 轮数 / 超时 / 会话上限 / 读取上限） | 见插件的 `model/AiProperties` |
| `wikikernel.rate-limit.ai-agent` | LocalSettings.yaml（每用户每小时的对话次数） | 30/小时 |

## 平台桥（插件用到的 `common.service.*`）

- `AiConfig` / `AiConfigService`：**插件自己的**厂商配置读写（用户密钥走插件表 `wiki_user_ai_key` +
  `PluginDatabase`；站点密钥与运行时设置走 `SiteConfig` 读写；yaml 走插件的 `AiProperties`）、
  `sourceOf` 优先级、`settings()`（默认厂商、放开普通用户、轮数、超时、代理、读取上限、会话 TTL / 上限、
  对话条数上限）、`mask` 尾号掩码、`saveAllowNormalUsers`（写站点开关）。
- `EditSubmitter`：**确认提交走它**（`Request.aiAssisted(...)`）——先跑平台保存前钩子（插件版滥用过滤器等，
  来源 `source=ai`），再按乐观锁保存并打 AI 水印；`blocked` / `handled` → 拒绝提交，`warn` → 保存并回传提示。
- `WikiPageStore`（正文）、`PageQueryStore`（页面元信息 / 命名空间列举 / 版本历史）、`WikiText`（提案预览，
  与正文同一套渲染器）、`WikiDiff`（提案差异）、`SearchStore`（搜索工具）、`PageLinkStore`（模板 / 链入 /
  被嵌入）、`CategoryStore`（分类）、`AccessCheck`（读 / 编辑 / 创建权限）、`CurrentUser`（当前用户与用户组）、
  `UserStore`（用户名）、`RateLimit`（对话限流）、`SiteConfig`（`features.ai-agent` / `site.name` 读，
  站点密钥与放开普通用户写）、`PluginDatabase`（`wiki_user_ai_key` 表读写）、`DbRow`（行读取）。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ai-agent/state` | 开关 / 权限 / 可用厂商（不含密钥）/ 默认厂商 |
| GET | `/api/ai-agent/session` | 还原会话：对话记录（含 Markdown HTML）、步骤轨迹、待确认提案 |
| POST | `/api/ai-agent/chat` | 对话一轮（`session` / `provider` / `message`） |
| POST | `/api/ai-agent/apply` | 确认提交一个提案（`session` / `proposal` / `comment` / `content` 可带用户改过的正文） |
| POST | `/api/ai-agent/apply-all` | 一键提交会话里全部提案（多条目） |
| POST | `/api/ai-agent/discard` | 放弃提案 |
| POST | `/api/ai-keys` | 保存 / 清除厂商密钥（`scope=user` 任意登录用户；`scope=site` 管理员 / 行政员；`clear=1` 清空） |
| POST | `/api/ai-keys/allow-normal` | 站点是否放开普通用户（管理员 / 行政员） |

错误语义与迁移前一致：未登录 `401`；功能关闭 / 无权限 / 无编辑创建权限 `403`；业务错误 `400`；
对话限流 `429`；版本冲突是 `200 + ok=false + code=conflict`。

## 与迁移前的行为差异

1. **站点密钥 / 放开普通用户的入口换了地方**：原来在 `特殊:权限配置 → 站点设置`（和 `/init` 的一个
   「配置项」），现在在 `特殊:AI代理?keys=1`（管理员 / 行政员）。键与语义不变
   （`ai.provider.<id>.*`、`ai-agent.allow-normal-users`），`/init` 仍可导入
   `wikikernel.ai.allow-normal-users`。
2. **密钥接口路径** `/api/ai-provider-keys` → **`/api/ai-keys`**（插件自己的 @RestController）。
3. **静态资源路径** `/js/ai-agent.js`、`/js/ai-keys.js` → `/plugins/ai-agent/…`；AI 相关样式从 core 的
   `base.css` 迁到 `/plugins/ai-agent/ai-agent.css`（`.mw-ai-watermark` 仍在 core）。
4. **提案正文的标题规范化**：迁移前用 core 的 `WikiTitles.normalize`（下划线折叠、首字母大写），插件里
   用一份等价的最小实现（去空白 / 下划线折叠、冒号后首字母大写）——引用模板的「缺模板」判定与
   `特殊:全部页面` 的命名空间语义由 core 的桥负责，保持不变。
5. **提交时的变更标签**：AI 提交经 `EditSubmitter` 后，钩子登记的变更标签（如滥用过滤器的 tag）由
   `EditSubmitter` 写入版本（core 侧同时补齐了这条桥的标签传递）。
6. **页面元信息**：`read` / `info` 工具经 `PageQueryStore` 读取（保护状态 / 创建时间 / 最新版本等字段与
   迁移前一致）；`list` 工具的命名空间解析（`main` / `template` / `模板:` …）由 core 的桥完成，保持不变。
7. **未移植**：无（原 AI 代理能力全部迁移；对话会话仍是**服务端内存**，重启即清空——与迁移前一致）。

## 测试

`plugin/ai-agent/src/test`（43 个用例，全部离线：厂商用内置 `mock` 适配器，桥用内存替身，SOCKS5 用本机迷你
服务端，不发任何真实网络请求）：

- `AiAgentServiceTest`（17）：开关与权限（关闭 / 非授权组 / 放开普通用户 / 未登录）、Mock 全流程
  （读 → 提案 → 渲染预览 + 差异 + 基准版本，**propose 不落库**）、确认提交**走 `EditSubmitter`**
  （`source=ai` + `aiAssisted` + 基准版本 + 摘要「AI 代理（mock/mock）：…」）、用户改过正文后按用户版本保存、
  并发冲突拒绝、`blocked` / `handled` / `saveFailed` 的拒绝语义、无权限页面 propose 失败、
  多条目一键提交、**部分失败保留在列表里**、放弃提案、会话还原（含 Markdown HTML）、
  state 不泄露密钥、轮数上限（0 = 不限制）、厂商不可用、密钥优先级、模板引用 / 缺模板检测。
- `AiAgentKeysTest`（4）：用户 &gt; 站点 &gt; yaml 的优先级、掩码只露尾号、清除后回落、
  站点级与「放开普通用户」只有管理员 / 行政员可改（403）、未登录 401、未知厂商 400、
  `settingsRows` 不含密钥明文、Mock 恒可用 + yaml 自定义厂商。
- `AiAgentApiControllerTest`（6）：开关关闭 403、未登录 401、无权限 403、放开普通用户后 200、
  对话限流 429（被限流时不再计数）、state / session 正常返回、冲突与未知提案的码值。
- `AiAgentPluginTest`（9）：扩展点装配、特殊页模型（未启用提示 / 权限提示 / 厂商列表 / 来源标签 /
  `?keys=1` 站点密钥面板仅管理员可见）、参数设置分区的表单（脚本、`data-keys-scope=user`、
  密钥不回显、关闭时仍可配置）、前端资源注入、文案包中英文键一一对应、
  **特殊页模板引用的消息键都能在插件文案包里解析**。
- `MarkdownRendererTest`（4）：粗体 / 列表 / 代码 / 表格 / 删除线 / 引用、原始 HTML 转义、危险链接清洗。
- `Socks5BridgeTest`（3，随 `Socks5Bridge` 一起从 core 迁入）：`socks5h`（域名交给代理）与 `socks5`
  （本机解析）的 CONNECT → 握手 → 双向对接、代理拒绝时回 `502`。

真实实例上的验证（本轮）：`plugins/` 里 25 个 jar 全部启动（core 的 `PluginRuntimeLoadTest`）；
`特殊:AI代理` 对匿名显示「需要登录」、对登录用户渲染完整对话界面、对管理员 `?keys=1` 渲染站点密钥面板；
`/api/ai-agent/state` 返回可用厂商；Mock 对话 → `apply-all` 两个条目 → 页面与配套模板都创建、版本摘要是
「AI 代理（mock/mock）：…」、页面带 `mw-ai-watermark` + `data-ai-model="mock/mock"`；
`POST /api/ai-keys`（站点密钥）只回传尾号掩码，`POST /api/ai-keys/allow-normal` 写同一个站点设置键。

> **功能开关**：`features.ai-agent`（默认值见插件描述文件）。开关由本插件在自己的
> `META-INF/wiki-plugin.properties` 里声明（`plugin.featureKey` / `plugin.featureDefault`），
> core 自动在「特殊:权限配置 → 站点设置 → 插件设置」与 `/init` 的插件 tab 里渲染 / 保存；
> 插件用 `common.service.SiteConfig` 读同一个键——core 里没有本功能的开关代码，
> 插件未加载时开关也不出现。
