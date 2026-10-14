# 配置与数据

从 `application.yaml` / `LocalSettings.yaml` 到 Auto-DDL、MyBatis-Plus 数据访问、Redis 页面正文缓存、
站点名称、访问控制与界面本地化（i18n）——「装好之后要调的东西」都在这里。
怎么构建与启动见 [README](../README.md) 的「快速开始」，插件自己的配置键见 [插件开发与运行](./PLUGIN-DEV.md)。

其它文档：[README](../README.md) ｜ [接口与兼容 API](./API.md) ｜ [功能详述](./FEATURES.md) ｜ [皮肤与前端界面](./SKIN.md) ｜ [Wikitext 与内容标记](./WIKITEXT.md) ｜ [插件开发与运行](./PLUGIN-DEV.md)

## 配置

`core/src/main/resources/application.yaml`：

```yaml
wikikernel:
  plugins:
    dir: plugins               # 运行时扫描的插件 jar 目录（相对启动时工作目录）
    runtime-dir: run/.plugins  # 运行时副本目录：加载的是拷贝，源 jar 不被锁定（可随时替换/删除）
    classpath-scan: true       # 是否用 ServiceLoader 扫描 classpath 上的插件（IDE 调试/测试方便）
    enabled: [ ]               # 白名单，为空 = 启用扫描到的全部插件；填写后只启用列表中的插件
    settings:                  # 每个插件的私有配置，键为插件 id
      your-plugin:
        key: value
```

命令行覆盖示例：

```powershell
java -jar core\build\libs\core-0.0.1-SNAPSHOT.jar --wikikernel.plugins.dir=D:\wiki-plugins
```

## 本地配置：LocalSettings.yaml（类似 MediaWiki 的 LocalSettings.php）

所有部署相关的配置都放在**启动目录**下的 `LocalSettings.yaml`：文件不入库、不打包进 jar，
不存在时使用内置默认（SQLite 文件库 `./data/wiki.db`）。仓库提供模板：

```powershell
Copy-Item LocalSettings.example.yaml LocalSettings.yaml
```

**IDEA 支持（补全 / 跳转，与 application.yaml 同等待遇）**：

- `core` 模块启用了 `spring-boot-configuration-processor`，构建后会生成
  `META-INF/spring-configuration-metadata.json`，所有 `wikikernel.*` 字段（含注释说明）都能补全，
  中键 / Ctrl+点击可跳到对应的 `@ConfigurationProperties` 字段；
- `application.yaml` 用 `spring.config.import` 以**双路径**导入（`./LocalSettings.yaml` 与
  `../LocalSettings.yaml`，都是 optional）：运行时从仓库根目录启动命中前者，IDEA 从 `core` 模块
  解析命中后者，从而把文件识别成 Spring 配置文件；
- 单元测试在沙箱工作目录（`core/build/test-working-dir`）运行，不会被真实 `LocalSettings.yaml` 影响；
- 若 IDEA 仍未识别该文件（版本差异），一次性在 **File → Project Structure → Facets → Spring →
  Configuration Files** 里把 `LocalSettings.yaml` 加进去即可，补全会立即生效。

```yaml
wikikernel:
  database:
    type: mysql              # sqlite（默认）| mysql | h2
    host: 127.0.0.1
    port: 3306
    name: wikikernel
    user: root
    password: "${MYSQL_PASSWORD:root}"   # 支持 ${环境变量:默认值}
  plugins:
    enabled: [ ]             # 类似 wfLoadExtension：为空 = 全部启用
    settings:
      your-plugin:
        key: value
  permissions:
    bootstrap-admins: [ "你的用户名" ]   # 首次搭建：启动时授予 管理员 + 行政员
    allow-other-user-page-edit: false   # 是否允许编辑其他用户的用户页（本人 / 管理员始终可以）；不写 = 不改网页设置
    rules:                              # 访问控制默认规则（每次启动导入，见「访问控制」一节）
      - target: "*"                     # * / 页面标题 / 前缀（以 * 结尾）/ 特殊:页面名
        action: read                    # read / edit / create / move / protect / upload / delete / purge / block
        groups: "*"                     # * = 所有人（含匿名）；空 = 无人；多个用 | 分隔
      - target: "帮助:*"
        action: create
        groups: "自动确定用户"
      - target: "特殊:用户权限"
        action: read
        groups: "管理员|行政员"
  run-path: ./run/files                 # 上传文件存放目录（默认 ./run/files）
  max-file-size-mb: 20                  # 单文件大小上限（MB）
  http:
    trust-proxy-headers: false          # 部署在 Cloudflare 等 CDN / 反向代理后面时 true（日志记录真实客户端 IP）
  cors:                                 # CORS 跨域（默认关闭；见「CORS 跨域」一节）
    enabled: false
    # allowed-origins: [ "https://example.com" ]   # 允许的来源（"*" = 任意）
    # allow-credentials: true
  auth:
    token-expire-days: 0                # 持久登录令牌有效期（天）；0 = 永不过期
    token-cookie-name: wiki_token       # 持久登录 Cookie 名
  skins:
    default-skin: vector                # 默认皮肤；内置 vector / fallback，或插件皮肤 id
    disabled: [ ]                       # 禁用的皮肤 id（fallback 不可禁用）
```

| 数据库 | type | 关键配置 |
| --- | --- | --- |
| SQLite（默认） | `sqlite` | `file`（默认 `./data/wiki.db`）；连接池固定 1 连接 + 外键 PRAGMA |
| MySQL | `mysql` | `host` / `port` / `name` / `user` / `password` / `pool-size` |
| H2 | `h2` | `file`（默认 `./data/wiki`） |

切换数据库只改 `type`，例如：

```powershell
.\gradlew.bat :core:bootRun                                            # 无 LocalSettings.yaml 时默认 SQLite
.\gradlew.bat :core:bootRun --args='--wikikernel.database.type=h2'     # 临时切 H2
```

优先级：**命令行参数 > 环境变量 > LocalSettings.yaml > 内置默认**。
高级用法：直接在 `LocalSettings.yaml` 里写 `spring.datasource.url` 会完全接管数据源配置。

实现：`spring.config.import` 负责加载文件，`LocalSettingsEnvironmentPostProcessor`
把 `wikikernel.database.*` 翻译成标准 `spring.datasource.*`，
因此 JdbcClient / 事务 / 连接池等仍是 Spring Boot 原生行为。
`GET /api/version` 会返回当前数据库（如 `"db":"SQLite"`）与 `wiki_page` 行数，方便确认切换是否生效。

### CORS 跨域（`wikikernel.cors`）

默认**关闭**（不添加任何跨域响应头，行为与原来完全一致）；让其它站点的前端用 `fetch` 调本站接口时再打开：

```yaml
wikikernel:
  cors:
    enabled: true
    allowed-origins:                 # 精确来源；"*" = 任意来源
      - https://example.com
    allowed-origin-patterns:         # 通配来源（可只配这一项）
      - https://*.example.com
    allowed-methods: [ GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS ]
    allowed-headers: [ "*" ]         # 预检允许的请求头
    exposed-headers: [ ]             # 允许前端读取的响应头
    allow-credentials: false         # 允许携带 Cookie / Authorization
    max-age: 3600                    # 预检结果缓存秒数
    paths: [ "/api/**" ]             # 生效路径；默认全部
```

- 生效条件：`enabled: true` **且** `allowed-origins` / `allowed-origin-patterns` 至少配一个来源；
  两者都没有时不会注册跨域过滤器（启动日志会说明）；
- `allow-credentials: true` 时不能用 `"*"` 当精确来源：会自动改成“按请求 Origin 回显”的来源模式并记日志；
- 实现：`config/CorsConfig` 注册**最高优先级**的 `CorsFilter`（在 Spring Security 过滤器链之前处理预检与响应头），
  所以 `/api/**`（已免 CSRF）可以被允许来源的网页跨域调用；`/wiki/**` 的表单 POST 仍受 CSRF 保护，
  跨域页面拿不到 token，保存页面会被拒绝（符合预期）；
- 安全提示：`"*"` 会让任意网站读取接口响应；需要带登录态（Cookie）跨域时用精确来源 +
  `allow-credentials: true`，并按浏览器要求通过 HTTPS（Cookie 需 `SameSite=None; Secure`）。

三种库通用的可移植性约定（写 DAO 时遵守）：

| 项目 | 约定 |
| --- | --- |
| 主键 | 应用生成（UUID / 雪花 ID）或数据库自增（`IdType.AUTO`），详见下节 |
| 时间 | `LocalDateTime` / `LocalDate` 自动映射各库时间类型；需要纯数字时间用 `Long`（epoch millis） |
| 文本 | `VARCHAR(n)` / `TEXT` |
| 建表 | 由 Auto-DDL 根据实体生成（见下一节）；手写 SQL 时用 `CREATE TABLE IF NOT EXISTS` + 内联约束 |
| Upsert / 全文检索 | 语法不通用，需要时按方言隔离：SQLite `ON CONFLICT`、MySQL `ON DUPLICATE KEY UPDATE`；搜索建议继续走 `SearchProvider` 扩展点 |

插件访问数据库：插件子容器可以注入 core 的 `DataSource` / `JdbcClient`，
在插件 `build.gradle` 加 `compileOnly 'org.springframework.boot:spring-boot-starter-jdbc'` 即可编译
（插件里的 MyBatis-Plus Mapper 暂未接入，见下一节说明）。

## 数据访问：MyBatis-Plus + Auto-DDL

- ORM 使用 **MyBatis-Plus 3.5.17**（官方 Boot 4 starter `mybatis-plus-spring-boot4-starter`）；
- 实体类（`@TableName` 模型）放在 **`common/src/main/java/cc/thonly/wikikernel/common/data/`**，
  core 与插件都能引用；Mapper 放在 core 的 `cc.thonly.wikikernel.db.mapper` 下（`@MapperScan`）；
- 表结构由**实体驱动自动维护**（类似 JPA `ddl-auto=update`）：启动时表不存在则建表，缺字段则 `ALTER TABLE ... ADD COLUMN`；
- **只增不减**：不会删列、不会改类型、不会动已有索引；正式环境建议关闭并改用 Flyway/Liquibase 脚本迁移。

```java
/** 页面身份：一行 = 一个页面（正文在 WikiRevision） */
@Data
@TableName("wiki_page")
public class WikiPage {
    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;                 // 页面 ID（自增，历史的“页面身份”）

    @DbColumn(nullable = false)
    private String title;

    @DbColumn
    private Long latestRevisionId;   // 指向当前版本

    @DbColumn(nullable = false)
    private LocalDateTime updateAt;  // createAt / hided / deleted 略
}

/** 历史版本：一行 = 一次编辑（正文在这里，仿 MediaWiki 的 revision 表） */
@Data
@TableName("wiki_revision")
public class WikiRevision {
    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;                 // 版本 ID，每次编辑自增

    @DbColumn(nullable = false)
    private Long pageId;             // 所属页面

    @DbColumn(type = DbType.LONGTEXT)
    private String content;

    @DbColumn
    private Long userId;

    @DbColumn(length = 500)
    private String comment;          // 编辑摘要

    @DbColumn(nullable = false)
    private LocalDateTime createAt;
}
```

页面与历史版本（MediaWiki 式 page/revision 两表）：

- `wiki_page` 存页面身份，`id` 自增即页面 ID；`wiki_revision` 存每次编辑的正文/编辑者/摘要/时间；
- 路由：`/wiki/标题` 阅读（`#REDIRECT` 页自动跳转，`?redirect=no` 可看重定向页本身）、
  `?action=edit` 编辑（未登录先跳登录页，页面不存在则创建）、
  `?action=history` 历史（页面还不存在时同样 404，自定义 404 页里可点「创建本页面」）、`?oldid=N` 查看指定旧版本；
- 根路径 `/` **按界面语言重定向到主页面**：消息键 `mainPage`（中文「首页」/ 英文「Main Page」）；
  该语言的首页还没创建时回退到默认「首页」，避免 `/` 直接 404（`WikiPageController#mainPageTitle`）；
- 历史页每行支持 **查看 / 编辑**：`?action=edit&oldid=N` 以旧版本内容打开编辑器，
  保存后生成新版本（想回退到旧版就直接编辑旧版再保存，历史不丢）；
- **差异对比页**（仿 MediaWiki 的 DifferenceEngine）：
  - 参数：`?diff=prev&oldid=N`（N 与上一版本）、`?diff=next&oldid=N`（N 与下一版本）、
    `?diff=N&oldid=M`（M → N）、`?diff=cur&oldid=N`（N 与当前版本）、`?diff=N`（旧侧取 N 的上一版本）；
  - 两栏 diff（− 黄色删除行 / + 蓝色新增行，替换行内的改动段**加粗**，仿 `.diffchange`）、
    版本头（修订号链接 / 用户 / 时间 / 摘要）与「上一差异 / 下一差异」导航；第一版提示「（这是最早的版本）」、
    无变化显示「没有差异。」；内容过大时自动退化为「公共前缀后缀 + 全删全增」，保证能打开；
  - 入口：历史页每行的「差异（prev）/ 当前（cur）」、最近更改与用户贡献的「差异」、旧版本阅读页的
    「与当前版本的差异」；
- **编辑草稿保护**（`static/js/edit-draft.js`，本地 localStorage，不上传服务器）：
  - 键 `tempedit-cache-<页面标题>`，值为 JSON `{v, t, base, text}`（`text` 原样存，无需 base64）；
  - 输入停顿 5 秒自动保存，提示条显示「草稿已自动保存到本地（保存于 …）」；
    关闭 / 刷新 / 切后台前也会落盘，并在仍有未保存修改时触发浏览器自带的离开确认
    （必须确认才会关闭，文案由浏览器决定）；保存成功自动清除草稿；
  - 提示条（含「放弃草稿」按钮）**只在确实存在草稿时显示**：放弃草稿或把内容改回页面当前版本后立即隐藏；
  - 下次打开同一页面自动恢复草稿并提示保存时间；
  - **冲突检测**（A 编辑一半离开、B 已提交的场景）：草稿记录当时的修订号；再次打开时若修订号已变化
    （或内容哈希不匹配），会明确提示「该页面在您离开后被修改过（版本 N → M），直接保存会覆盖对方的修改」，
    并提供「查看最新版本」链接与「放弃草稿」按钮；
    提示条上的「放弃草稿」可一键恢复到页面当前内容；
- **段落编辑**（仿 MediaWiki 的 section 编辑）：
  - 可编辑的人会在每个标题旁看到 `[编辑]`（`SectionEditLinks` + `WikiSections`；没编辑权限时不显示，旧版本视图也没有）；
  - `?action=edit&section=N` 的编辑框里**只有该段**（标题行 + 正文，含子标题），摘要自动填 `/* 标题 */ `；
  - 保存时只把该段替换进**最新版本**：别人在其他段落的修改原样保留；该段在你编辑期间被改过
    （服务端比对 `baserevid` 的该段文本）则进入**编辑冲突提示**页（你的文本保留、附差异对照），
    手动合并后再次保存即可（基准已更新为最新，不会循环冲突）；
  - 保存后跳回该段锚点（`#标题`）；页面结构已变、段落不存在时进入冲突提示页；
  - 段落编辑的草稿单独存档：`tempedit-cache-<页面标题>@s<段落号>`（不会覆盖整页草稿）；
- **整页编辑冲突提示**（仿 MediaWiki 的编辑冲突页，服务端检测，非自动合并）：
  - 提交时基准修订 `baserevid` 已被他人推进 → 不写入、不自动合并，返回**编辑冲突提示**页；
  - 提示页展示：修改者 / 时间 / 对方编辑摘要、**你的提交 ↔ 服务器最新版本**的行级差异（`diffchange` 高亮）、
    「查看当前版本」「查看对方这次修改的差异」「放弃我的修改，编辑最新版本」链接；
  - 你的文本完整保留在编辑框中；手动合并后保存会用编辑框内容生成新版本（保存基准自动更新为最新），
    保存瞬间再次被改写（并发）时仍会回到提示页；
  - Action API 不受影响：`edit` 的过期 `baserevid` 返回 `editconflict` 错误；
- **编辑辅助工具栏**（仿 MediaWiki WikiEditor 默认皮肤，`edit-toolbar.js`，仅 Wikitext 编辑页 / 段落编辑显示）：
  - 插入：粗体（Ctrl+B）、斜体（Ctrl+I）、内部 / 外部链接、参考文献 `<ref></ref>`、签名 `--~~~~`、
    标题（2–5 级下拉）、无序 / 有序列表（对选中行再点一次取消）、缩进、忽略 wiki 格式、水平线、表格；
  - **特殊字符**面板：破折号、数学符号、箭头、中文标点等，点一下插入一个，可连续插入；
  - **快速上传图片**：文件按钮打开弹窗 → 选择图片 → 上传到 `/api/files/upload` → 自动在光标处插入
    `[[文件:名称|thumb]]`（光标停在 `]]` 前，方便补说明）；未登录 / 无上传权限时显示服务端提示；
  - 所有插入都会触发 `input` 事件，草稿保护（`edit-draft.js`）随之自动保存；
  - 代码页（`.js` / `.css` / 微件页）不显示工具栏，继续用 CodeMirror 编辑器；
- **编辑预览**（仿 MediaWiki「显示预览」，`edit-preview.js`，仅 Wikitext 编辑页 / 段落编辑）：
  点「显示预览」→ `POST /api/pages/preview` 服务端渲染编辑框当前内容（**不落盘**），
  预览区出现在编辑框上方，顶部提示「请记住，这只是预览。您的更改还没有保存！」；
  正文按阅读视图渲染（目录折叠、脚注、分类栏、模板 / 微件等都在）；未登录 401、无编辑 / 创建权限 403；
  代码页与「查看源代码」页没有预览按钮；
- **可视化编辑器**（`visual-editor.js` + `/api/pages/visual/*`，仿 MediaWiki 的入口与 URL 参数）：
  - 可编辑的 Wikitext 页面在页签栏显示两个入口：**「编辑」**（可视化，`?veaction=edit`）与
    **「编辑源代码」**（`?action=edit`）；不可编辑时仍显示「查看源代码」；
  - `?veaction=edit` 页面加载即打开可视化编辑；`?veaction=editsource` 与不可用场景
    （代码页 / 无编辑权限等）会像 MediaWiki 一样**回退到源代码编辑**；
    切换模式时地址栏同步（可视化 = `veaction=edit`，源代码 = `action=edit`）；
  - **只从编辑框里的 Wikitext 出发**渲染（不是已保存页面的渲染结果）；服务端先分词再渲染，
    **目录 / 分类栏等「后生成」内容会被剥掉，绝不会进入编辑器或回写**；
  - **模板**：
    - **独占段落的模板直接渲染成预览**（块内就是它的渲染结果，顶部一条「模板：名字」小标签）；
    - 点击任意模板打开**模板对话框**：**参数**页签是参数表单——参数由服务端**扫描模板页源码里的
      三花括号参数**自动发现（数字名是位置参数），对话框里可增删参数、看实时预览
      （`POST /api/pages/visual/template`）；参数值里的 `|` 会转义成 `{{!}}`，留空参数不写入；
    - **源代码**页签可直接改整个 `{{…}}` 调用；「应用更改」把新调用写回编辑器（块内预览同步刷新），
      最终仍由序列化统一回写，未点「应用更改到源代码」前不会动源码；
    - 行内模板（文字中间）显示为「模板：名字」小方块，预览与参数在对话框里看；模板页不存在时对话框给出提示；
  - 引用 / 文件 / 分类 / 复杂表格 / `<pre>` / `<nowiki>` 等显示为不可编辑的**源码块**，
    点击弹窗编辑源码（不再用简陋的输入框），这些内容在可视化编辑中不会被改写或丢失；
  - 支持可视编辑：段落、标题、粗体 / 斜体、内链 / 外链、无序 / 有序 / 缩进列表、水平线、`<br>`、简单表格；
  - 工具栏：粗体 / 斜体 / 内链 / 二三级标题 / 列表 / 水平线与「应用更改到源代码」「返回源代码编辑」；
    粘贴只接受纯文本（不会把渲染结果或外部 HTML 带进内容）；
  - 「应用更改」`POST /api/pages/visual/serialize` 安全序列化回 Wikitext：遇到不支持的结构**整体拒绝**
    （绝不写回半成品），仍可用源代码编辑；应用后请检查源代码再保存（不会自动保存）；
  - 行尾会统一为 `\n`（与渲染一致），未编辑的内容不会产生额外改写；
- 标题里的 `/` 表示子页面（如 `首页/样式.css`），路由支持多段路径（`{*rest}`），
  URL 由 `WikiUrls` 逐段编码，不会产生会被 Tomcat 拒绝的 `%2F`；
- 旧版「正文存在 `wiki_page.content`」的库启动时会自动迁移（`LegacyWikiPageMigration`）：
  为每个页面生成初始版本，并移除旧的 `pageIdentifier` / `content` / `user_id` 列（幂等，可重复启动）。

Java 类型默认推断：`String` → `VARCHAR(255)`（length > 4000 时 `TEXT`）、`Long` → `BIGINT`、
`LocalDateTime` → MySQL `DATETIME(3)` / H2 `TIMESTAMP` / SQLite `TEXT`、`LocalDate` → `DATE` / `TEXT`、
`Boolean` → `TINYINT(1)` / `BOOLEAN`、`BigDecimal` → `DECIMAL(19,4)`、`byte[]` → `BLOB`；
不满足时用 `@DbColumn(type = DbType.XXX, length / precision / scale / nullable / defaultValue / unique)` 覆盖；
`type` 是枚举 `DbType`（`AUTO`/`VARCHAR`/`TEXT`/`LONGTEXT`/`INT`/`BIGINT`/`BOOLEAN`/`DECIMAL`/`DATE`/`DATETIME`/`TIME`/`BLOB`…），
`DECIMAL` 可用 `precision` / `scale`（默认 `19,4`）。需要新类型时在 `DbType` 加枚举值、Auto-DDL 补一行映射。

主键三种写法（Auto-DDL 都支持）：

| 写法 | id 类型 | 说明 |
| --- | --- | --- |
| `@TableId(type = IdType.ASSIGN_ID)` | `Long` | 雪花 ID，应用生成、跨库通用 |
| `@TableId(type = IdType.ASSIGN_UUID)` | `String` | 32 位 UUID，应用生成 |
| `@TableId(type = IdType.AUTO)` | `Long` / `Integer` | 数据库自增：MySQL `AUTO_INCREMENT`、H2 `IDENTITY`、SQLite `INTEGER PRIMARY KEY AUTOINCREMENT` |

注意：`IdType.AUTO` 必须是数值类型；Auto-DDL 只在新表时生成自增语法，已有表不会修改
（若旧表主键不是自增列，启动时会打警告，需删库重建或手动 ALTER）；跨库导入数据时注意主键冲突。

长文本约定：

- 普通长文：`@DbColumn(type = DbType.TEXT)`；
- **正文这类不限长的：`@DbColumn(type = DbType.LONGTEXT)`**，Auto-DDL 按方言翻译为
  MySQL `LONGTEXT` / H2 `CLOB` / SQLite `TEXT`；注意 MySQL 的 `TEXT` 只有 64KB，长词条必须用 `LONGTEXT`；
- `Instant` / `java.util.Date` 在 SQLite JDBC 驱动下不可用（读写会抛异常），请改用 `LocalDateTime` 或 `Long`。

JSON 时间格式：core 已全局配置 `LocalDateTime` 的输出/解析格式为 `yyyy-MM-dd HH:mm:ss`
（见 `core` 的 `JacksonTimeConfig`，基于 Boot 4 + Jackson 3 的 `JsonMapperBuilderCustomizer`）；
只影响接口 JSON，不影响数据库存储。单个字段想用别的格式，可在实体字段上加 `@JsonFormat` 覆盖
（实体在 `common`，需要在 common 引入 Jackson 注解依赖）。

开关：`wikikernel.database.auto-ddl: false`（`LocalSettings.yaml`）可关闭自动 DDL。

边界说明：

- 已有表**不会**补唯一索引、改类型、删列（与 JPA `update` 一样保守）；
- 插件里的 Mapper 暂不支持（`@MapperScan` 只扫描 core 包）；插件访问数据库建议注入 core 的 `DataSource` / `JdbcClient`。

## 站点名称（仿 MediaWiki 的 $wgSitename）


默认 `WikiKernel`，三处可配（优先级从高到低）：

| 位置 | 说明 |
| --- | --- |
| `/init` 安装向导 → 站点信息 | 写入 `LocalSettings.yaml` 的 `wikikernel.site`，保存后**立即生效** |
| `特殊:权限配置 → 站点设置` → 站点信息 | 写数据库设置 `site.name`，**立即生效**（只有管理员 / 行政员能改） |
| `LocalSettings.yaml` 的 `wikikernel.site.name` | **每次启动导入**，覆盖网页里的修改（与 `wikikernel.features` / `permissions.rules` 同一约定） |

```yaml
wikikernel:
  site:
    name: 我的维基
```

- **生效范围**：浏览器标题栏（「页面名 - 站点名」）、页眉图标提示（Vector 的 logo `alt`；fallback 皮肤的品牌文字）、
  搜索框占位符（「搜索 我的维基」）、页脚标识行（「我的维基 · Powered by Spring Boot」）、
  动作 API `action=query&meta=siteinfo` 的 `sitename`、`mw.config.get('wgSiteName')`、
  Lua 的 `mw.site.siteName`、导出文件里的 `sitename`、邮件主题 / 正文（如「我的维基 密码重置」）与滥用过滤器变量 `wiki_name`；
- **规范化**：去掉控制字符、压缩连续空白、去首尾空白、最多 **128** 字符；留空 = 默认 `WikiKernel`；
- **不跟着变**：项目命名空间（`WikiKernel:`，如 `WikiKernel:关于`）与软件名（`特殊:版本`、`/api/version`、
  导出格式名 WikiKernel XML / JSON）保持 `WikiKernel` —— 对应 MediaWiki 的 `$wgMetaNamespace`，与 `$wgSitename` 是两回事；
- **回归测试** `SiteNameSettingTest`（默认值 / 自定义值贯通标题、品牌、搜索框、API、Lua；空值与超长规范化；
  设置接口不带 `siteName` 时不动已有名称）。

## 访问控制（页面 / 特殊页 的查看、编辑、创建等权限）

规则模型：**目标 + 动作 → 允许的用户组**，存 `wiki_access_rule`（`target` / `action` / `group_names`）。

| 维度 | 取值 |
| --- | --- |
| 目标 | `*`（全部）｜精确标题（`首页`）｜前缀，以 `*` 结尾（`帮助:*`）｜特殊页（`特殊:日志`，按**规范名**匹配，别名同样生效） |
| 动作 | `read` 查看、`edit` 编辑、`create` 创建、`move` 移动、`protect` 保护、`upload` 上传、`delete` 删除 |
| 允许 | `*` = 所有人（含匿名）｜空 = 无人（禁止）｜多个用户组用 `\|` 分隔（任一即可） |

- **匹配优先级**：精确标题 > 最长前缀 > `*`；命中后不再往下匹配；
- **内置默认值**（没有任何规则命中时）：查看=所有人；编辑 / 创建 / 移动 / 上传=登录用户；
  保护 / 删除=管理员；基础组「用户」对登录用户隐式生效；
- **命名空间 / 配置页的内置默认值**（同样可被规则覆盖）：
  微件（`微件:`）命名空间的编辑 / 创建 / 移动=**小部件编辑者**（管理员不豁免）；
  WikiKernel（`WikiKernel:`）命名空间的编辑 / 创建 / 移动=**管理员**；
  `WikiKernel:Titleblacklist` / `WikiKernel:Titlewhitelist` 的查看=**管理员**
  （无权者按「页面不存在」处理：404，且标题不会出现在列表 / 搜索 / 最近更改里）；
- **网页配置**：`特殊:权限配置`——默认规则（目标 `*`）每个动作一行；可添加 / 覆盖 / 删除任意规则；
  只有管理员 / 行政员能修改（`POST /api/access-rules`、`POST /api/access-rules/delete`，`@RestController`）；
  页面本身也受规则控制（如把查看权限设为「无人」后需要靠 LocalSettings 恢复）；
- **LocalSettings.yaml**：`wikikernel.permissions.rules` 里的规则**每次启动导入**，
  覆盖同一「目标 + 动作」的网页修改（网页新增的其它规则保留）；
- **强制点**（全部经过 `AccessControlService`，方便维护）：
  - 页面：`/wiki/标题`、`?action=history`、`?action=raw`（查看）；`?action=edit` 与保存（编辑 / 创建，无权时显示「查看源代码」并提示）；
  - 特殊页：渲染前按 `特殊:<规范名>` 检查查看权限（403）；
  - 移动 / 保护：`特殊:移动页面`、`特殊:保护页面` 与 `/api/pages/move`、`/api/pages/protect`；
  - 列表防泄露：`特殊:全部页面`、`特殊:搜索`、`/api/search`、`/api/pages`、`特殊:最近更改`、`特殊:相关更改`、
    `特殊:链入页面`、`特殊:用户贡献`、`特殊:日志`、`特殊:页面信息` 都会隐藏无权查看的页面（标题也不出现）；
- 受保护页面仍额外要求管理员（`protectedPage`），与规则叠加生效。

示例（LocalSettings.yaml）：见上文「本地配置」一节；网页配置入口：`特殊:权限配置`（英文别名 `Special:AccessControl`）。

## 界面本地化（i18n）

界面文案全部走消息资源，默认简体中文，已附带英文；以后加 `messages_xx.properties` 即可扩展：

- 资源文件：`core/src/main/resources/messages.properties`（默认中文）与 `messages_en.properties`；
- 模板用 `#{key}`；Java 侧注入 Spring `MessageSource`（`LocaleContextHolder` 取当前语言）；
  JS 文案由 `fragments/head.html` 内联成 `window.WIKI_MESSAGES` 供 `auth.js` 使用；
- 切换语言：任意页面加 `?uselang=en`（与 MediaWiki 同名参数），记入会话，页脚有「语言：中文 · English」链接；
  不支持的语言回落到默认中文；
- 特殊页展示名可本地化：`SpecialPage.displayName()`（默认等于 `name()`；`name()` 仍作为 URL / 注册键）；
- 日期格式也走消息键 `format.dateTime`（中文 `yyyy年MM月dd日 HH:mm`，英文 `yyyy-MM-dd HH:mm`）；
- `MessageBundleTest` 保证中英文 key 一致、模板引用的 key 都有定义。

不参与本地化的内容：页面标题/正文、侧边栏页面内容、编辑摘要等**用户数据**；启动日志与内部异常信息；品牌名 WikiKernel。

## 页面正文缓存（Redis 可选）

rendered 之后的页面正文可以缓存起来（命中就直接跳过渲染），模板一改就自动批量刷新受影响的页面：

- **开关与后端**（站点设置 `wiki_setting` 可运行时改、`LocalSettings.yaml` 的 `wikikernel.cache.page.*` 兜底、**改完立即生效、不用重启**）：
  `cache.page.enabled`（**默认 false**）、`cache.page.backend`（`auto` / `redis` / `memory` / `none`，默认 auto）、`cache.page.ttl-seconds`（300）、
  `cache.page.max-entries`（内存后端上限，500）、`cache.page.preload-on-template-change`（true）、`cache.page.preload-parallelism`（默认 CPU 核数）、
  `cache.page.preload-batch-size`（0 = 自动）；
- **Redis 可有可无**：配了 `spring.data.redis.*`（或 `wikikernel.cache.page.redis.enabled=true`）且连得上就用 Redis，否则退回**进程内 LRU**，再不行等于不缓存——
  **没装 Redis 也能正常启动**，运行期 Redis 报错只 WARN 一次并临时退回内存；启动日志会写明用的是哪种后端；
- **版本安全**：缓存条目带 `revisionId`，与页面当前版本比对不一致就按未命中处理并删除条目，**永不返回旧内容**；
- **模板变更**：模板（`模板:` 命名空间）保存后，用 `PageLinkStore.pagesTranscluding` 找出所有嵌入它的页面 → 逐个失效 → 用**虚拟线程**分批**预渲染**
  （并发受 `preload-parallelism` 限制，单页失败只记日志，异步执行不阻塞保存请求）；
- **给插件**：`common.service.PageCache`（`enabled` / `html` / `put` / `invalidate` / `invalidateAll` / `invalidateTranscluding`）可主动失效。
