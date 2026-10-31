import { orderStreams } from './streamOrder';

test('GORILLA DIRECT sits right under ON NOW Direct, above every other source', () => {
    const tor = { _addon_id: 'torrentio', name: 'Torrentio 1080p', url: 'u1' };
    const gor = { _addon_id: 'gorilla-direct', name: 'GORILLA DIRECT', url: 'u2' };
    const on = { _addon_id: 'onnowtv-direct', _onnow_direct: true, name: 'ON NOW TV Direct', url: 'u3' };
    const out = orderStreams([tor, gor, on]);
    expect(out.map((s) => s.name)).toEqual(['ON NOW TV Direct', 'GORILLA DIRECT', 'Torrentio 1080p']);
    expect(orderStreams([tor, gor])[0].name).toBe('GORILLA DIRECT');
});
