# OSS Storage 扩展

把 Wiki 的原图（含历史版本）同步到独立 `oss-server`，缩略图由 OSS 服务端生成并缓存。扩展通过 core 新增的 `FileUrlResolver` 改写页面里的文件 URL；OSS 不可用时自动回退本地。

## 配置

在 `LocalSettings.yaml` 的 `wikikernel.plugins.settings` 下增加（默认关闭，确认服务端已启动后再开启）：

```yaml
wikikernel:
  plugins:
    settings:
      oss-extension:
        enabled: true
        # 写接口地址（扩展上传 / 删除 / 软删除用）
        operation-url: http://127.0.0.1:18081
        # 用户浏览器读取地址（可指向 OSS 直连域名或反向代理）
        read-url: http://127.0.0.1:18081
        # 可选 CDN 只读域名；非空时覆盖 read-url
        cdn-url: ""
        # 与 oss-server 的 oss.key 一致
        key: change-me
        # thumbnail = 原图仍走 Wiki，仅缩略图走 OSS
        # all       = 原图和缩略图都走 OSS；OSS 不可用时原图 404，缩略图本地回退
        mode: thumbnail
        # 首次启用时把现有文件和历史版本排入失败队列（完成后写 outbox/migration.done）
        migrate-existing: true
```

插件在 `onStart` 时只负责把变更写入 `data/plugins/oss-extension/outbox/` 下的 properties 小文件，真正的上传由后台虚拟线程完成。单个文件上传失败不会影响 Wiki 保存；OSS 恢复后自动重试。

## 行为

- **任务顺序**：上传 / 删除都带单调序号，避免同一毫秒内的“先上传后删除”被乱序执行。
- **历史版本**：原图按每次上传的 `storedName` 分别保存；新版本不会覆盖旧版本。
- **缩略图**：OSS 服务端按需生成 `half.webp` / `quarter.webp` 并缓存；历史原图不预生成缩略图。
- **软删除 / 恢复**：通过服务端 `by-id/{fileId}/deleted` 统一隐藏 / 恢复该文件所有版本。
- **不可用回退**：
  - `thumbnail` 模式：原图继续由 Wiki 提供；缩略图由 core 在本地按需生成。
  - `all` 模式：原图接口返回 404；缩略图仍会回退本地生成。
- **CDN**：`read-url` / `cdn-url` 只影响浏览器读取 URL；写接口始终使用 `operation-url`。

## 构建

```powershell
.\gradlew.bat :plugin:oss-extension:jar
```

把 `plugin/oss-extension/build/libs/oss-extension-*.jar` 放到根目录 `plugins/`，或运行 `gradlew installPlugins`。
