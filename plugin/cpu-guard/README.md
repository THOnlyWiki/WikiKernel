# CPU Guard：Debian JVM 锁核与 CPU 限额

适用于需要控制 JVM 长期 CPU 使用量的 Debian / systemd / cgroup v2 部署。
插件只依赖 `common`，不依赖 core 或其它插件。默认预设针对 **2 vCPU VPS**：绑定 1 核，
按 **单核 25%** 限额，即整台 2 核 VPS 的约 **12.5%**。确认提供商按整机统计后，
可以切换为 **整机 25%**，保留低于 30% 的余量。

CPU 亲和性限制能使用哪些核心；`cpu.max` 才限制整个 JVM cgroup 的 CPU 时间预算。
GC、JIT、请求和后台线程均受配额约束。`-XX:ActiveProcessorCount=1` 用来减少 JVM 自行建立的并行度，
不能单独作为 CPU 使用率保证。

## 安装与推荐启动方式

1. 将压缩包 `plugins/cpu-guard-1.0.0.jar` 放入 WikiKernel 的 `plugins/` 目录。
2. 将 `deploy/` 放到部署目录；从包含 `LocalSettings.yaml` 的 WikiKernel 部署目录运行启动脚本。
3. 生产环境使用附带的 systemd 启动方式，在 JVM 启动之前就施加配额。

```bash
# 默认：2 vCPU，绑定 1 核，单核 25%（整机约 12.5%）
bash deploy/run-limited.sh ./wikikernel-server.jar

# 已确认按整机统计时：2 vCPU，整机 25%，绑定 1 核
CPU_GUARD_BASIS=machine CPU_GUARD_PERCENT=25 CPU_GUARD_MACHINE_CPUS=2 \
  bash deploy/run-limited.sh ./wikikernel-server.jar --server.port=18080
```

脚本依赖 systemd、Bash、Java 21+、cgroup v2 的 cpu / cpuset 控制器。
root 使用系统实例；普通用户使用 `systemd-run --user`，需要已运行的用户 systemd 管理器和相应控制器授权。
`systemd-run` 无法建立受限 scope 时脚本会退出，Java 不会随后改成无限制启动。
脚本不会自动执行 sudo，不更改系统全局配置；`JAVA_BIN` 可指定 Java 可执行文件，
额外 JVM 参数可通过 Java 自带的 `JAVA_TOOL_OPTIONS` 传入。

单独热加载插件发生在 Spring 启动之后，不能覆盖 JVM 最初的启动峰值。
因此生产应使用启动脚本或下方 systemd 服务模板；插件加载失败、被关闭或卸载时，外层限制仍然存在。

## systemd 长期运行

`deploy/wikikernel-cpu-guard.service` 提供自启动模板。安装前确认：

- `User` / `Group` 是已经创建、能读写部署目录的服务账号；默认示例为 `wikikernel`。
- `WorkingDirectory` 与 JAR 路径改成实际部署位置，Java 路径正确。
- `AllowedCPUs=0` / `CPUAffinity=0` 改成系统实际允许的 CPU ID，可用 `grep Cpus_allowed_list /proc/self/status` 检查。
- 模板默认 `CPUQuota=25%`，是 **单核 25%**；确认整机口径后，2 vCPU 的整机 25% 应改为 `CPUQuota=50%`，
  并将 ExecStart 的 `-Dwikikernel.cpu-guard.basis=single-core` 同步改为 `machine`。

```bash
# 编辑好模板后自行安装；不会由插件自动覆盖已有服务
sudo cp deploy/wikikernel-cpu-guard.service /etc/systemd/system/wikikernel-cpu-guard.service
sudo systemctl daemon-reload
sudo systemctl enable --now wikikernel-cpu-guard.service
```

模板以普通服务账号运行。systemd 在 JVM 启动前设置配额和亲和性；运行账号没有 cgroup 写权限时，
插件仍可识别已生效的、更严格的系统配额，但无法把网页里更低的目标值写入内核。
这种情况下通过 systemd 修改上限。无需为了插件给整个 JVM 授予 root 权限。

## 网页设置与 YAML

入口：**特殊:权限配置 → 站点设置 → CPU 限额与锁核**。
变更最多约 5 秒应用一次；页面摘要显示内核配额、核心绑定、当前 JVM CPU 用量和近 60 秒加权平均。
监控线程是单个后台线程，不通过暂停全部 Java 线程或暂停 JVM 来模拟限速。

| 站点设置键 | 插件 YAML 键 | 默认 | 说明 |
| --- | --- | --- | --- |
| `cpu-guard.enabled` | `enabled` | `true` | 启用限制；关闭会尝试恢复插件自己改过的值 |
| `cpu-guard.percent` | `percent` | `25` | 整数 1–30；非法值回退到 25 |
| `cpu-guard.basis` | `basis` | `single-core` | `single-core` 单核口径；`machine` 整机口径；非法值回退单核 |
| `cpu-guard.locked-cores` | `locked-cores` | `1` | 绑定允许列表中前 N 个 CPU；0 表示只限额；超过可用数时取可用数 |
| `cpu-guard.machine-cpus` | `machine-cpus` | `2` | VPS vCPU 总数；0 自动；不能大于检测到的在线 CPU 数 |

```yaml
wikikernel:
  plugins:
    settings:
      cpu-guard:
        enabled: true
        percent: 25
        basis: single-core
        locked-cores: 1
        machine-cpus: 2
```

站点设置优先于插件 YAML，YAML 优先于启动器传入的同名 `-Dwikikernel.cpu-guard.*` 默认值。
插件不会放宽启动器或管理员已经施加的更严格配额。外层启动上限是最终天花板，
例如外层单核 25% 时，网页切换到整机 25% 也不能超过外层；要提高这一上限，需要同步调整启动配置。

## 2 核 VPS 百分比换算

| 模式 | 目标 | systemd CPUQuota | 理论整机 CPU 预算 |
| --- | --- | --- | --- |
| 单核口径，默认 | 25% | 25% | 12.5% |
| 整机口径 | 25% | 50% | 25% |
| 整机口径 | 28% | 56% | 28% |
| 整机口径 | 30% | 60% | 30%，没有统计误差余量 |

这里的 systemd 百分比以一颗逻辑 CPU 为 100%；核心绑定可能进一步降低实际可用上限。
CPU 时间配额限制长期调度预算，短窗口采样可能抖动，不能把某次瞬时读数当作长期违规。
默认预留余量，但具体提供商统计口径仍需以其规则为准。
配额约束当前 JVM 及其 cgroup 内子进程；其他服务和操作系统的 CPU 使用量不属于该 JVM 的限制范围。
插件的采样数字只计 JVM 进程本身，子进程仍受 cgroup 配额约束。

## 生效检查与权限边界

```bash
# 替换为 Java PID，读取内核实际值
PID=12345
CG=$(awk -F: '$1=="0" {print $3}' /proc/$PID/cgroup)
cat "/sys/fs/cgroup$CG/cpu.max"
cat "/sys/fs/cgroup$CG/cpuset.cpus.effective"
# 持续观测，而非只看启动时的单次读数
systemd-cgtop
```

- 插件只修改包含当前 JVM、且没有其它服务进程和子 cgroup 的独占叶子 cgroup。
  SSH 会话、根 cgroup、共享 Docker 容器等会被拒绝，并报告“限制未生效”。
- 非 root 账号通常无权直接改 cgroup；使用外层 systemd 配额，或由管理员给独立 cgroup 正确授权。
  权限不足不会被显示成已成功限制。
- cpuset 不可用时 CPU 配额仍可单独生效，界面会说明绑定失败。
- 卸载只恢复插件自己改过、且当前仍等于最后写入值的项目；不会覆盖管理员随后修改的配置。
- 当前支持 Linux cgroup v2；其他系统显示监控状态与未生效原因。

## 构建与测试

```powershell
.\gradlew.bat :plugin:cpu-guard:test :plugin:cpu-guard:bundle checkPluginDependencies
```

产物：`plugin/cpu-guard/build/libs/cpu-guard-1.0.0.jar` 与
`plugin/cpu-guard/build/distributions/cpu-guard-1.0.0.zip`。
自动化测试使用临时 cgroup 文件，覆盖百分比换算、核心列表、权限不足、独占检查、原值恢复及指标采样。
Debian 的实际 systemd / 内核执行效果需要部署后按上面的生效检查验证。

参考：[Linux cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html)、
[systemd.resource-control](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html)。
