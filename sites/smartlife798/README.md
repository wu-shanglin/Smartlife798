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
