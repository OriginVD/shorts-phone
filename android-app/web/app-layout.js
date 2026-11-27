// 앱 전용 화면 개편: 초록 민트 색 + 아래 탭 4개 (재생 / 목록 / 받기 / 설정)
// index.html의 요소를 지우거나 이름을 바꾸지 않고 자리만 옮겨요. 그래서 원래 기능은 그대로 돌아가요.
// app-extra.js 다음에 불러요. (tools/prepare-web.js)
(function () {
  const Cap = window.Capacitor;
  if (!Cap || !Cap.isNativePlatform || !Cap.isNativePlatform()) return;
  try { buildAppLayout(); }
  catch (e) { setStatus('화면을 꾸미지 못했어요: ' + (e && e.message)); }
})();

function buildAppLayout() {
  // 옮기는 동안에는 화면에서 잠깐 떨어져서 못 찾으니까, 필요한 요소를 처음에 다 잡아 둬요
  const refs = {};
  ['stage', 'placeholder', 'btnPlay', 'btnPick', 'picker', 'list', 'linkRow', 'btnMinus', 'tglShuffle',
    'tglLoopAll', 'vol', 'btnBoost', 'status', 'nowTitle', 'btnNext'].forEach(id => {
    refs[id] = document.getElementById(id);
    if (!refs[id]) throw new Error(id + '을(를) 찾지 못했어요');
  });
  const el = id => refs[id] || document.getElementById(id);
  const make = (tag, props = {}, ...kids) => {
    const n = Object.assign(document.createElement(tag), props);
    n.append(...kids);
    return n;
  };
  const rows = {};
  ['btnPlay', 'btnPick', 'btnMinus', 'vol'].forEach(id => { rows[id] = refs[id].closest('.row'); });
  const rowOf = id => rows[id];
  const nowCard = document.querySelector('.now');
  const listHead = document.querySelector('.list-head');

  // ---------------- 색과 모양 ----------------
  const css = `
  :root {
    --bg: #0B1210; --side: #0F1916; --card: #15211D; --card-hi: #1E2E28;
    --text: #E8F5EF; --dim: #7F9A8F; --accent: #10B981; --accent-hi: #34D399; --mint: #A7F3D0;
    --on-accent: #04140E;
    --sat: max(env(safe-area-inset-top, 0px), var(--safe-area-inset-top, 0px));
    --sab: max(env(safe-area-inset-bottom, 0px), var(--safe-area-inset-bottom, 0px));
  }
  html, body { background: var(--bg); }
  body.app {
    max-width: none; margin: 0; padding: 0 14px;
    padding-top: var(--sat);
    padding-bottom: calc(var(--dock-h, 70px) + 8px);
    font-family: system-ui, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif;
  }
  body.app button { border-radius: 14px; }
  body.app button.main { background: var(--accent); color: var(--on-accent); }
  body.app button.main:active { background: var(--accent-hi); }
  body.app button.toggle.on { background: var(--accent); color: var(--on-accent); }
  body.app input[type=range] { accent-color: var(--accent); }
  body.app .sub { display: none; }

  #appHeader { display: flex; align-items: center; gap: 10px; height: 52px; }
  #appHeader h1 { margin: 0; font-size: 20px; letter-spacing: -0.3px; }
  #appHeader .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--accent);
    box-shadow: 0 0 10px var(--accent); }
  #appHeader .tabname { margin-left: auto; color: var(--dim); font-size: 13px; }

  .tab { display: none; }
  .tab.show { display: block; }

  /* 재생 탭: 영상이 남는 공간을 다 써요 */
  #tab-play.show { display: flex; flex-direction: column;
    height: calc(100dvh - var(--sat) - 52px - var(--dock-h, 70px) - 8px); }
  body.app #stage { flex: 1; height: auto; min-height: 220px; border-radius: 18px;
    border: 1px solid var(--card-hi); }
  body.app #placeholder { color: var(--dim); font-weight: normal; }
  body.app .now { border-radius: 14px; margin: 10px 0 8px; }
  body.app #loopInfo { color: var(--accent-hi); font-weight: bold; }
  #tab-play .row { margin-bottom: 0; }
  #tab-play .row button { height: 52px; font-size: 16px; }

  /* 카드 */
  .card { background: var(--card); border-radius: 16px; padding: 14px; margin-bottom: 12px; }
  .card h2 { font-size: 14px; color: var(--mint); margin: 0 0 10px; font-weight: bold; }
  .card p { color: var(--dim); font-size: 13px; line-height: 1.6; margin: 8px 0 0; }
  .card .row:last-child { margin-bottom: 0; }
  .card button:not(.main):not(.on) { background: var(--card-hi); }
  .card #linkRow input { background: var(--bg); border: 1px solid var(--card-hi); }
  .card #linkRow input:focus { outline: none; border-color: var(--accent); }

  /* 목록 탭 */
  body.app .list-head { margin: 4px 2px 8px; }
  body.app #list li { border-radius: 14px; margin-bottom: 4px; background: var(--side); }
  body.app #list li.playing { background: var(--card-hi); }
  body.app #list li.playing::before { background: var(--accent); }
  body.app #list li.playing .t { color: var(--accent-hi); }
  #listEmpty { color: var(--dim); text-align: center; padding: 30px 0; line-height: 1.8; }

  /* 아래 고정: 알림 줄 + 작은 재생 막대 + 탭 */
  #dock { position: fixed; left: 0; right: 0; bottom: 0; z-index: 10;
    background: var(--side); border-top: 1px solid var(--card-hi);
    padding-bottom: var(--sab); }
  body.app #status { margin: 0; padding: 6px 14px 0; min-height: 0; color: var(--mint); }
  body.app #status:empty { display: none; }
  #mini { display: none; align-items: center; gap: 8px; padding: 8px 10px 0 14px; }
  #mini.show { display: flex; }
  #mini .t { flex: 1; font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  #mini button { width: 40px; height: 36px; padding: 0; background: var(--card); }
  #tabs { display: flex; }
  #tabs button { flex: 1; height: 58px; background: transparent; border-radius: 0; color: var(--dim);
    display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
    font-size: 11px; }
  #tabs button .i { font-size: 20px; line-height: 1; }
  #tabs button.on { color: var(--accent-hi); font-weight: bold; }
  #tabs button:active { background: var(--card); }
  `;
  document.head.append(make('style', { textContent: css }));
  document.querySelector('meta[name=theme-color]')?.setAttribute('content', '#0B1210');
  document.body.classList.add('app');

  // ---------------- 위 ----------------
  const h1 = document.querySelector('h1');
  const tabName = make('span', { className: 'tabname' });
  const header = make('header', { id: 'appHeader' }, make('span', { className: 'dot' }), h1, tabName);

  // ---------------- 탭 내용 ----------------
  // 재생
  const tabPlay = make('section', { id: 'tab-play', className: 'tab' },
    el('stage'), nowCard, rowOf('btnPlay'));
  el('placeholder').innerHTML = '▶<br>[목록]에서 영상을 넣거나<br>[받기]에서 링크로 받아 주세요<br><small>한 번 넣으면 다음에도 남아 있어요</small>';

  // 목록
  const listEmpty = make('div', { id: 'listEmpty' });
  listEmpty.innerHTML = '아직 영상이 없어요<br>[＋ 영상 추가]로 폰에 있는 영상을 넣거나<br>[받기] 탭에서 링크로 받아 보세요';
  const tabList = make('section', { id: 'tab-list', className: 'tab' },
    rowOf('btnPick'), el('picker'), listHead, el('list'), listEmpty);
  el('btnPick').classList.add('main');

  // 받기
  const getHelp = make('p');
  getHelp.innerHTML = '틱톡·유튜브 앱에서 <b>공유 → 쇼츠 플레이어</b>를 누르면 바로 받아요.<br>' +
    '링크 여러 개를 한꺼번에 붙여 넣어도 돼요. 받는 동안 앱을 나가도 계속 받아요.<br>' +
    '로그인해야 볼 수 있는 영상(연령 제한 등)은 받을 수 없어요.';
  const tabGet = make('section', { id: 'tab-get', className: 'tab' },
    make('div', { className: 'card' }, make('h2', { textContent: '링크로 받기' }), el('linkRow'), getHelp));

  // 설정
  const countRow = rowOf('btnMinus');
  const toggleRow = make('div', { className: 'row' }, el('tglShuffle'), el('tglLoopAll'));
  const volRow = rowOf('vol');
  const boostRow = make('div', { className: 'row' },
    make('span', { className: 'label grow', textContent: '소리 키우기' }), el('btnBoost'));
  const tabSet = make('section', { id: 'tab-set', className: 'tab' },
    make('div', { className: 'card' }, make('h2', { textContent: '반복' }), countRow, toggleRow,
      make('p', { textContent: '한 영상을 몇 번 볼지 정해요. +를 끝까지 누르면 ∞(계속 반복)이에요. 무한을 켜면 목록 끝에서 처음으로 돌아가요.' })),
    make('div', { className: 'card' }, make('h2', { textContent: '소리' }), volRow, boostRow,
      make('p', { textContent: '작게 녹음된 영상은 [소리 키우기]로 1.5배·2배·3배까지 키울 수 있어요.' })));

  // ---------------- 아래 고정 ----------------
  const miniTitle = make('span', { className: 't' });
  const miniPlay = make('button', { textContent: '❚❚' });
  const miniNext = make('button', { textContent: '⏭' });
  miniPlay.onclick = () => el('btnPlay').click();
  miniNext.onclick = () => el('btnNext').click();
  const mini = make('div', { id: 'mini' }, miniTitle, miniPlay, miniNext);
  miniTitle.onclick = () => showTab('play');

  const TABS = [
    ['play', '▶', '재생', tabPlay],
    ['list', '☰', '목록', tabList],
    ['get', '⬇', '받기', tabGet],
    ['set', '⚙', '설정', tabSet],
  ];
  const tabsBar = make('nav', { id: 'tabs' });
  const tabBtns = {};
  for (const [key, icon, label] of TABS) {
    const b = make('button', {}, make('span', { className: 'i', textContent: icon }), make('span', { textContent: label }));
    b.onclick = () => showTab(key);
    tabBtns[key] = b;
    tabsBar.append(b);
  }
  const dock = make('div', { id: 'dock' }, el('status'), mini, tabsBar);

  document.body.prepend(header, tabPlay, tabList, tabGet, tabSet, dock);

  // 남은 원래 줄(옮기고 빈 것) 정리
  document.querySelectorAll('body > .row').forEach(r => { if (!r.children.length) r.remove(); });

  // 아래 고정 영역 높이만큼 위 내용이 가려지지 않게
  const setDockH = () => document.documentElement.style.setProperty('--dock-h', dock.offsetHeight + 'px');
  new ResizeObserver(setDockH).observe(dock);
  setDockH();

  // ---------------- 탭 바꾸기 ----------------
  let current = 'play';
  function showTab(key) {
    current = key;
    for (const [k, , label, section] of TABS) {
      section.classList.toggle('show', k === key);
      tabBtns[k].classList.toggle('on', k === key);
      if (k === key) tabName.textContent = label;
    }
    updateMini();
    window.scrollTo(0, 0);
  }
  window.appShowTab = showTab;

  // 목록에서 영상을 누르면 재생 탭으로 (✕ 지우기는 빼고)
  el('list').addEventListener('click', e => {
    if (e.target.closest('.del')) return;
    if (e.target.closest('li')) showTab('play');
  });

  // ---------------- 작은 재생 막대 ----------------
  function updateMini() {
    const active = pos >= 0;
    mini.classList.toggle('show', active && current !== 'play');
    miniTitle.textContent = el('nowTitle').textContent;
    miniPlay.textContent = video.paused ? '▶' : '❚❚';
  }
  ['play', 'pause', 'emptied', 'loadedmetadata'].forEach(ev => video.addEventListener(ev, updateMini));
  new MutationObserver(updateMini).observe(el('nowTitle'), { childList: true, characterData: true, subtree: true });

  // 목록이 비었는지
  const updateEmpty = () => { listEmpty.style.display = el('list').children.length ? 'none' : 'block'; };
  new MutationObserver(updateEmpty).observe(el('list'), { childList: true });
  updateEmpty();

  // 처음엔 영상이 있으면 재생 탭, 없으면 목록 탭
  showTab(el('list').children.length ? 'play' : 'list');
  setTimeout(() => { if (current === 'list' && el('list').children.length && pos < 0) showTab('play'); }, 800);
}
