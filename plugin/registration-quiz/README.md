# registration-quiz（注册问答验证扩展移植）

注册页的**问答验证**：两个**等长数组**——问题 `registration.questions` 与答案 `registration.answers`，
`a[i]` 与 `b[i]` 按下标一一对应；注册页每次从题库里**随机抽一道**（并带回题目下标），
必须答对**那道题**才能注册（仿 MediaWiki 的 QuestyCaptcha 思路）。

```text
注册页里的插件字段：
1 + 1 = ?                    ← 随机抽到的问题
[ 答案 ]                     ← registerAnswer（隐藏字段 registerQuizIndex 带回题目下标）
```

## 扩展点

| 扩展点 | 用途 |
| --- | --- |
| `RegistrationFormField` | 注册表单里插入题目 + 隐藏的题目下标 + 答案输入框（字段名 `registerQuizIndex` / `registerAnswer`，与迁移前 core 模板一致） |
| `RegistrationGuard` | `POST /api/user/register` 按下标校验答案：答错 / 没带下标 / 下标越界 → `BLOCK("注册问答答案不正确，请重试。")`（core 返回 400 + 说明） |
| `MessageBundle` | `auth.registerAnswerHint` / `auth.registerAnswerWrong` 文案（键名与迁移前 core 相同；core 里已删除同名键） |

注册页的提交逻辑继续用 **core 的 `/js/auth.js`**：它用 `FormData` 全量提交表单，不依赖具体字段，
所以插件不需要自带脚本（也不需要自己的静态资源）。

## 配置（**仍留在 core**，插件通过 `common.service.SiteConfig` 读同一批键）

| 设置键 | 说明 | 默认 |
| --- | --- | --- |
| `features.registration-quiz` | 总开关 | **关闭** |
| `registration.questions` | 问题数组（换行分隔） | 空 |
| `registration.answers` | 答案数组（换行分隔，与问题数组等长） | 空 |
| `registration.question` | **旧格式**单问题（`questions` 为空时才用） | 空 |

- 表单与 `LocalSettings.yaml` 照旧可用：`/init` 安装向导里本插件的 tab（两个多行文本框）与
  「特殊:权限配置 → 站点设置 → 插件设置 → 注册问答」的两个多行文本框（问题 / 答案各一行一条）——
  分区与向导步骤都由插件注册（`RegistrationQuizSettingsSection` / `RegistrationQuizSetupStep`），
  core 里没有注册问答的设置代码（原来的行编辑器与 `js/registration-quiz.js` 已删除）；
- 键常量集中在插件 `RegistrationQuizSettings`
  （`QUESTIONS_KEY` / `ANSWERS_KEY` / `LEGACY_QUESTION_KEY` / `ENABLED_KEY` = `features.registration-quiz`）；
- 数量不等的**旧数据**：向导步骤会给出错误提示（`SetupResult.error`），
  已有的不等长数据按较短的长度配对（与插件读法一致）。

## 机制（从 core 的 `RegistrationQuizService` 整体迁来）

- 随机抽题并带回**下标**，注册时校验的是「那道题」的答案；
- 答案不区分大小写、忽略首尾空白（`" wikikernel "` = `WikiKernel`）；
- **不带题目下标 / 下标越界 / 非法下标 = 答错**（注册页总会带下标）；
- 开关关闭或题库为空 = 不门禁（注册页没有问题字段，注册接口也不校验）；
- **旧格式兼容**：`registration.questions` 为空时用 `registration.question`（单问题）+
  `registration.answers`（同题的多个可接受答案，`|` 或换行分隔）展开成「同一问题多行、每行一个可接受答案」，
  随机抽题不会因此偏向，语义与旧版完全一致；
- 管理员 / 行政员代为创建账号时同样要回答问题。

## 与 MediaWiki 原版的差异

- 这里对应的是 MediaWiki 的 QuestyCaptcha（问题 / 答案对）思路，而不是 ConfirmEdit 的图片 / reCAPTCHA 验证；
- MediaWiki 的 QuestyCaptcha 是「一个答案对应一个问题」，本实现保留 core 原有的**两个等长数组 + 下标校验**
  （同一问题可以配多个可接受答案，见上面的旧格式兼容），并且答案数组与问题数组**必须等长**；
- 题目答案直接存在站点设置里（数据库 `wiki_setting`），**不哈希**——管理员在设置页里明文可见
  （与迁移前一致；这是给「机器人难以自动回答」的轻量门禁，不是安全边界）；
- 未移植 MediaWiki 的注册验证码「随机提问 + 失败次数限制 / 封锁」机制（本实现答错只提示重试），
  也没有 `QuestyCaptcha` 的 `$wgQuestyCaptchaQuestions` 多语言题目分组。
