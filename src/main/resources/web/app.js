/* fraud-guard console. Talks to the same public API any core banking system would use. */
'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const el = (tag, attrs = {}, ...kids) => {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === 'class') n.className = v; else if (k === 'style') n.style.cssText = v; else if (k.startsWith('on')) n.addEventListener(k.slice(2), v); else n.setAttribute(k, v);
    }
    kids.flat().forEach((c) => n.append(c instanceof Node ? c : document.createTextNode(c ?? '')));
    return n;
  };
  const S = { api: null, analyst: null, supervisor: null, role: 'analyst', accounts: [], seq: 0, openCase: null, tab: 'cases' };
  const COLOR = { ALLOW: 'var(--allow)', REVIEW: 'var(--review)', BLOCK: 'var(--block)' };
  const LABEL = { ALLOW: 'Lolos', REVIEW: 'Ditahan', BLOCK: 'Diblokir' };
  const TYPE = { TRANSFER_OUT: 'Transfer keluar', TRANSFER_IN: 'Transfer masuk', CASH_DEPOSIT: 'Setor tunai', CASH_WITHDRAWAL: 'Tarik tunai' };
  const STATUS = { OPEN: 'Baru', IN_REVIEW: 'Diperiksa', PENDING_APPROVAL: 'Menunggu supervisor', CLOSED: 'Selesai' };
  const RES = { RELEASE: 'Lepaskan (bukan fraud)', CONFIRM_FRAUD: 'Fraud, bekukan rekening', REPORT_LTKM: 'Laporkan LTKM ke PPATK' };
  const rupiah = (v) => 'Rp' + Number(v).toLocaleString('id-ID', { maximumFractionDigits: 0 });
  const time = (iso) => new Date(iso).toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

  // ---------------------------------------------------------------- http
  async function call(method, path, body, token) {
    const res = await fetch(path, {
      method,
      headers: { Authorization: `Bearer ${token || (S.role === 'analyst' ? S.analyst : S.supervisor)}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    let json = {};
    try { json = await res.json(); } catch { /* empty */ }
    if (!res.ok) { const e = new Error(json.message || `HTTP ${res.status}`); e.status = res.status; throw e; }
    return json;
  }
  const sendTxn = (t) => call('POST', '/api/transactions', { id: `SIM-${Date.now().toString(36)}-${++S.seq}`, channel: 'mobile', ...t }, S.api);

  // ---------------------------------------------------------------- scenarios
  const EMP = '7001002001', SHOP = '7001002002', NEW = '7001002003';
  function nightIso() {
    const wib = new Date(Date.now() + 7 * 3600e3);
    let d = wib.toISOString().slice(0, 10);
    if (wib.getUTCHours() < 2 || (wib.getUTCHours() === 2 && wib.getUTCMinutes() < 30)) d = new Date(wib - 864e5).toISOString().slice(0, 10);
    return `${d}T02:30:00+07:00`;
  }
  const SCENARIOS = [
    { name: 'Transfer harian', note: 'Rp350 ribu ke teman yang biasa, dari HP biasa', c: 'var(--allow)',
      run: () => [{ accountNo: EMP, type: 'TRANSFER_OUT', amount: '350000.00', counterpartyAccount: '7009000001', deviceId: 'HP-AHMAD-01' }] },
    { name: 'Rekening penampung', note: 'Rekening 2 hari: Rp50 juta masuk, Rp48 juta langsung keluar', c: 'var(--block)',
      run: () => [{ accountNo: NEW, type: 'TRANSFER_IN', amount: '50000000.00', counterpartyAccount: '7009111111' },
        { accountNo: NEW, type: 'TRANSFER_OUT', amount: '48000000.00', counterpartyAccount: '7009222222', deviceId: 'HP-X' }] },
    { name: 'Pecah setoran tunai', note: '3 × Rp190 juta, masing-masing di bawah batas lapor Rp500 juta', c: 'var(--review)',
      run: () => [1, 2, 3].map(() => ({ accountNo: SHOP, type: 'CASH_DEPOSIT', amount: '190000000.00' })) },
    { name: 'Setor tunai Rp600 juta', note: 'Sah, tapi wajib LTKT', c: 'var(--allow)',
      run: () => [{ accountNo: SHOP, type: 'CASH_DEPOSIT', amount: '600000000.00' }] },
    { name: 'Transfer beruntun', note: '10 transfer kecil dalam hitungan detik', c: 'var(--review)',
      run: () => Array.from({ length: 10 }, () => ({ accountNo: EMP, type: 'TRANSFER_OUT', amount: '100000.00', counterpartyAccount: '7009000002', deviceId: 'HP-AHMAD-01' })) },
    { name: 'Tengah malam, HP baru', note: 'Rp30 juta pukul 02.30 ke rekening asing', c: 'var(--block)',
      run: () => [{ accountNo: EMP, type: 'TRANSFER_OUT', amount: '30000000.00', counterpartyAccount: '7009555555', deviceId: 'HP-BARU', at: nightIso() }] },
    { name: 'Kirim ke daftar pantauan', note: 'Rp1 juta ke rekening 8899001122', c: 'var(--block)',
      run: () => [{ accountNo: EMP, type: 'TRANSFER_OUT', amount: '1000000.00', counterpartyAccount: '8899001122', deviceId: 'HP-AHMAD-01' }] },
  ];
  function drawScenarios() {
    $('scenarios').replaceChildren(...SCENARIOS.map((s) => {
      const b = el('button', { type: 'button', class: 'scn', style: `--c:${s.c}` }, el('b', {}, s.name), el('span', {}, s.note));
      b.addEventListener('click', async () => {
        document.querySelectorAll('.scn').forEach((x) => { x.disabled = true; });
        try {
          let last;
          for (const t of s.run()) last = await sendTxn(t);
          showVerdict(last);
          await refresh();
        } catch (e) { alert(e.message); } finally { document.querySelectorAll('.scn').forEach((x) => { x.disabled = false; }); }
      });
      return b;
    }));
  }

  // ---------------------------------------------------------------- verdict
  const SEG = ['#b42318', '#c2410c', '#a86a12', '#7c5b12', '#8d2a6a', '#5b4b8a', '#1f5f99', '#0e4d52'];
  function showVerdict(d) {
    $('v-empty').hidden = true; $('v').hidden = false;
    $('v-txn').textContent = `${d.id} · ${TYPE[d.type]} ${rupiah(d.amount)} · rek ${d.accountNo}`;
    $('v-score').textContent = d.score;
    const o = $('v-outcome'); o.textContent = LABEL[d.decision]; o.className = `outcome o-${d.decision}`;
    let left = 100;
    $('v-segs').replaceChildren(...d.reasons.map((r, i) => {
      const w = Math.min(left, r.points); left -= w;
      return el('i', { style: `width:${w}%;--seg:${SEG[i % SEG.length]}`, title: `${r.rule} +${r.points}` });
    }));
    $('v-reasons').replaceChildren(...(d.reasons.length ? d.reasons.map((r, i) => el('li', {},
      el('span', { class: 'pts', style: `color:${SEG[i % SEG.length]}` }, r.hardBlock ? 'STOP' : `+${r.points}`),
      el('span', {}, el('span', { class: 'rule' }, r.rule), r.detail)))
      : [el('li', { class: 'none' }, 'Tidak ada aturan yang terpicu.')]));
    const foot = [];
    if (d.caseId) foot.push(`Kasus ${d.caseId} dibuat untuk analis.`);
    if (d.reports.length) foot.push(`Laporan ${d.reports.join(', ')} dibuat otomatis.`);
    $('v-foot').textContent = foot.join(' ');
  }

  // ---------------------------------------------------------------- lists
  async function refresh() {
    const [txns, cases, accounts] = await Promise.all([call('GET', '/api/transactions'), call('GET', '/api/cases'), call('GET', '/api/accounts')]);
    S.accounts = accounts.items;
    $('accounts').replaceChildren(...S.accounts.map((a) => el('li', {}, el('b', {}, `${a.name} · ${a.label}`),
      el('span', { class: 'mono' }, `${a.accountNo} · penghasilan ${rupiah(a.monthlyIncome)}/bln · risiko ${a.rating}`),
      a.frozen ? el('span', { class: 'frozen' }, 'DIBEKUKAN') : '')));
    $('txns').replaceChildren(...txns.items.map((t) => el('tr', {}, el('td', {}, time(t.evaluatedAt)), el('td', { class: 'mono' }, t.accountNo),
      el('td', {}, TYPE[t.type]), el('td', { class: 'num' }, rupiah(t.amount)), el('td', { class: 'num' }, String(t.score)),
      el('td', {}, el('span', { class: 'dot', style: `--c:${COLOR[t.decision]}` }), LABEL[t.decision] + (t.decision !== t.initialDecision ? ` (awal: ${LABEL[t.initialDecision]})` : '')))));
    $('cases').replaceChildren(...(cases.items.length ? cases.items.map((c) => el('li', {}, el('button', { type: 'button', style: `--c:${COLOR[c.decision]}`, onclick: () => openCase(c.id) },
      el('span', { class: 'cid' }, `${c.id} · skor ${c.score}`), el('span', { class: 'pill' }, STATUS[c.status]),
      el('span', { class: 'meta' }, `${c.customer} · ${rupiah(c.amount)} · ${LABEL[c.decision]}`))))
      : [el('li', { class: 'sub' }, 'Belum ada kasus. Transaksi yang ditahan atau diblokir muncul di sini.')]));
    if (S.openCase) await openCase(S.openCase);
    if (S.tab === 'reports') await drawReports();
    if (S.tab === 'audit') await drawAudit();
    const sel = $('m-acc');
    if (!sel.options.length) sel.replaceChildren(...S.accounts.map((a) => el('option', { value: a.accountNo }, `${a.accountNo} · ${a.label}`)));
  }

  // ---------------------------------------------------------------- case detail
  async function openCase(id) {
    const c = await call('GET', `/api/cases/${id}`);
    S.openCase = id;
    $('cases').hidden = true; $('case').hidden = false;
    $('c-status').textContent = STATUS[c.status];
    $('c-title').textContent = `${c.id} · ${c.customer}`;
    $('c-meta').textContent = `transaksi ${c.txnId} · rek ${c.accountNo} · ${rupiah(c.amount)} · skor ${c.score} · ${LABEL[c.decision]}`;
    $('c-reasons').replaceChildren(...c.reasons.map((r) => el('li', {}, el('span', { class: 'pts' }, r.hardBlock ? 'STOP' : `+${r.points}`),
      el('span', {}, el('span', { class: 'rule' }, r.rule), r.detail))));
    $('c-steps').replaceChildren(...c.steps.map((s) => el('li', {}, el('b', {}, `${s.actor}: ${s.action}`), s.note ? ` — ${s.note}` : '',
      el('small', {}, new Date(s.at).toLocaleString('id-ID')))));
    $('c-actions').replaceChildren(...actionsFor(c));
  }
  function actionsFor(c) {
    const err = el('p', { class: 'err', role: 'alert' });
    const act = (path, body) => async () => {
      try { await call('POST', `/api/cases/${c.id}/${path}`, body ? body() : {}); await refresh(); } catch (e) { err.textContent = e.message; }
    };
    const note = el('textarea', { placeholder: 'Alasan (wajib, minimal 5 huruf)', maxlength: '500', 'aria-label': 'Alasan' });
    if (S.role === 'analyst') {
      if (c.status === 'OPEN') return [el('p', {}, 'Kasus belum ada yang pegang.'), el('div', { class: 'row' }, el('button', { type: 'button', class: 'btn', onclick: act('claim') }, 'Ambil kasus')), err];
      if (c.status === 'IN_REVIEW') {
        const sel = el('select', { 'aria-label': 'Keputusan' }, ...Object.entries(RES).map(([k, v]) => el('option', { value: k }, v)));
        return [el('p', {}, 'Usulkan keputusan. Supervisor yang lain harus menyetujuinya.'), sel, note,
          el('div', { class: 'row' }, el('button', { type: 'button', class: 'btn', onclick: act('propose', () => ({ resolution: sel.value, note: note.value })) }, 'Kirim usulan')), err];
      }
      if (c.status === 'PENDING_APPROVAL') return [el('p', {}, `Usulan ${RES[c.proposal]} menunggu supervisor. Pindah ke peran Supervisor di kanan atas untuk menyetujui, lalu coba setujui sebagai analis untuk melihat penolakannya.`),
        el('div', { class: 'row' }, el('button', { type: 'button', class: 'btn try', onclick: act('approve') }, 'Coba setujui sendiri')), err];
    } else {
      if (c.status === 'PENDING_APPROVAL') return [el('p', {}, `Usulan analis: ${RES[c.proposal]}.`), note,
        el('div', { class: 'row' }, el('button', { type: 'button', class: c.proposal === 'RELEASE' ? 'btn' : 'btn danger', onclick: act('approve', () => ({ note: note.value })) }, 'Setujui'),
          el('button', { type: 'button', class: 'btn ghost', onclick: act('send-back', () => ({ note: note.value })) }, 'Kembalikan ke analis')), err];
      if (c.status === 'OPEN') return [el('p', {}, 'Supervisor tidak mengambil kasus. Pindah ke peran Analis.'), el('div', { class: 'row' }, el('button', { type: 'button', class: 'btn try', onclick: act('claim') }, 'Coba ambil')), err];
    }
    return [el('p', {}, c.status === 'CLOSED' ? `Selesai: ${RES[c.resolution]}.` : 'Tidak ada tindakan untuk peran ini.')];
  }
  $('case-back').addEventListener('click', () => { S.openCase = null; $('case').hidden = true; $('cases').hidden = false; });

  // ---------------------------------------------------------------- reports & audit
  async function drawReports() {
    const r = await call('GET', '/api/reports');
    $('reports').replaceChildren(...(r.items.length ? r.items.map((x) => el('li', {}, el('b', {}, x.id), ` · ${x.customer} · ${rupiah(x.total)}`,
      el('div', { class: 'sub' }, x.reason), el('small', { class: 'mono' }, `${x.transactions.join(', ')} · oleh ${x.createdBy}`)))
      : [el('li', { class: 'sub' }, 'Belum ada laporan.')]));
  }
  async function drawAudit() {
    const ch = $('chain');
    if (S.role !== 'supervisor') { ch.className = 'chain'; ch.textContent = 'Log audit hanya bisa dibuka supervisor.'; $('audit').replaceChildren(); return; }
    const a = await call('GET', '/api/audit');
    ch.className = `chain ${a.intact ? 'ok' : 'bad'}`;
    ch.textContent = a.intact ? `Rantai utuh: ${a.entries} entri diperiksa ulang dari awal.` : `Rantai putus di entri #${a.brokenAt}.`;
    $('audit').replaceChildren(...a.items.map((e) => el('li', {}, `#${e.seq} ${e.actor} · ${e.action} · ${e.subject}${e.detail ? ` · ${e.detail}` : ''}`,
      el('span', { class: 'h' }, `hash ${e.hash.slice(0, 24)}… ← ${e.prev_hash.slice(0, 16)}…`))));
  }

  // ---------------------------------------------------------------- tabs & roles
  function setTab(t) {
    S.tab = t;
    for (const k of ['cases', 'reports', 'audit']) { $(`t-${k}`).setAttribute('aria-selected', String(k === t)); $(`p-${k}`).hidden = k !== t; }
    if (t === 'reports') drawReports().catch(() => {});
    if (t === 'audit') drawAudit().catch(() => {});
  }
  ['cases', 'reports', 'audit'].forEach((t) => $(`t-${t}`).addEventListener('click', () => setTab(t)));
  function setRole(r) {
    S.role = r;
    $('role-analyst').setAttribute('aria-pressed', String(r === 'analyst'));
    $('role-supervisor').setAttribute('aria-pressed', String(r === 'supervisor'));
    if (S.api) refresh().catch(() => {});
  }
  $('role-analyst').addEventListener('click', () => setRole('analyst'));
  $('role-supervisor').addEventListener('click', () => setRole('supervisor'));

  // ---------------------------------------------------------------- manual form
  $('manual').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    $('m-err').textContent = '';
    const type = $('m-type').value;
    const amt = Number(String($('m-amt').value).replace(/[^0-9.]/g, ''));
    try {
      const d = await sendTxn({ accountNo: $('m-acc').value, type, amount: Number.isFinite(amt) ? amt.toFixed(2) : $('m-amt').value,
        counterpartyAccount: type.startsWith('TRANSFER') ? $('m-cp').value.trim() : undefined, deviceId: $('m-dev').value.trim() || undefined });
      showVerdict(d); await refresh();
    } catch (e) { $('m-err').textContent = e.message; }
  });

  // ---------------------------------------------------------------- start
  $('b-start').addEventListener('click', async () => {
    $('b-start').disabled = true;
    try {
      const r = await fetch('/api/sandbox', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
      const j = await r.json();
      if (!r.ok) throw new Error(j.message || `HTTP ${r.status}`);
      Object.assign(S, { api: j.apiKey, analyst: j.analystToken, supervisor: j.supervisorToken });
      $('start').hidden = true; $('desk').hidden = false; $('b-start').hidden = true;
      drawScenarios();
      await refresh();
    } catch (e) { $('start-err').textContent = e.message; $('b-start').disabled = false; }
  });
})();
