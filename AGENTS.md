# AGENTS.md — 慧生活798 开发约束

本文件是 AI 与贡献者的入口。改动前读完本文件；操作细节见 [工程规范](docs/06-工程规范与路线图.md)，接口依据见 [契约](docs/api-contract.json)。

## Codex 默认工作流

本项目日常实现、修复、文档和维护默认自主走完「任务分支开发 → 本地验证与 Commit → Push 并创建 PR → PR CI 通过 → 合并 main → 拉取最新 main → 删除本地开发分支和本任务临时工作树」。这是持续授权，无需逐项询问；用户当次要求只分析、暂不提交或指定分支时，以当次要求为准。打 tag、发布 Release、上传 APK 及真实业务操作仍需单独授权。

1. **检查与隔离**：先查 `git status --short`、当前分支、工作区/暂存区差异和最近提交。保护用户已有改动，不自动 stash、覆盖、改写历史或强推。工作区不干净时优先用 worktree 隔离。
2. **分支开发**：新任务从已核对的最新 `origin/main` 建立 `feat/`、`fix/`、`refactor/`、`docs/` 或 `chore/` 分支；同一任务复用分支，不继承其他任务独有提交。只读任务不建分支。不直接在 main 实施修改。
3. **实现与提交**：完成实现、受影响文档及 CHANGELOG，运行必要检查；仅暂存本任务明确路径或片段，复核实际暂存快照及 `git diff --cached --check` 后自动 Commit。检查失败先修复，不提交普通完成状态。
4. **PR 与 CI**：Push 任务分支，创建目标为 main 的 PR，写明最终行为、验证及未验项。等待 PR 当前最新 head 的适用 CI 全部成功；必要检查失败、取消、缺失或未执行都不能当作通过。修改后重新等待新 head 的 CI，不凭历史 PASS、绕过保护或直接推 main 完成合并。
5. **合并与同步**：CI 通过后自动合并 PR，合并时绑定已验证的 head SHA。获取远端最新 main，在干净工作区执行 `git pull --ff-only`；发生冲突、分叉或存在用户改动时保留现场，使用隔离工作树同步并报告，不自动 rebase 或强制覆盖。
6. **清理与收尾**：核实 PR 已合并、本地 main 与远端一致，再删除本地开发分支及本任务临时工作树。清理前确认工作树无未提交/未跟踪工作，并将需保留的 APK 或证据移至持久位置；不删除其他任务分支或工作树。远端分支删除按用户明确要求执行。

最终简报写明变更、验证、文档、PR/CI/合并链接、main 提交、清理结果，以及保留的未提交内容与原因。操作细节见 [Git 管理与提交](docs/06-工程规范与路线图.md#6-git-管理与提交)。

## 1. 项目与事实来源

- 唯一 Android 工程：`SmartLife798/`；包名 `com.smartlife798.app`。
- 独立重制、无官方授权，直接访问 `https://i.ilife798.com`；不包含官方源码。
- 产品范围：中文、手机竖屏。不主动扩展平板/折叠屏、国际化、门禁硬件及原应用的独立业务。
- 构建版本、依赖、SDK 以 Gradle 文件为准；不要在本文件复制当前版本或测试数量。
- 客户端发布版本与服务端协议版本标识是两回事，不随发版一起修改请求头；运行时优先采用原应用分发页中包名与开发者匹配的最新版本，失败回退安装级缓存与 `Api.CLIENT_VERSION` 内置保底；更新本地保底仍须核对原 APK 与相关链路。版本发现不等于协议兼容性验收，更新通道与排查流程见 docs/04。
- 配置普通／强制更新前必读 [GitHub 更新发布](docs/06-工程规范与路线图.md#github-更新发布)，按其中的生成、发布和恢复步骤操作；修复 APK 可下载且签名可覆盖前，不启用强制策略。
- 服务端协议以契约、原 APK 静态依据和脱敏实测交叉确认。代码与文档冲突时先定位依据，不凭猜测改协议。

| 资料 | 按任务读取 |
|---|---|
| [README](README.md) / [CHANGELOG](CHANGELOG.md) | 当前交付 / 历史变更 |
| [docs/01](docs/01-逆向分析报告.md) | 协议、设备状态机、原 APK 依据 |
| [docs/02](docs/02-功能设计与UI方案.md) | 页面、导航、页面态、截图清单 |
| [docs/03](docs/03-技术方案与开发计划.md) | 技术选型、运行机制、验证边界 |
| [docs/04](docs/04-接口契约与变更检测.md) | 契约与逆向工具 |
| [docs/05](docs/05-真实支付与计费实测.md) | 启停/支付证据、状态码、外部验收限制 |
| [docs/06](docs/06-工程规范与路线图.md) | 存储、评审、发布、待办 |

## 2. 实施范围

- 用 `rg` 搜索符号、调用方、测试与文档引用后再改。普通技术选择自行处理；业务含义、真实操作授权或协议依据不清时指出具体缺口。
- 不主动重写架构、升级依赖、恢复撤回功能或整理无关代码。删除前核对引用与恢复方式；不使用 `reset --hard`、`clean -fdx` 覆盖现场。
- 本文件定义改动必须遵守的约束，不代表现有实现均已达标。已知缺口见 docs/06 的待办，遇到时按本次范围处理，不顺带扩大任务。

## 3. 不可突破的边界

- 支付、账单和设备执行结果只认服务端；SDK 回调成功仍需 `trans/confirm`。超时/受理/取消不能报成功。
- 不绕过鉴权、不内置真实账号或 token、不复用原 APK 签名私钥。
- `Preview.enabled` 时不联网、不写 SharedPreferences、不创建真实进行中记录、不拉起支付/签约 SDK 或更新下载/安装；新增路径必须验证演示隔离。
- 日志、异常、截图和提交不得包含 token、手机号、完整签名订单串、支付签名或真实账号资料；SDK 原始 `result`、任意异常消息也不能直接当作安全日志。
- 涉及扣费、创建账单、支付/签约、启停的操作需要入口防重入和 `finally` 释放状态；仅按钮 `enabled=false` 不足。不得自动重试可能产生副作用的请求。
- 本地测试不自动操作真实账号、发短信、建充值单、支付/签约或启停真实设备。执行前须有用户对该具体业务操作的授权，不能把 GET 当成只读。
- 不提交 APK、构建缓存、逆向产物、账号证据、签名材料或机器路径。不得关闭 TLS 校验、放开明文流量、弱化备份/日志保护来通过测试。
- 不用删除测试、扩大 lint 忽略或伪造输出让检查通过。环境缺失与业务未验分别报告。

## 4. 分层与实现

`MainActivity / ui → MainViewModel → core → 服务端`；共享状态通过 `StateFlow` 返回。

- `core/` 不依赖 Compose、`ui/`、`vm/`。其中 `Store`、支付桥、`Diag` 等有 Android 依赖；解析、映射、金额、分页、状态机须保持可在 JVM 单测。
- `vm/` 不依赖 Compose；统一登录态、演示分支、网络编排与用户错误。`ui/` 不直接调用 `Api`、OkHttp 或 SharedPreferences。
- 账号共享状态放 VM；页面临时状态用 `remember` / `rememberSaveable`。不为小改动引入另一套全局状态或 DI 框架。
- 网络与阻塞 SDK 调用放 IO；协程绑定生命周期，重抛 `CancellationException`，不得用宽泛 `catch` / `runCatching` 吞取消。
- 切换账号或登出须取消旧任务、清账号数据，防止旧响应写回新会话。安装级数据按 docs/06 的存储分类保留，不随账号数据一并清除。
- 页面用回调导航，不持有 `NavController`；需刷新数据的页面接入 `refreshTick`，保留 `bumpRefresh()` 的 200ms 合并。列表提供加载、空、错误、重试/下拉刷新态。
- 轮询只在前台及必要页面/状态运行，同一设备避免重复轮询；离开扫码页释放 CameraX、分析器和扫描器，失败载荷不得持续重试。
- 先关闭原 Sheet 再打开另一个。格式化器保持线程安全；UI 色彩使用主题，不为局部问题覆盖全局主题。
- UTF-8、LF、4 空格；XML/JSON/YAML 为 2 空格，BAT/CMD 为 CRLF（以 `.editorconfig` 为准）。不批量格式化无关文件。

## 5. 协议与业务易错点

| 领域 | 必须保持 |
|---|---|
| 账单分页 | UI 1 基经 `Paging.toApiPage()` 转 API 0 基 |
| 失效令牌 | `code=-99` 经 `session.tokenRejected` 统一清会话，不逐屏处理 |
| Gson | 服务端模型全字段默认值；可空性按真实数据，不能用 `!!` 掩盖。改 R8 必须验证反射模型 |
| 启停参数 | 复用 `DeviceCommands`；`rcp=false`，不得手写 `receipt=true` |
| 启动确认 | `code>=0` 仅受理；可观测设备按 `StartWatch` 确认状态后记录进行中；不可观测设备只表述“已受理” |
| 状态快照 | `dev/status` 的 `DeviceStatus` 只含局部字段；必须与同 id 的完整设备 `mergeStatus`，不可整页替换。缺类型用 `nameOrNull()` 回退 |
| 操作反馈 | 请求忙碌与设备状态决定按钮；不新增固定延时锁按钮。`StartCommandLock` 仅在待机、有本设备记录、尚未见过运行态时保护重复启动，最多 20s；状态变化立即解锁，不是无条件冷却 |
| 会话记录 | 不能用第二台设备覆盖第一台进行中记录；结束/轮询必须校验设备 id |
| 钱包 | `acc/wallet/owner` 从 `WalletOwner.aw` 解包；金额与充值商品以服务端为准 |
| 支付渠道 | 共用 `Billing.kt` / `PayChoice`；不能静默选择客户端无法结算的渠道 |
| 支付宝 | `prepay/21` 空 data 可表示免密扣款，仍调 `confirm`；有订单串才交 SDK |
| 支付宝签约 | 协议类型 20；优先 `sign` 交 SDK，`code` 仅兜底。`acc-pgas` 是渠道集合，不能判断用户已签约 |
| 微信 | SDK 已集成；包名+签名+appId 需开放平台匹配，回调入口为 `{applicationId}.wxapi.WXPayEntryActivity`。不得宣称真实支付已验 |

## 6. 改动联动

按实际影响检查下表，只更新受影响内容，不机械改遍所有文件。新增补说明、修改原位更新、删除清理引用；历史留在 CHANGELOG 与 Git。无行为、配置或架构影响的内部调整可不改说明文档，但仍维护 `Unreleased`。不新增轮次文档、`*.monitor`、`new_*.png` 或无调用方兼容层。

| 改动 | 检查并同步 |
|---|---|
| 接口/字段 | `Api.kt`、`Models.kt`、VM、`docs/api-contract.json`、解析/映射测试 |
| 状态机/主按钮/命令 | `DeviceTypes.kt`、`DeviceCommands.kt`、`StartWatch`、相关测试、docs/01、docs/02、docs/05 |
| 支付/账单 | `Billing.kt`、`Payment.kt`、`WxPay.kt`、相关测试、docs/05；接口用法变化同步契约 |
| 页面/导航 | `MainActivity.kt`、页面、docs/02、README 截图区 |
| 存储键/账号生命周期 | `Store.kt`、`clearAccount()` 分类、VM 任务清理、docs/06 的存储表 |
| 依赖/SDK/JDK | Gradle、docs/03 技术选型；wrapper 升级同时更新 properties 与 jar |
| 原 APK | 契约 baseline、docs/01、docs/04；先运行 `tools/api-diff.py`，不能为通过检测直接接受新 baseline |
| 应用更新/发布 | `AppUpdate.kt`、`UpdatePackage.kt`、VM/更新 UI、相关测试、docs/06 发布清单与存储分类；版本一致性用 `tools/check-version.py` |
| 任意改动 | CHANGELOG 的 `Unreleased`；当前事实原位更新，截图同名覆盖 |

## 7. 验证与发布边界

哈希仅用于更新包完整性、签名/原样本身份核对或明确的传输损坏排查；普通开发、文档、服务连通性检查与交付简报不要求逐项计算或附上哈希。

仓库根目录运行（Python 3.10+、Git）：

```powershell
python tools/check-repo.py
python -m unittest discover -s tools/tests
python tools/check-version.py
```

Android 相关改动进入 `SmartLife798/`，使用 wrapper：

```powershell
# JAVA_HOME 指向满足当前 Gradle/AGP 要求的本机 JDK；不写进受控配置。
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lint --console=plain
```

- JDK、SDK 要求以 Gradle 与 CI 配置为准，本机 `local.properties` 须可用；环境准备、安装/演示/抓日志命令见 docs/03。
- 新增或修改纯逻辑须有对应单测。行为变更还须在模拟器/设备验证；真实资金、物理出水、支付签约是各自独立的验收，不能由 Demo 或编译代替。
- 纯文档/工具/CI 改动跑根目录检查；构建或依赖改动跑完整 Gradle 检查。不得把 `UP-TO-DATE` 或历史证据说成本次重新执行的测试。
- 凡进入 APK 的变更按 docs/06 准备语义版本并重建产物；纯维护不消耗版本号。版本与产物准备不授权远端发布，不在本文件重复维护版本副本。
- 仓库检查是静态兜底，不能证明演示隔离、脱敏、并发防重或真实支付正确，相关路径仍需评审与行为验证。
