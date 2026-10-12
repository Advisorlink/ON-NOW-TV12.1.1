import { orderStreams } from '/tmp/so/streamOrder.mjs';
const mk = (id, name, extra={}) => ({ _addon_id: id, _addon_name: name, name, url: 'http://x', _size_gb: 2, ...extra });
const list = [
  mk('easynews-plus', 'EasyNews++', { name: 'EN 1080p' }),
  mk('com.stremio.plexio', 'Plexio (EP-STREM)', { name: 'Plexio 1080p' }),
  mk('mediafusion', 'MediaFusion', { name: 'MF 1080p' }),
  mk('com.stremio.torrentio.addon', 'Torrentio', { name: 'Tor 1080p 2GB' }),
  mk('com.stremio.torrentio.addon', 'Torrentio', { name: 'Tor 1080p 1GB', _size_gb: 1 }),
  mk('onnowtv-direct', 'ON NOW TV Direct', { _onnow_direct: true, name: 'Direct' }),
  mk('com.stremio.torrentio.addon', 'Torrentio', { name: 'Tor uncached', _pm_uncached: true, _size_gb: 0.5 }),
];
console.log(orderStreams(list).map(s => s.name));
