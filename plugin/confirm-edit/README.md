# confirm-edit（MediaWiki ConfirmEdit 扩展移植）

编辑 / 创建页面时的**算术验证码**：未豁免用户（含匿名）打开编辑页时，编辑框下方出现一道随机加法题
（题面与答案存会话，一次一题）；保存时由插件校验，答错回到编辑表单（**已填内容与摘要保留**）并换一道新题。

```text
编辑框下方的插件字段：
请回答验证题（防机器人）：  6 + 7 =   [ 答案（数字） ]
```

## 扩展点

| 扩展点 | 用途 |
| --- | --- |
| `EditFormField` | 编辑框下方插入题目 + 答案输入框（字段名 `captchaAnswer`，与迁移前 core 模板一致） |
| `EditGuard` | 保存前校验答案：答对 `ALLOW`；答错 / 没带答案 / 会话里没有题目 → `BLOCK("验证答案不正确，请重试。")` |
| `MessageBundle` | `confirmEdit.*` 文案（键名与迁移前 core 完全相同；core 里已删除同名键） |

保存前钩子**只拦网页编辑表单**：`api.php?action=edit` 的来源是 `EditContext.SOURCE_API`，
插件直接放行——与迁移前一致（ConfirmEdit 不影响 Action API）。

## 配置（**仍留在 core**，插件通过 `common.service.SiteConfig` 读同一批键）

| 设置键 | 说明 | 默认 |
| --- | --- | --- |
| `features.confirm-edit` | 功能开关 | **关闭** |
| `features.confirm-edit.bypass-groups` | 免验证用户组（`\|` 或换行分隔） | `管理员\|行政员\|自动确定用户\|机器人` |

- 三个入口与迁移前一致：`/init` 安装向导里本插件的 tab、「特殊:权限配置 → 站点设置 → 插件设置 →
  编辑验证码」、`LocalSettings.yaml` 的 `wikikernel.features.confirm-edit`（含 `enabled` / `bypass-groups`）——
  分区与向导步骤都由插件注册，键名不变；
- 站点设置里**显式留空**免验证组 = 无人豁免（所有人都要答题）；键不存在时用默认值；
- 插件读写同一批设置键（经 `common.service.SiteConfig` 读、启动导入时写；core 里没有 ConfirmEdit 代码）。

## 机制

- 题目：`1..9` 的两个随机加数（`6 + 7 =`），题面与答案存**会话**
  （键 `wikikernel.confirmEdit.question` / `wikikernel.confirmEdit.answer`，与迁移前相同）；
- 同一会话里重复渲染编辑页不会换题；**校验时无论对错都消耗题目**（防重放 / 防爆破），
  所以答错后回到编辑页会自动换一道新题；
- 答案宽容：忽略首尾空白与非数字字符（`" 2 分 "` = `2`）。

## 与 MediaWiki 原版的差异

- 只移植了**问答式 / 算术题**这一种验证方式（无图片验证码、无 reCAPTCHA / hCaptcha、无 FancyCaptcha），
  所以不需要图片生成、CDN 或密钥配置；
- 拦截范围比 MediaWiki 小：MediaWiki 的 ConfirmEdit 可以拦「创建账号 / 保存编辑 / 发邮件 / 加入链接」等
  多个动作（`$wgCaptchaTriggers`），这里只拦**网页保存编辑 / 创建页面**（`action=edit` / `create`）；
  `api.php`、上传等其它写操作不受影响；
- 没有 MediaWiki 的 `confirmedit` 用户组、`$wgCaptchaWhitelist` 正则白名单与编辑次数阈值
  （`$wgCaptchaTriggersOnNamespace` / `$wgCaptchaBadLoginAttempts` 等），免验证只按用户组；
- 题面存在会话里（MediaWiki 也存在 session），因此**多标签页 / 刷新**会共用同一道题，
  而服务器重启 / 会话失效后需要重新答题（此时保存会提示答错并给出新题）。
