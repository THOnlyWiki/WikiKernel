# SocialProfile 原版对照（2026-11-04）

对照 Wikimedia 官方仓库 master 快照 `8b20cc46fec9b6a2d177dfafd5de49b2768f1c68`；以实际实现为准，不把旧 README 的描述当成现状。

来源：[扩展说明](https://www.mediawiki.org/wiki/Extension:SocialProfile)、[官方源码](https://github.com/wikimedia/mediawiki-extensions-SocialProfile/tree/8b20cc46fec9b6a2d177dfafd5de49b2768f1c68)。

| 原版能力 | WikiKernel 当前状态 |
| --- | --- |
| 资料页头像、积分、等级、动作链接 | 已有；保留原版 DOM 类名和 75px 头像内容尺寸 |
| 左侧统计 / 关系 / 礼物 / 勋章，右侧资料 / 用户墙 | 已有 35% / 60% 分栏；本次补正文容器窄屏布局和长文字换行 |
| 好友请求 / 接受 / 解除，仇敌 | 已有；好友双向，仇敌单向 |
| 留言板 / 回复 / 删除、礼物、积分、勋章 | 已有，不重复重做 |
| `{{#avatar:用户名|s/m/ml/l/32px}}` | 本次新增；原版尺寸为 16 / 30 / 50 / 75px，显式像素上限 512 |
| `<newusers count="10" row="5"/>` | 本次新增；最近注册用户，过滤不可公开阅读的用户页 |
| `<randomuserswithavatars count="10" row="4" size="ml"/>` | 本次新增；只抽取上传过头像的用户 |
| `#newusers` / `#randomuserswithavatars` | 同时支持函数写法，参数可用 `count=`、`row=`、`size=` |
| `Special:RemoveAvatar` | 本次新增 `特殊:删除头像` / `Special:RemoveAvatar`；本人可删除自己的头像，移除他人头像由 `特殊:删除头像` 的 protect 权限控制，默认管理员 |
| 站点默认头像 | 已有共享设置；本次修正头像查询 API，移除头像后也使用该设置 |

动态头像 / 用户列表声明 `cache-expiry=0`，避免正文缓存使新头像或用户列表长期不刷新；限制每次最多 48 位用户，不增加后台轮询。
头像文件写入前校验栅格文件签名；PNG/JPEG/GIF 读取尺寸元数据，拒绝超大图。保留 WebP 上传兼容性（JDK 本身不提供 WebP 解码器）。删除时比较原头像文件名，遇到并发替换不清空新头像。

## 布局验证原则

桌面继续保留原版 35% / 60% 栏宽、75px 主头像、30px 留言头像、等级徽章与区块标题结构。
适配 WikiKernel 全局 border-box 时，头像仍按原版 content-box 计算边框与内边距。
正文宽度不超过 620px 时两栏改为纵向排列，留言正文紧邻头像且长 URL 可换行；主题深色模式不使用固定的浅色留言背景。

## 尚未完整移植

- UserWiki / UserProfile 命名空间及原版用户页 / 社交资料切换。
- BoardBlast 群发留言、完整邮件通知和原版 SocialProfile 专用 Echo 事件类型。
- 原版礼物 / 系统奖励图片上传管理流程；当前礼物目录与勋章管理是 WikiKernel 实现。
- 服务器物理生成 s/m/ml/l 多尺寸头像文件：当前用原图和限定显示尺寸。
- 原版全部扩展资料字段、UserPage DIY 版式及外围扩展集成。

以上是可核验的剩余差异，不表示与原版完全等同。
