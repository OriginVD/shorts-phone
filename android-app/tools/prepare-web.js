// 웹 재생기(../index.html)를 www/ 로 복사하면서, 앱 전용 스크립트(web/app-extra.js)를 끼워 넣어요.
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const www = path.join(root, 'www');
fs.mkdirSync(www, { recursive: true });

let html = fs.readFileSync(path.join(root, '..', 'index.html'), 'utf8');
if (!html.includes('</body>')) throw new Error('index.html에 </body>가 없어요');
// capacitor.js: 앱 기능(플러그인)을 부르는 데 필요한 Capacitor 기본 라이브러리
html = html.replace('</body>', '<script src="capacitor.js"></script>\n<script src="app-extra.js"></script>\n</body>');
fs.writeFileSync(path.join(www, 'index.html'), html);
fs.copyFileSync(path.join(root, 'web', 'app-extra.js'), path.join(www, 'app-extra.js'));
fs.copyFileSync(require.resolve('@capacitor/core/dist/capacitor.js'), path.join(www, 'capacitor.js'));
console.log('www/ 준비 완료');
