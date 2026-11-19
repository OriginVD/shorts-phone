// 앱에서만 쓰는 기능: 링크로 영상 받기, 백그라운드 재생
// index.html(웹 재생기)은 그대로 두고, 앱을 만들 때 이 파일을 끼워 넣어요. (tools/prepare-web.js)
// index.html에 있는 video, items, order, pos, selected, dbRun, loadLibrary, parseName,
// renderList, setStatus, stop, next 를 그대로 써요.
(function () {
  const Cap = window.Capacitor;
  if (!Cap || !Cap.isNativePlatform || !Cap.isNativePlatform()) return;
  const Dl = Cap.registerPlugin('ShortsDownloader');

  // ---------------- 화면 ----------------
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
  row.innerHTML = `<input id="linkInput" type="url" inputmode="url" placeholder="틱톡·유튜브 링크 붙여넣기 (여러 개 가능)">
    <button class="main" id="btnLink">⬇ 받기</button>`;
  document.getElementById('btnPick').closest('.row').after(row);

  const input = document.getElementById('linkInput');
  const btn = document.getElementById('btnLink');

  // ---------------- 링크로 받기 ----------------
  // 받기는 앱(안드로이드) 쪽에서 한 줄로 차례차례 해요. 여기서는 몇 개 남았는지만 세요.
  let waiting = 0;
  let lastPct = 0;
  const findUrls = text => [...new Set(String(text || '').match(/https?:\/\/[^\s"'<>]+/g) || [])];

  function showProgress() {
    if (waiting === 0) return;
    setStatus(`받는 중... ${lastPct}%` + (waiting > 1 ? ` · ${waiting - 1}개 기다리는 중` : '') + ' (앱을 나가도 계속 받아요)');
  }
  function updateButton() { btn.textContent = waiting > 0 ? '✕ 취소' : '⬇ 받기'; }

  Dl.addListener('progress', p => {
    lastPct = Math.max(0, Math.min(100, Math.round(p.percent)));
    showProgress();
  });

  // 받은 파일을 보관함에 넣는 일은 한 번에 하나씩
  let importChain = Promise.resolve();
  const importFile = r => (importChain = importChain.then(() => importOne(r)).catch(() => {}));

  async function importOne(r) {
    try {
      if (items.some(it => it.name === r.name)) {
        setStatus('이 영상은 이미 들어 있어요.');
        return;
      }
      const res = await fetch(Cap.convertFileSrc(r.path));
      let blob = await res.blob();
      if (/\.mp4$/i.test(r.name)) blob = new Blob([blob], { type: 'video/mp4' });
      await dbRun('readwrite', s => s.put({ name: r.name, blob, added: Date.now() }));

      if (pos >= 0) {
        // 재생 중이면 다시 불러오지 않고(재생이 끊겨요) 목록 맨 위에 끼워 넣고, 지금 재생 순서 끝에 붙여요
        items.unshift({ name: r.name, url: URL.createObjectURL(blob), ...parseName(r.name) });
        order = order.map(i => i + 1);
        order.push(0);
        selected += 1;
        renderList();
      } else {
        await loadLibrary();
      }
    } catch (e) {
      setStatus(e && e.name === 'QuotaExceededError'
        ? '저장하지 못했어요. 핸드폰 저장 공간이 부족한지 확인해 주세요.'
        : '받은 영상을 넣지 못했어요.');
      return; // 파일은 남겨 두고 다음에 앱을 켤 때 다시 넣어 봐요
    }
    Dl.remove({ path: r.path }).catch(() => {});
  }

  function addLinks(text) {
    const urls = findUrls(text);
    if (urls.length === 0) { setStatus('링크를 찾지 못했어요. https:// 로 시작하는 주소를 넣어 주세요.'); return; }
    input.value = '';
    if (waiting === 0) { lastPct = 0; setStatus('받을 준비 중... (처음 한 번은 조금 걸려요)'); }
    for (const url of urls) {
      waiting++;
      Dl.download({ url })
        .then(r => importFile(r))
        .catch(e => {
          if (e && e.code === 'CANCELED') setStatus('받기를 취소했어요.');
          else setStatus((e && e.message) || '받지 못했어요.');
        })
        .finally(() => {
          waiting--;
          lastPct = 0;
          updateButton();
          if (waiting > 0) showProgress();
          else if (/^받(는 중|을 준비)/.test($('status').textContent)) setStatus('다 받았어요! 맨 위에 넣었어요.');
        });
    }
    updateButton();
    showProgress();
  }

  btn.onclick = () => {
    if (waiting > 0) { Dl.cancel(); return; }
    addLinks(input.value);
  };
  input.addEventListener('keydown', e => { if (e.key === 'Enter') { input.blur(); addLinks(input.value); } });

  // 다른 앱에서 "공유"로 링크를 보내면 바로 받아요
  const onShared = text => { if (text) addLinks(text); };
  Dl.getSharedText().then(r => onShared(r.text)).catch(() => {});
  Dl.addListener('shared', r => onShared(r.text));

  // 앱이 꺼져 있는 사이에 다 받은 파일이 있으면 넣어요
  Dl.listDone().then(r => (r.files || []).forEach(importFile)).catch(() => {});

  // ---------------- 백그라운드 재생 ----------------
  // 재생 상태를 앱에 알려 주면, 앱을 나가도 알림창에 [재생·일시정지] [다음] [정지]가 떠요.
  let lastReport = '';
  function report() {
    const active = pos >= 0;
    const state = {
      active,
      playing: active && !video.paused,
      title: active && items[order[pos]] ? items[order[pos]].title : '',
    };
    const key = JSON.stringify(state);
    if (key === lastReport) return;
    lastReport = key;
    Dl.setPlayback(state).catch(() => {});
  }
  ['play', 'playing', 'pause', 'emptied', 'loadedmetadata'].forEach(ev => video.addEventListener(ev, report));

  // 화면이 꺼지거나 앱이 뒤로 가면 안드로이드가 영상을 멈출 때가 있어서, 내가 멈춘 게 아니면 다시 틀어요
  let userPaused = false;
  video.addEventListener('play', () => { userPaused = false; });
  video.addEventListener('pause', () => {
    if (document.hidden && pos >= 0 && !video.ended && !userPaused) {
      setTimeout(() => { if (pos >= 0 && video.paused && !userPaused) video.play().catch(() => {}); }, 300);
    }
  });
  // 앞에서 직접 누른 일시정지는 그대로 둬요
  document.getElementById('btnPlay').addEventListener('click', () => { if (!video.paused) userPaused = true; }, true);
  video.addEventListener('click', () => { if (!video.paused) userPaused = true; }, true);

  Dl.addListener('media', ({ action }) => {
    if (action === 'toggle') {
      if (pos < 0) return;
      if (video.paused) video.play().catch(() => {});
      else { userPaused = true; video.pause(); }
    } else if (action === 'next') {
      next();
    } else if (action === 'stop') {
      userPaused = true;
      stop();
    }
    report();
  });
})();
