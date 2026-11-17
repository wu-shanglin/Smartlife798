const format = new Intl.NumberFormat('zh-CN');

function trend(days, key, label) {
  const maximum = Math.max(...days.map(day => day[key]), 1);
  return `<div class="trend ${key}">
    <p>${label}</p>
    <div class="columns" aria-hidden="true">${[...days].reverse().map(day => {
      const height = 100 * day[key] / maximum;
      return `<span title="${day.day} · ${format.format(day[key])}"><i style="--height:${height}%"></i></span>`;
    }).join('')}</div>
    <div class="axis" aria-hidden="true"><span>${days.at(-1).day.slice(5)}</span><span>${days[0].day.slice(5)}</span></div>
  </div>`;
}

export function renderDashboard(days) {
  const today = days[0];
  const recent = days.slice(0, 14);
  const rows = days.map(day => `<tr><th scope="row"><time datetime="${day.day}">${day.day}</time></th>
    <td>${format.format(day.active)}</td><td>${format.format(day.starts)}</td></tr>`).join('');

  return `<!doctype html>
<html lang="zh-CN" data-type="software">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="慧生活798 的每日活跃安装数与设备启动受理次数。">
  <meta name="page-type" content="software">
  <meta name="site-kind" content="software">
  <title>用量统计 · 慧生活798</title>
  <link rel="icon" href="/assets/icon.png">
  <style>
    :root{color-scheme:light dark;--paper:#f3f6fa;--surface:#fff;--ink:#142237;--muted:#4a5c74;--line:#d7e0ec;--active:#2265bf;--starts:#087766}
    @media(prefers-color-scheme:dark){:root{--paper:#0e1826;--surface:#162333;--ink:#f0f5fc;--muted:#b2c2d7;--line:#34475e;--active:#83b6ff;--starts:#5ed0b9}}
    *{box-sizing:border-box}
    body{margin:0;background:var(--paper);color:var(--ink);font:1rem/1.5 Inter,system-ui,sans-serif}
    .wrap{width:min(70rem,calc(100% - 2rem));margin-inline:auto}
    header{border-bottom:1px solid var(--line);background:var(--surface)}
    header .wrap{display:flex;align-items:center;gap:.65rem;min-height:4rem;font-weight:650}
    header img{width:2rem;height:2rem;border-radius:.55rem}
    main{padding-block:clamp(1.5rem,5vw,3.75rem)}
    h1,h2,p{margin:0} h1{font-size:clamp(2rem,5vw,3rem);line-height:1.2} h2{font-size:1.2rem}
    .heading{display:flex;flex-wrap:wrap;align-items:baseline;justify-content:space-between;gap:.45rem 1rem;margin-bottom:1.25rem}
    .date,.note,.axis{color:var(--muted)}
    .metrics,.charts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}
    .metric,.panel{background:var(--surface);border:1px solid var(--line);border-radius:.9rem}
    .metric,.panel{padding:clamp(.85rem,3vw,1.5rem)}
    .metric dt{color:var(--muted);font-size:.9rem;font-weight:600;line-height:1.4}
    .metric dd{margin:.2rem 0 0;font-size:clamp(2rem,5vw,3.2rem);font-weight:670;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}
    .metrics{margin:0}.note{margin:.8rem 0 1.25rem;font-size:.9rem}
    .panel + .panel{margin-top:1rem}.charts{margin-top:1rem}
    .trend p{font-size:.9rem;font-weight:600;margin-bottom:.45rem}
    .columns{height:5.5rem;display:grid;grid-template-columns:repeat(14,1fr);gap:clamp(2px,.65vw,8px);border-bottom:1px solid var(--line)}
    .columns span{display:flex;align-items:end;background:var(--paper);border-radius:4px 4px 0 0}
    .columns i{width:100%;height:var(--height);border-radius:4px 4px 0 0;background:var(--active)}
    .starts .columns i{background:var(--starts)}
    .axis{display:flex;justify-content:space-between;margin-top:.3rem;font-size:.78rem}
    table{border-collapse:collapse;width:100%;margin-top:.75rem;font-size:.875rem;font-variant-numeric:tabular-nums}
    th,td{padding:.55rem .25rem;border-bottom:1px solid var(--line);text-align:right}
    th:first-child{text-align:left} tbody th{font-weight:500} thead th{color:var(--muted)}
    tbody tr:last-child>*{border-bottom:0}
    .skip{position:absolute;left:1rem;top:.5rem;background:var(--surface);color:var(--ink);padding:.55rem;transform:translateY(-200%)}
    .skip:focus{transform:none}
    @media(max-width:36rem){.charts{grid-template-columns:1fr}}
    @media(prefers-reduced-motion:no-preference){main{animation:enter .24s ease-out}@keyframes enter{from{opacity:.7;transform:translateY(4px)}}}
  </style>
</head>
<body>
  <a class="skip" href="#main">跳至统计</a>
  <header><div class="wrap"><img src="/assets/icon.png" alt="">慧生活798</div></header>
  <main class="wrap" id="main" data-enter>
    <div class="heading"><h1>用量统计</h1><p class="date"><time datetime="${today.day}">${today.day}</time> · UTC+8</p></div>
    <dl class="metrics" aria-label="今日统计">
      <div class="metric"><dt>今日活跃安装</dt><dd>${format.format(today.active)}</dd></div>
      <div class="metric"><dt>今日设备启动受理</dt><dd>${format.format(today.starts)}</dd></div>
    </dl>
    <p class="note">仅统计主动开启用量统计的安装。每日按安装去重，设备用量按服务端受理的启动请求计次。</p>
    <section class="panel" aria-labelledby="trend-heading">
      <h2 id="trend-heading">近 14 天趋势</h2>
      <div class="charts">${trend(recent, 'active', '活跃安装')}${trend(recent, 'starts', '设备启动受理')}</div>
    </section>
    <section class="panel" aria-labelledby="table-heading">
      <h2 id="table-heading">近 30 天明细</h2>
      <table aria-labelledby="table-heading">
        <thead><tr><th scope="col">日期</th><th scope="col">活跃安装</th><th scope="col">设备启动受理</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </section>
  </main>
</body>
</html>`;
}
