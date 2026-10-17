// Copies immutable design assets only. Never writes to the React application.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
const root = path.resolve(import.meta.dirname, '../../..');
const assets = path.join(root, 'android/vesper-native/app/src/main/assets');
fs.mkdirSync(assets, {recursive: true});
function registry(file, name) {
  const code = fs.readFileSync(path.join(root, 'frontend/src/lib', file), 'utf8');
  const start = code.indexOf(`export const ${name} = `);
  if (start < 0) throw new Error(`Missing ${name}`);
  const data = code.slice(start).split('];')[0].replace(`export const ${name} = `, '') + ']';
  return vm.runInNewContext(`(${data})`, {}, {timeout: 1000});
}
fs.writeFileSync(path.join(assets, 'browse-registry.json'), JSON.stringify({
  networks: registry('networks.js', 'NETWORKS'),
  collections: registry('collections.js', 'COLLECTIONS'),
}, null, 2));
fs.cpSync(path.join(root, 'frontend/public/networks'), path.join(assets, 'networks'), {recursive: true});
fs.mkdirSync(path.join(assets, 'login'), {recursive: true});
fs.copyFileSync(path.join(root, 'frontend/public/login/vesper-bg.webp'), path.join(assets, 'login/vesper-bg.webp'));
for (const file of ['layout/dialog_exit_confirm.xml', 'drawable/exit_glow.xml', 'drawable/exit_card_bg.xml',
  'drawable/exit_btn_primary.xml', 'drawable/exit_btn_secondary.xml', 'color/exit_btn_primary_text.xml', 'color/exit_btn_secondary_text.xml']) {
  const dest = path.join(root, 'android/vesper-native/app/src/main/res', file);
  fs.mkdirSync(path.dirname(dest), {recursive: true});
  fs.copyFileSync(path.join(root, 'android/vesper-tv/app/src/main/res', file), dest);
}