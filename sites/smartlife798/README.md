# 慧生活798 Android 下载站

`public/` 包含可直接部署的 HTML、CSS、JavaScript、图标和应用截图。下载按钮指向 GitHub Releases 上项目已发布的 APK。浏览器会尝试从 GitHub 读取最新的 2.x 正式版与匹配的 APK 附件，查询失败时保留首页内置的已发布版本链接。

## Cloudflare Pages

手动部署时，从 Cloudflare 的 Workers & Pages 新建 Pages 项目，直接上传 `public/` 文件夹。上传内容的根目录应包含 `index.html`。

通过 GitHub 自动部署时，连接本仓库并使用以下设置。

| 设置 | 值 |
| --- | --- |
| 生产分支 | `main` |
| 框架预设 | None |
| 根目录 | `sites/smartlife798` |
| 构建命令 | `exit 0` |
| 构建输出目录 | `public` |

[Cloudflare Pages 静态 HTML 文档](https://developers.cloudflare.com/pages/framework-guides/deploy-anything/)

## Cloudflare Workers

已包含 Wrangler 的静态资源配置。在 `sites/smartlife798` 下执行下列命令。

```powershell
npx wrangler dev
npx wrangler deploy --dry-run
```

准备上线时，在同一目录登录 Cloudflare 并部署。

```powershell
npx wrangler login
npx wrangler deploy
```

[Cloudflare Workers 静态资源文档](https://developers.cloudflare.com/workers/static-assets/get-started/)

`wrangler.jsonc` 开启 Worker 请求日志，采样率为 100%，日志持久保存到 Cloudflare，链路追踪关闭。部署成功后可在 Worker 的「可观察性 → 日志」查看 Worker 执行记录。[Workers Logs 文档](https://developers.cloudflare.com/workers/observability/logs/workers-logs/)

Git 分支预览由 Cloudflare Workers Builds 执行 `npx wrangler preview`，仓库用空的 `previews` 块声明预览配置。需要为预览接入数据时，用预览环境独立的绑定。[Worker Previews 文档](https://developers.cloudflare.com/workers/previews/configuration/)

### 用量统计

同一个 Worker 在 `/stats` 提供管理页面，展示今日活跃安装数、今日设备启动受理次数、14 天趋势和 30 天明细。日期统一使用 UTC+8。管理页面使用 HTTPS Basic Auth，用户名是 `admin`，密码从 Worker Secret 读取。

- **活跃安装**为当天主动参与统计且打开客户端进入前台或提交了设备启动受理事件的安装。同一次安装当天只计一次，多台设备分别计数。
- **设备启动受理**为客户端收到业务服务端受理结果后的次数，按事件 UUID 去重。统计应答与设备业务流程独立，启动确认仍由原业务服务判断。离线、时钟偏差和上报失败可能导致偏低。写入端点供客户端匿名访问，限流能降低滥发影响，数据适用于运营观察。
- 客户端默认关闭统计，只发送事件类型、日期、按日更换的随机安装标识及可选的单次事件 UUID。Worker 不把账号、令牌、手机号、设备编号或 IP 写入 D1。D1 表保留最近 30 个日历日的数据，由 Cron 每天清理。关闭开关停止后续上报，已汇入的数据按保留期清理。

#### 配置现有 Worker

从本目录复制 `wrangler.jsonc` 为部署用的 `wrangler.production.jsonc`。在副本中保留现有 Worker 名称、生产域名路由与其他已有绑定。首次启用统计时新建 D1，或手动把已有 D1 的名称和 ID 配为 `USAGE_DB` 绑定。

```powershell
Copy-Item wrangler.jsonc wrangler.production.jsonc
npx wrangler d1 create smartlife798-usage --binding USAGE_DB --update-config --config wrangler.production.jsonc
npx wrangler d1 migrations apply USAGE_DB --config wrangler.production.jsonc --remote
npx wrangler secret put STATS_PASSWORD --config wrangler.production.jsonc
npx wrangler deploy --config wrangler.production.jsonc
```

在 Secret 提示中输入随机的高强度 ASCII 密码。之后继续使用配置了绑定的副本部署，配置信息也可由现有 Cloudflare 部署流程管理。已有 Worker 的 `/` 继续提供 APK 下载页。部署后通过实际 Worker 域名访问 `/stats`，确认未登录时提示认证，授权后能读取统计。如果部署目标是纯静态 Cloudflare Pages，统计接口和后台需部署到 Worker。

在 Android 工程目录构建时传入实际 Worker 的 HTTPS 源地址，例如 `-PusageStatsOrigin=https://实际部署的Worker域名`，即可显示「我的 → 关于本客户端 → 匿名用量统计」。无该属性的构建与独立 `.offline` 测试包均不会上报。已有 APK 需升级到带此功能的构建并开启开关后才会产生数据。

[D1 Wrangler 命令](https://developers.cloudflare.com/d1/wrangler-commands/)
