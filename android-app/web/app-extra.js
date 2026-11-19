// 앱에서만 쓰는 기능: 링크로 영상 받기
// index.html(웹 재생기)은 그대로 두고, 앱을 만들 때 이 파일을 끼워 넣어요. (tools/prepare-web.js)
// index.html에 있는 items, dbRun, loadLibrary, setStatus, stop, pos 를 그대로 써요.
(function () {
  const Cap = window.Capacitor;
  if (!Cap || !Cap.isNativePlatform || !Cap.isNativePlatform()) return;
  const Dl = Cap.registerPlugin('ShortsDownloader');

  const style = document.createElement('style');
  style.textContent = `
    #linkRow input {
      flex: 1; min-width: 0; height: 44px; border: 0; border-radius: 10px; padding: 0 12px;
      background: var(--card); color: var(--text); font: inherit;
    }
    #linkRow input::placeholder { color: var(--dim); }
    #linkRow button { flex: none; }`;
  document.head.append(style);

  const row = document.createElement('div');
  row.className = 'row';
  row.id = 'linkRow';
  row.innerHTML = `<input id="linkInput" type="url" inputmode="url" placeholder="틱톡·유튜브 링크 붙여넣기">
    <button class="main" id="btnLink">⬇ 받기</button>`;
  const pickRow = document.getElementById('btnPick').closest('.row');
  pickRow.after(row);

  const input = document.getElementById('linkInput');
  const btn = document.getElementById('btnLink');
  let busy = false;

  // 공유된 글("이 영상 봐 https://vt.tiktok.com/...") 안에서 링크만 꺼냄
  const findUrl = text => (String(text || '').match(/https?:\/\/\S+/) || [null])[0];

  Dl.addListener('progress', p => {
    if (!busy) return;
    const pct = Math.max(0, Math.min(100, Math.round(p.percent)));
    setStatus(`받는 중... ${pct}%` + (p.eta > 0 ? ` (약 ${p.eta}초 남음)` : ''));
  });

  async function fetchLink(raw) {
    if (busy) return;
    const url = findUrl(raw);
    if (!url) { setStatus('링크를 찾지 못했어요. https:// 로 시작하는 주소를 넣어 주세요.'); return; }
    busy = true;
    btn.textContent = '✕ 취소';
    setStatus('받을 준비 중... (처음 한 번은 조금 걸려요)');
    try {
      const r = await Dl.download({ url });
      setStatus('목록에 넣는 중...');
      if (items.some(it => it.name === r.name)) {
        setStatus('이 영상은 이미 들어 있어요.');
      } else {
        const res = await fetch(Cap.convertFileSrc(r.path));
        let blob = await res.blob();
        if (/\.mp4$/i.test(r.name)) blob = new Blob([blob], { type: 'video/mp4' });
        if (pos >= 0) stop();
        await dbRun('readwrite', s => s.put({ name: r.name, blob, added: Date.now() }));
        await loadLibrary();
        input.value = '';
        setStatus(`받았어요! 맨 위에 넣었어요.`);
      }
    } catch (e) {
      if (e && e.code === 'CANCELED') setStatus('받기를 취소했어요.');
      else if (e && e.name === 'QuotaExceededError') setStatus('저장하지 못했어요. 핸드폰 저장 공간이 부족한지 확인해 주세요.');
      else setStatus((e && e.message) || '받지 못했어요.');
    } finally {
      busy = false;
      btn.textContent = '⬇ 받기';
      Dl.cleanup().catch(() => {});
    }
  }

  btn.onclick = () => {
    if (busy) { Dl.cancel(); return; }
    fetchLink(input.value);
  };
  input.addEventListener('keydown', e => { if (e.key === 'Enter') { input.blur(); fetchLink(input.value); } });

  // 다른 앱에서 "공유"로 링크를 보내면 바로 받아요
  function onShared(text) {
    if (!text) return;
    input.value = findUrl(text) || text;
    fetchLink(text);
  }
  Dl.getSharedText().then(r => onShared(r.text)).catch(() => {});
  Dl.addListener('shared', r => onShared(r.text));
})();
