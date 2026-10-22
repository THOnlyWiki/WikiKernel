# registration-quiz（注册问答验证扩展移植）

注册页的**问答验证**：推荐 `registration.quiz`，一行一道题，题目和该题的多个答案用 `|` 分隔；
同一题可以配置多个可接受答案。注册页每次从题库里**随机抽一道**（并带回题目下标），
答对该题任意一个答案即可注册（仿 MediaWiki 的 QuestyCaptcha 思路）。

```text
注册页里的插件字段：
1 + 1 = ?                    ← 随机抽到的问题
[ 答案 ]                     ← registerAnswer（隐藏字段 registerQuizIndex 带回题目下标）
```

```text
配置示例（一行一题：问题 | 答案1 | 答案2）：
1 + 1 = ? | 2 | 二
本 wiki 叫什么名字？ | WikiKernel | wikikernel
```

## 扩展点

| 扩展点 | 用途 |
| --- | --- |
| `RegistrationFormField` | 注册表单里插入题目 + 隐藏的题目下标 + 答案输入框（字段名 `registerQuizIndex` / `registerAnswer`，与迁移前 core 模板一致） |
| `RegistrationGuard` | `POST /api/user/register` 按下标校验答案：答错 / 没带下标 / 下标越界 → `BLOCK("注册问答答案不正确，请重试。")`（core 返回 400 + 说明） |
| `MessageBundle` | `auth.registerAnswerHint` / `auth.registerAnswerWrong` 文案（键名与迁移前 core 相同；core 里已删除同名键） |

注册页的提交逻辑继续用 **core 的 `/js/auth.js`**：它用 `FormData` 全量提交表单，不依赖具体字段，
所以插件不需要自带脚本（也不需要自己的静态资源）。

## 配置（插件通过 `common.service.SiteConfig` 读取）

| 设置键 | 说明 | 默认 |
| --- | --- | --- |
| `features.registration-quiz` | 总开关 | **关闭** |
| `registration.quiz` | 推荐格式：每行 `问题 | 答案1 | 答案2` | 空 |
| `registration.questions` | 旧格式问题数组（每行一题） | 空 |
| `registration.answers` | 旧格式答案数组（每行一题，与问题数组等长） | 空 |
| `registration.question` | 更早的旧格式单问题（`questions` 为空时才用） | 空 |

- **设置页**：「特殊:权限配置 → 站点设置 → 插件设置 → 注册问答」只有一个问答 textarea，
  旧格式会自动转换成新格式显示；`/init` 安装向导同样使用这个单 textarea。
  分区与向导步骤都由插件注册（`RegistrationQuizSettingsSection` / `RegistrationQuizSetupStep`）。
- 键常量集中在插件 `RegistrationQuizSettings`
  （`QUIZ_KEY` / `QUESTIONS_KEY` / `ANSWERS_KEY` / `LEGACY_QUESTION_KEY` / `ENABLED_KEY`）；
- 新格式每行至少要有「问题 | 答案」；格式不完整的行会被忽略，向导会直接给出错误提示。
- 数量不等的旧数据仍按较短的长度配对，避免升级后直接失效。

## 机制（从 core 的 `RegistrationQuizService` 整体迁来）

- 随机抽题并带回**下标**，注册时校验的是「那道题」的答案；
- 每个问题可以有多个可接受答案，答对任意一个即可；
- 答案不区分大小写、忽略首尾空白（`" wikikernel "` = `WikiKernel`）；
- **不带题目下标 / 下标越界 / 非法下标 = 答错**（注册页总会带下标）；
- 开关关闭或题库为空 = 不门禁（注册页没有问题字段，注册接口也不校验）；
- **旧格式兼容**：两个旧数组，以及 `registration.question` + `answers`（`|` 或换行分隔）；
- 管理员 / 行政员代为创建账号时同样要回答问题。

## 与 MediaWiki 原版的差异

- 这里对应的是 MediaWiki 的 QuestyCaptcha（问题 / 答案对）思路，而不是 ConfirmEdit 的图片 / reCAPTCHA 验证；
- MediaWiki 的 QuestyCaptcha 是「一个答案对应一个问题」，本实现使用**一行一题 + 任意多个可接受答案**
  （`问题 | 答案1 | 答案2`），并保留旧的两个等长数组 + 下标校验兼容；
- 题目答案直接存在站点设置里（数据库 `wiki_setting`），**不哈希**——管理员在设置页里明文可见
  （与迁移前一致；这是给「机器人难以自动回答」的轻量门禁，不是安全边界）；
- 未移植 MediaWiki 的注册验证码「随机提问 + 失败次数限制 / 封锁」机制（本实现答错只提示重试），
  也没有 `QuestyCaptcha` 的 `$wgQuestyCaptchaQuestions` 多语言题目分组。
