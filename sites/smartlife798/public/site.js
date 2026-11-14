const repository = 'https://github.com/wu-shanglin/Smartlife798';
const releaseApi = 'https://api.github.com/repos/wu-shanglin/Smartlife798/releases/latest';

async function refreshDownload() {
  try {
    const response = await fetch(releaseApi, { signal: AbortSignal.timeout(3500) });
    if (!response.ok) return;

    const release = await response.json();
    const version = /^v(2\.\d+\.\d+)$/.exec(release.tag_name)?.[1];
    if (!version || release.draft || release.prerelease || !Array.isArray(release.assets)) return;

    const filename = `SmartLife798-${version}-release.apk`;
    const download = `${repository}/releases/download/v${version}/${filename}`;
    if (!release.assets.some(asset => asset.name === filename && asset.browser_download_url === download)) return;

    document.querySelectorAll('[data-download]').forEach(link => { link.href = download; });
    document.querySelector('[data-version]').textContent = `v${version} 正式版`;
  } catch {
    // The verified APK and releases page remain available if GitHub's API cannot be reached.
  }
}

refreshDownload();
