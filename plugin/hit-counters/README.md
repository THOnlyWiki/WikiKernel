# hit-counters（MediaWiki HitCounters 扩展移植）

页面浏览计数 —— 对应 MediaWiki 的 [HitCounters](https://www.mediawiki.org/wiki/Extension:HitCounters)
扩展（第三方扩展）。

- 插件 id：`hit-counters`；模块：`:plugin:hit-counters`；入口类 `HitCountersPlugin`；
- 本地化文案：`HitCountersMessages` + `i18n/messages.properties` / `messages_en.properties`；
- 构建：`.\gradlew.bat :plugin:hit-counters:build`。

## 功能

| 部分 | 说明 |
| --- | --- |
| 计数 | `HitCountersViewHook`（`PageViewHook`）：词条阅读（非 diff / 旧版本）时给页面 +1，并用 `registerDurable` 登记持久属性，页面重新解析保存时不覆盖 |
| 魔术字 | `PageViewsMagicWord` = `{{NUMBEROFPAGEVIEWS}}`、`TotalViewsMagicWord` = `{{NUMBEROFVIEWS}}`（页面 / 全站浏览量） |
| 页脚 | `HitCountersFooterHook`：正文末尾追加「本页面已被访问 N 次。」（消息键 `hitcounters.footer`） |
| 特殊页 | `PopularPagesSpecialPage` = `特殊:最多浏览`（按浏览量排序，可显示页面 ID / 字节数） |
| 安装向导 | `HitCountersSetup`（`SetupStep`）：`/init` 的插件 tab 里配置下面的各项 |
| 数据 | 计数落页面属性（`common.service.PagePropertyStore`：`addToInt` / `sumInt` / `listByIntDesc`） |

## 配置（`LocalSettings.yaml` → `wikikernel.plugins.settings.hit-counters`）

也可以在 `/init` 的插件 tab 或「特殊:权限配置 → 站点设置 → 插件设置」里改：

```yaml
wikikernel:
  plugins:
    settings:
      hit-counters:
        disabled: false               # 关闭浏览计数
        footer: true                  # 正文末尾显示「本页面已被访问 N 次」
        enable-add-page-id: false     # 特殊:最多浏览 显示页面 ID
        enable-add-text-length: false # 特殊:最多浏览 显示字节数
        exempt-groups: ""             # 豁免计数的用户组（逗号分隔；机器人始终不计数）
```

描述文件里**没有**声明 `plugin.featureKey`：本插件没有单一的 `features.*` 站点开关，开关粒度就是上面的设置项
（`disabled: true` 等价于关闭浏览计数）。

## 与 MediaWiki 的差异

- `$wgHitcounterUpdateFreq` 的批量合并未实现（每次浏览立即 +1）；
- 页脚计数显示在正文末尾；`特殊:统计` 的浏览区块未加。

## 相关文档

- [配置与数据](../../docs/CONFIG.md)（`LocalSettings.yaml`、站点设置、页面属性）
- [插件开发与运行](../../docs/PLUGIN-DEV.md)（`PageViewHook` / `MagicWord` / `SetupStep` 扩展点）
