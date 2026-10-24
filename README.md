# 慧生活798 · 独立重制客户端

独立重制的中文 Android 客户端，包名 `com.smartlife798.app`，直接访问原服务端 `https://i.ilife798.com`。无官方授权、不含官方源码；界面使用 Kotlin、Jetpack Compose 与 Material 3。

| | |
|---|---|
| 新客户端包名 | `com.smartlife798.app` |
| 当前版本 | `1.9.7`（`versionCode 38`） |
| 技术栈 | Kotlin 2.2 · Jetpack Compose (Material 3) · OkHttp · CameraX + ML Kit · Alipay / WeChat SDK |
| 最低版本 | Android 7.0（API 24） |
| 最新已发布 | [1.9.6 APK](https://github.com/wu-shanglin/Smartlife798/releases/download/v1.9.6/SmartLife798-1.9.6-debug.apk) · [Release 说明](https://github.com/wu-shanglin/Smartlife798/releases/tag/v1.9.6) |

客户端发布版本、官方协议请求标识与静态 APK 基线相互独立。运行时从已核验包名和开发者的原应用分发页发现版本，失败保留缓存及内置保底；这不代表新协议或设备兼容性已验，见 [协议维护](docs/04-接口契约与变更检测.md#6-原-apk-版本与更新通道)。

## 当前版本

- **1.9.7：详情加载与更新进度**。合并详情首载期间的刷新，避免取消重发；更新进度固定在弹窗按钮上方，仅显示进度和状态，不随日志滚动。已准备普通更新，最低支持码保留 37；正式发布前线上仍为下述 1.9.6 策略。
- **1.9.6：强制更新返回退出**。返回退出应用并保留更新限制，重新打开仍须更新；下载忙碌时也可退出，外部点击不能绕过。普通更新返回只关闭弹窗。旧 APK 的返回行为需升级后才能修复。
- **1.9.5：运行卡片与恢复**。返回首页继续确认启动；未知结果保留「启动待确认」入口，确认后显示运行卡片。详情、首页和刷新统一对账，避免异设备、旧响应和重复启停污染会话。
- 支付宝回执只保留白名单状态与固定提示；工程验证已覆盖，真实支付、物理出水及非零计费仍待验，详情见 [验证记录](docs/05-真实支付与计费实测.md#9-工程验证记录)。
- **已正式发布并强制更新**：最新版本码与最低支持版本码均为 **37**；低于 37 且已集成更新功能的安装需升级，达到 37 后解除。可从应用内下载、校验并交系统覆盖安装，无需卸载；无更新功能或尚未获得策略的离线安装不受此客户端策略控制。
- [正式 APK](https://github.com/wu-shanglin/Smartlife798/releases/download/v1.9.6/SmartLife798-1.9.6-debug.apk) · [本地保留](dist/SmartLife798-1.9.6-debug.apk) · [GitHub latest](https://github.com/wu-shanglin/Smartlife798/releases/latest)。后续发布须保留已确认的最低支持范围，步骤见 [GitHub 更新发布](docs/06-工程规范与路线图.md#github-更新发布)。

## 功能与边界

登录、收藏、扫码/手输编号、设备状态与启停、消费账单、钱包充值及支付宝/微信 SDK 已实现；执行、金额和支付结果由服务端决定。微信另受开放平台包名、签名与 appId 配置约束。常用/最近设备、搜索、置顶、启动页和外观偏好属于客户端能力。

范围为中文、手机竖屏；不扩展门禁硬件、考勤、云闪付/银行 SDK 及原应用独立业务。完整页面与功能清单见 [docs/02](docs/02-功能设计与UI方案.md)，已实现不等于真实业务验收通过。

## 维护入口

先读 [AGENTS.md](AGENTS.md)。日常任务按任务分支、验证、Commit、PR/CI、合并及清理执行；打 tag、发布和真实业务操作仍分别授权。

| 需要核对 | 唯一维护位置 |
|---|---|
| 版本历史 | [CHANGELOG](CHANGELOG.md) |
| 原 APK 与协议静态依据 | [docs/01](docs/01-逆向分析报告.md) |
| 页面、交互、视觉与截图来源 | [docs/02](docs/02-功能设计与UI方案.md) |
| 技术选型、运行机制与构建环境 | [docs/03](docs/03-技术方案与开发计划.md) |
| 接口契约、版本发现与漂移检测 | [docs/04](docs/04-接口契约与变更检测.md)、[api-contract.json](docs/api-contract.json) |
| 实测、工程验证及未验边界 | [docs/05](docs/05-真实支付与计费实测.md) |
| 检查命令、存储、发布、待办和 Git | [docs/06](docs/06-工程规范与路线图.md) |

## 构建与产物

唯一 Android 工程为 `SmartLife798/`；先按 [构建与运行](docs/03-技术方案与开发计划.md#4-构建与运行) 准备 JDK/SDK、本机 `local.properties`，再按 [检查与证据](docs/06-工程规范与路线图.md#2-检查与证据) 执行适用检查。可选 release 签名及产物准备见 [版本与发布](docs/06-工程规范与路线图.md#3-版本与发布)。

| 本地构建 | 文件 |
|---|---|
| 产物 | [`dist/SmartLife798-1.9.7-debug.apk`](dist/SmartLife798-1.9.7-debug.apk) |

原 APK、静态分析缓存、`evidence/` 及 APK 不入库。原样本、签名、本机配置、当前交付和独有证据须保留；清理规则见 [文档与目录维护](docs/06-工程规范与路线图.md#4-文档与目录维护)。

## 截图

截图来源、版本及限制统一见 [截图清单](docs/02-功能设计与UI方案.md#7-界面截图)；以下为历史演示页面，不能作为当前版本或真实业务验收。

| 登录 | 首页 | 扫码 |
|---|---|---|
| ![1.8.0 登录页](screenshots/login.png) | ![1.8.0 首页演示](screenshots/home.png) | ![1.8.0 虚拟相机扫码页](screenshots/scan.png) |

| 手动输入 | 设备 | 设备详情 |
|---|---|---|
| ![1.8.0 手动输入](screenshots/manual.png) | ![1.8.0 设备列表演示](screenshots/devices.png) | ![1.8.0 设备详情演示](screenshots/device.png) |

| 消费记录 | 待付款筛选与角标 | 我的 |
|---|---|---|
| ![1.8.0 消费记录演示](screenshots/orders.png) | ![1.8.0 待付款演示](screenshots/preview_bills.png) | ![1.9.2 我的设置卡片演示](screenshots/me.png) |

| 深色首页 | 外观（跟随系统深色） |
|---|---|
| ![1.8.0 深色首页演示](screenshots/dark.png) | ![1.9.2 深色设置卡片演示](screenshots/appearance_dark.png) |
