/* L2 Boss Tracker — саб РБ Asterios x5.
   Интерфейс: показывает таймеры и тумблеры звука.
   Фоновая служба Android (плагин BossWatch) опрашивает API, даже когда приложение закрыто,
   и играет мелодию при убийстве босса с включённым тумблером. */
(function () {
  'use strict';

  const API = 'https://s.l2lb.monster/api/v1/asterios/bosses';
  const H = 3600e3, M = 60e3;
  const BOSSES = [
    { token: 'Cabrio', name: 'Cabrio', lvl: 70, loc: 'Cemetery' },
    { token: 'Hallate', name: 'Hallate', lvl: 73, loc: 'ToI 3' },
    { token: 'Kernon', name: 'Kernon', lvl: 75, loc: 'ToI 8' },
    { token: 'Golkonda', name: 'Golkonda', lvl: 79, loc: 'ToI 11' },
  ];
  const DEFAULTS = { 'bw.sound': '', 'bw.openH': '18', 'bw.closeH': '30', 'bw.notifyBefore': '10',
    'bw.notifyOpen': '1', 'bw.interval': '60', 'bw.repeat': '2', 'bw.enabled': '1' };

  // ---------- Capacitor ----------
  const Cap = window.Capacitor;
  const isNative = !!(Cap && Cap.isNativePlatform && Cap.isNativePlatform());
  const plugin = (n) => { if (!isNative) return null; try { return (Cap.Plugins && Cap.Plugins[n]) || Cap.registerPlugin(n); } catch (e) { return null; } };
  const Prefs = plugin('Preferences');
  const BW = plugin('BossWatch');
  const AppP = plugin('App');

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const pad = (n) => String(n).padStart(2, '0');

  let cfg = Object.assign({}, DEFAULTS);
  let data = {};          // token -> объект из API
  let lastOk = 0, lastErr = null, svc = null;
  let history = [];

  // ---------- Настройки ----------
  async function getPref(k) {
    try { if (Prefs) { const r = await Prefs.get({ key: k }); return r.value; } } catch (e) {}
    try { return localStorage.getItem(k); } catch (e) { return null; }
  }
  async function setPref(k, v) {
    v = String(v);
    try { if (Prefs) await Prefs.set({ key: k, value: v }); } catch (e) {}
    try { localStorage.setItem(k, v); } catch (e) {}
  }
  async function loadCfg() {
    for (const k of Object.keys(DEFAULTS)) {
      const v = await getPref(k);
      if (v == null) await setPref(k, DEFAULTS[k]); else cfg[k] = v;
    }
    try { history = JSON.parse((await getPref('ui.history')) || '[]'); } catch (e) { history = []; }
  }
  const soundSet = () => new Set(cfg['bw.sound'].split(',').map((s) => s.trim()).filter(Boolean));
  async function setSound(token, on) {
    const s = soundSet(); on ? s.add(token) : s.delete(token);
    cfg['bw.sound'] = [...s].join(',');
    await setPref('bw.sound', cfg['bw.sound']);
  }

  // ---------- Время ----------
  function parseMsk(s) {
    const m = /^(\d{4})-(\d\d)-(\d\d)[ T](\d\d):(\d\d):(\d\d)/.exec(s || '');
    if (!m) return 0;
    return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]) - 3 * H; // МСК = UTC+3
  }
  function dur(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60), sec = s % 60;
    return (d ? d + 'д ' : '') + `${pad(h)}:${pad(m)}:${pad(sec)}`;
  }
  function durShort(ms) {
    const m = Math.floor(ms / M), h = Math.floor(m / 60);
    return h ? `${h} ч ${m % 60} мин` : `${m} мин`;
  }
  function when(ts) {
    const d = new Date(ts), now = new Date();
    const t = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
    if (d.toDateString() === now.toDateString()) return `сегодня ${t}`;
    if (new Date(now - 864e5).toDateString() === d.toDateString()) return `вчера ${t}`;
    if (new Date(+now + 864e5).toDateString() === d.toDateString()) return `завтра ${t}`;
    return `${pad(d.getDate())}.${pad(d.getMonth() + 1)} ${t}`;
  }
  function statusOf(token, now) {
    const b = data[token];
    const k = b ? parseMsk(b.last_kill_at) : 0;
    if (!k) return { st: 'unknown' };
    const open = k + Number(cfg['bw.openH']) * H, close = k + Number(cfg['bw.closeH']) * H;
    if (now < open) return { st: 'wait', k, open, close, left: open - now, p: (now - k) / (open - k) };
    if (now < close) return { st: 'window', k, open, close, left: close - now, p: (now - open) / (close - open) };
    return { st: 'alive', k, open, close, since: now - close };
  }

  // ---------- Данные ----------
  function applyJson(json, ts) {
    let arr;
    try { arr = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { return; }
    if (!Array.isArray(arr)) return;
    const next = {};
    arr.forEach((o) => { if (o && (o.boss_token || o.name)) next[o.boss_token || o.name] = o; });
    // история: фиксируем новые убийства
    let changed = false;
    Object.keys(next).forEach((t) => {
      const kill = next[t].last_kill_at;
      if (kill && !history.some((h) => h.token === t && h.kill === kill)) {
        history.unshift({ token: t, kill, killer: next[t].killer_name || '', clan: next[t].clan_name || '' });
        changed = true;
      }
    });
    if (changed) { history = history.slice(0, 100); setPref('ui.history', JSON.stringify(history)); }
    data = next;
    if (ts) lastOk = Math.max(lastOk, ts);
    render();
  }
  async function refresh() {
    // 1) что уже знает служба
    if (BW) {
      try {
        svc = await BW.status();
        if (svc.lastJson) applyJson(svc.lastJson, svc.lastOk);
        lastErr = svc.lastError || null;
      } catch (e) {}
    }
    // 2) свежий запрос (CapacitorHttp обходит CORS в приложении)
    try {
      const r = await fetch(API + '?t=' + Date.now(), { headers: { Accept: 'application/json' } });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      applyJson(await r.json(), Date.now());
      lastErr = null;
    } catch (e) {
      if (!lastOk) lastErr = 'Не удалось получить данные: ' + (e.message || e);
    }
    renderWatch();
  }

  // ---------- Отрисовка ----------
  function render() {
    const snd = soundSet();
    $('list').innerHTML = BOSSES.map((b) => {
      const o = data[b.token] || {};
      const who = [o.killer_name, o.clan_name ? `[${o.clan_name}]` : ''].filter(Boolean).join(' ');
      return `<article class="card" data-t="${b.token}">
        <div class="c-head">
          <div class="c-name">${esc(b.name)} <span class="lvl">${b.lvl}</span></div>
          <span class="badge">${esc(b.loc)}</span>
        </div>
        <div class="c-body">
          <div class="c-main">
            <div class="status" data-st></div>
            <div class="timer" data-timer></div>
            <div class="meta" data-meta></div>
            ${who ? `<div class="killer">☠ ${esc(who)}</div>` : ''}
          </div>
          <label class="sound ${snd.has(b.token) ? 'on' : ''}">
            <span class="switch"><input type="checkbox" data-sound="${b.token}" ${snd.has(b.token) ? 'checked' : ''}><span></span></span>
            <small>${snd.has(b.token) ? '🔔 звук' : '🔕 тихо'}</small>
          </label>
        </div>
        <div class="bar" data-barwrap><i data-bar></i></div>
      </article>`;
    }).join('');
    renderHistory();
    tick();
  }
  function tick() {
    const now = Date.now();
    const srv = new Date(now + 3 * H);
    const loc = new Date(now);
    $('clock').textContent = `${pad(loc.getHours())}:${pad(loc.getMinutes())}:${pad(loc.getSeconds())} · сервер ${pad(srv.getUTCHours())}:${pad(srv.getUTCMinutes())}`;
    document.querySelectorAll('.card').forEach((card) => {
      const s = statusOf(card.dataset.t, now);
      card.className = 'card ' + s.st;
      const st = card.querySelector('[data-st]'), tm = card.querySelector('[data-timer]'),
        meta = card.querySelector('[data-meta]'), bar = card.querySelector('[data-bar]'), wrap = card.querySelector('[data-barwrap]');
      st.className = 'status st-' + s.st;
      wrap.style.visibility = s.st === 'wait' || s.st === 'window' ? 'visible' : 'hidden';
      if (s.st === 'unknown') { st.textContent = 'Нет данных'; tm.textContent = '—'; meta.textContent = ''; return; }
      if (s.st === 'wait') { st.textContent = 'До окна'; tm.textContent = dur(s.left); meta.textContent = `Убит ${when(s.k)} · окно ${when(s.open)} – ${when(s.close)}`; }
      else if (s.st === 'window') { st.textContent = 'Окно открыто · до конца'; tm.textContent = dur(s.left); meta.textContent = `Убит ${when(s.k)} · окно до ${when(s.close)}`; }
      else { st.textContent = 'Должен быть жив'; tm.textContent = '+' + durShort(s.since); meta.textContent = `Убит ${when(s.k)} · окно закрылось ${when(s.close)}`; }
      bar.style.width = (Math.min(1, Math.max(0, s.p || 0)) * 100).toFixed(1) + '%';
    });
    renderWatchSub(now);
  }
  function renderHistory() {
    $('history').innerHTML = history.length
      ? history.slice(0, 20).map((h) => {
          const who = [h.killer, h.clan ? `[${h.clan}]` : ''].filter(Boolean).join(' ');
          return `<div><b>${esc(h.token)}</b><span>${esc(who)}</span><span>${when(parseMsk(h.kill))}</span></div>`;
        }).join('')
      : '<span class="muted">Пока пусто</span>';
  }
  function renderWatch() {
    const on = cfg['bw.enabled'] === '1';
    $('watchToggle').checked = on;
    $('watchBar').classList.toggle('on', on && (!svc || svc.running !== false));
    const w = [];
    if (!isNative) w.push('Это веб-версия: фоновое слежение и звук работают только в установленном приложении.');
    if (svc && svc.notifications === false) w.push('<b>Уведомления выключены.</b> <a data-go="notif">Включить</a>');
    if (svc && svc.batteryOk === false && on) w.push('Телефон может усыплять слежение. <a data-go="battery">Разрешить работу в фоне</a>');
    if (lastErr && Date.now() - lastOk > 5 * M) w.push('⚠ ' + esc(lastErr));
    $('warnings').innerHTML = w.map((x) => `<div class="warn">${x}</div>`).join('');
    renderWatchSub(Date.now());
  }
  function renderWatchSub(now) {
    const on = cfg['bw.enabled'] === '1';
    let t = on ? (svc && svc.running === false && isNative ? 'Запускаю…' : 'Работает, даже если закрыть приложение') : 'Выключено: звука и уведомлений не будет';
    if (lastOk) t += ` · обновлено ${Math.max(0, Math.round((now - lastOk) / 1000)) < 60 ? Math.max(0, Math.round((now - lastOk) / 1000)) + ' с' : durShort(now - lastOk)} назад`;
    $('watchSub').textContent = t;
  }

  // ---------- Служба ----------
  async function startWatch() {
    cfg['bw.enabled'] = '1'; await setPref('bw.enabled', '1');
    if (BW) {
      try { await BW.requestNotifications(); } catch (e) {}
      try { await BW.start(); } catch (e) { toast('Не удалось запустить: ' + (e.message || e)); }
      setTimeout(refresh, 1500);
    }
    renderWatch();
  }
  async function stopWatch() {
    cfg['bw.enabled'] = '0'; await setPref('bw.enabled', '0');
    if (BW) { try { await BW.stop(); } catch (e) {} setTimeout(refresh, 800); }
    renderWatch();
  }

  // ---------- Панели ----------
  let openSheet = null;
  function show(id) { openSheet = id; $(id).classList.add('on'); $('backdrop').classList.add('on'); }
  function hide() { if (openSheet) $(openSheet).classList.remove('on'); openSheet = null; $('backdrop').classList.remove('on'); }
  let tt = null;
  function toast(text) { $('toastText').textContent = text; $('toast').classList.add('on'); clearTimeout(tt); tt = setTimeout(() => $('toast').classList.remove('on'), 3500); }

  function openSettings() {
    $('sOpenH').value = cfg['bw.openH']; $('sCloseH').value = cfg['bw.closeH'];
    $('sNotifyOpen').checked = cfg['bw.notifyOpen'] === '1';
    $('sBefore').value = cfg['bw.notifyBefore']; $('sInterval').value = cfg['bw.interval']; $('sRepeat').value = cfg['bw.repeat'];
    const d = [];
    if (svc) {
      d.push('Служба: ' + (svc.running ? 'работает' : 'остановлена'));
      if (svc.lastOk) d.push('последний ответ ' + when(svc.lastOk));
      if (svc.lastError) d.push('ошибка: ' + svc.lastError);
    }
    $('diag').textContent = d.join(' · ');
    show('sheetSettings');
  }
  async function saveSettings() {
    const num = (v, def, min) => { const n = Number(v); return isFinite(n) && n >= (min || 0) ? String(n) : def; };
    let openH = num($('sOpenH').value, '18'), closeH = num($('sCloseH').value, '30');
    if (Number(closeH) < Number(openH)) closeH = openH;
    const upd = {
      'bw.openH': openH, 'bw.closeH': closeH,
      'bw.notifyOpen': $('sNotifyOpen').checked ? '1' : '0',
      'bw.notifyBefore': num($('sBefore').value, '10'),
      'bw.interval': num($('sInterval').value, '60', 30),
      'bw.repeat': String(Math.min(10, Math.max(1, Number($('sRepeat').value) || 1))),
    };
    for (const k of Object.keys(upd)) { cfg[k] = upd[k]; await setPref(k, upd[k]); }
    if (BW) { try { await BW.reload(); } catch (e) {} }
    hide(); render();
  }

  // ---------- События ----------
  function bind() {
    $('btnSettings').onclick = openSettings;
    $('backdrop').onclick = hide;
    $('btnSettingsSave').onclick = saveSettings;
    $('watchToggle').onchange = (e) => (e.target.checked ? startWatch() : stopWatch());
    $('list').addEventListener('change', async (e) => {
      const t = e.target.dataset.sound; if (!t) return;
      await setSound(t, e.target.checked);
      const lab = e.target.closest('.sound');
      lab.classList.toggle('on', e.target.checked);
      lab.querySelector('small').textContent = e.target.checked ? '🔔 звук' : '🔕 тихо';
      toast(e.target.checked ? `${t}: мелодия при убийстве включена` : `${t}: без звука`);
    });
    $('warnings').addEventListener('click', (e) => {
      const a = e.target.closest('[data-go]'); if (!a || !BW) return;
      if (a.dataset.go === 'battery') BW.openBatterySettings().catch(() => {});
      else BW.requestNotifications().then((r) => { if (!r.granted) BW.openNotificationSettings(); refresh(); }).catch(() => {});
    });
    $('btnTestSound').onclick = async () => {
      if (!BW) { toast('Звук работает только в приложении'); return; }
      await setPref('bw.repeat', String(Math.max(1, Number($('sRepeat').value) || 1)));
      try { await BW.testSound(); toast('Играю мелодию (громкость будильника)'); } catch (e) { toast('Ошибка: ' + (e.message || e)); }
    };
    $('btnBattery').onclick = () => BW ? BW.openBatterySettings().catch(() => {}) : toast('Только в приложении');
    $('btnNotifSettings').onclick = () => BW ? BW.openNotificationSettings().catch(() => {}) : toast('Только в приложении');
    $('btnClearHistory').onclick = async () => { history = []; await setPref('ui.history', '[]'); renderHistory(); toast('История очищена'); };

    if (AppP) {
      AppP.addListener('backButton', () => { if (openSheet) hide(); else if (AppP.minimizeApp) AppP.minimizeApp(); else AppP.exitApp(); });
      AppP.addListener('resume', () => refresh());
    }
    document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
  }

  // ---------- Старт ----------
  (async function start() {
    bind();
    await loadCfg();
    render();
    renderWatch();
    if (cfg['bw.enabled'] === '1') await startWatch();
    await refresh();
    setInterval(tick, 1000);
    setInterval(() => { if (!document.hidden) refresh(); }, 30000);
  })();
})();
