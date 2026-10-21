# 第三方登录（GitHub / QQ）

core 提供第三方 OAuth 登录 / 注册框架；GitHub / QQ provider 分别是 `external-auth-github`、`external-auth-qq` 插件。用户在登录 / 注册页点击 GitHub 或 QQ 按钮，完成后：

- 已绑定的第三方账号：直接登录；
- 未绑定且站点允许自动注册：创建本地账号并绑定；
- 已登录用户从「特殊:参数设置 → 第三方授权」发起：只绑定到当前账号，不会切换登录用户。

## 管理入口

- **启用 / 配置 provider**：`特殊:权限配置 → 第三方登录`
- **用户绑定 / 解绑**：`特殊:参数设置 → 第三方授权`

## 配置步骤

1. 在 `特殊:权限配置 → 第三方登录` 填写站点根地址（可选）。  
   反向代理、自定义域名或 HTTPS 时必须填写对外地址，例如 `https://wiki.example.com`；留空则按当前请求推导。
2. 创建对应开放平台应用：

   **GitHub**
   - OAuth App → Authorization callback URL 填：
     `https://<你的站点>/auth/github/callback`
   - 复制 Client ID 与 Client Secret 到站点设置，勾选「启用 GitHub 登录」并保存。

   **QQ 互联**
   - 应用的回调地址填：
     `https://<你的站点>/auth/qq/callback`
   - 复制 App ID 与 App Key 到站点设置，勾选「启用 QQ 登录」并保存。

3. 保存后登录页 / 注册页会出现对应按钮。  
   Client ID 与 Secret 都填写完整且启用时按钮才显示。

## 自动注册

「首次第三方登录时自动创建本地账号」默认开启，但仍受以下限制：

- 必须同时开启 `特殊:权限配置 → 站点设置 → 启用注册`；
- 仍然经过 IP 创建封禁、禁用标题名单和插件注册钩子（如注册问答）；
- 如果第三方返回的已验证邮箱已被本地账号使用，不会自动合并，而是要求先登录后在「第三方授权」中绑定。

GitHub 返回已验证邮箱时会同步标记本地邮箱已确认；QQ 通常不返回邮箱。

## 数据与扩展

绑定表：`wiki_external_identity`（`provider + subject` 的 SHA-256 唯一键，一个本地账号可绑定多个 provider）。

新增 provider 只需在插件中实现 `cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider`，在 `onStart` 里调用 `context.register(ExternalAuthProvider.class, provider)`；配置界面、登录按钮和用户授权列表会根据 provider 声明自动渲染。\n\n当前内置的两个 provider 插件：\n\n- `plugin/external-auth-github`：GitHub OAuth App\n- `plugin/external-auth-qq`：QQ 互联\n\n它们和其他插件一样由 `wikikernel.plugins.enabled` 控制，可在运行时装卸；未加载时对应配置项和登录按钮不会出现。

