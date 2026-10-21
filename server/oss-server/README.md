# oss-server

WikiKernel 独立 OSS 服务端（Spring Boot，Java 21）。只使用 SQLite 保存元数据，原图和缩略图存放在磁盘目录；公开读取不需要密钥，写接口需要共享密钥。

## 启动

```powershell
.\gradlew.bat :server:oss-server:bootJar
java -jar server\oss-server\build\libs\oss-server.jar
```

默认监听 `http://127.0.0.1:18081`，工作目录下创建：

- `data/oss.db`：SQLite 元数据
- `data/oss-files/`：原图
- `data/oss-files/thumbnails/`：WebP 缩略图缓存

需要 ImageMagick 7 的 `magick` 命令（Windows 默认会尝试常见安装路径；也可以配置 `oss.magick`）。生产环境建议通过反向代理 / CDN 暴露只读路径：

- `GET /files/{storedName}`：原图（含历史版本；软删除后 404）
- `GET /thumbnails/{storedName}/half.webp`
- `GET /thumbnails/{storedName}/quarter.webp`

## 配置

`server/oss-server/src/main/resources/application.yaml` 或启动参数 / 环境变量：

```yaml
server:
  port: 18081
oss:
  key: "请改成足够长的随机字符串"
  storage-dir: ./data/oss-files
  magick: ""                 # 空 = 自动查找；也可写 magick.exe 绝对路径
  thumbnail-quality: 82
  public-base-url: http://127.0.0.1:18081
  cdn-base-url: ""           # 留空 = 使用 public-base-url
```

扩展配置里的 `key` 必须与本服务端一致。读取路径不需要密钥，写接口使用 `X-OSS-Key` 请求头。

## 写接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `PUT` | `/api/files/{storedName}?name=...&contentType=...&fileId=...&version=...&current=true` | 上传 / 覆盖一个原图版本 |
| `DELETE` | `/api/files/{storedName}` | 永久删除一个原图版本及缩略图 |
| `PATCH` | `/api/files/by-id/{fileId}/deleted?deleted=true` | 软删除 / 恢复同一文件全部版本 |
| `GET` | `/api/health` | 健康检查（无需密钥） |

服务端在一次上传时会将该 `fileId` 的旧版本标记为非当前版本，但不会删除旧原图，历史版本仍可读取。

## 缓存与 CDN

原图响应带 `Cache-Control: public, max-age=31536000, immutable` 和 `Access-Control-Allow-Origin: *`。缩略图首次请求时生成并写入 SQLite 索引与磁盘缓存；后续直接读缓存。CDN 只需要回源上述两个 GET 路径，写接口不要暴露到公网。
