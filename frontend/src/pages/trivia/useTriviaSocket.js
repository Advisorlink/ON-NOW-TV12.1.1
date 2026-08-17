import { useCallback, useEffect, useRef, useState } from 'react';

const WS_BASE = (process.env.REACT_APP_BACKEND_URL || '').replace(/^http/i, 'ws');

export default function useTriviaSocket(code, role, name, pid) {
    const [state, setState] = useState(null);
    const [connected, setConnected] = useState(false);
    const [privateMsg, setPrivateMsg] = useState(null);
    const wsRef = useRef(null);
    const pidRef = useRef(pid || null);

    useEffect(() => {
        if (!code) return undefined;
        // `alive`/`retry`/`timer` are LOCAL to this effect generation.
        // A shared ref here caused a zombie-socket bug: when `code`
        // changed (or StrictMode re-ran the effect), the OLD socket's
        // async onclose saw the ref flipped back to true by the new
        // run and silently reconnected to the STALE room, overwriting
        // wsRef — so sends went to an empty room nobody was watching.
        let alive = true;
        let retry = 0;
        let timer = null;

        const open = () => {
            if (!alive) return;
            const params = new URLSearchParams({ role });
            if (role === 'player') {
                params.set('name', name || 'Player');
                if (pidRef.current) params.set('pid', pidRef.current);
            }
            const ws = new WebSocket(`${WS_BASE}/api/trivia/ws/${code}?${params}`);
            wsRef.current = ws;
            ws.onopen = () => { retry = 0; setConnected(true); };
            ws.onmessage = (ev) => {
                try {
                    const msg = JSON.parse(ev.data);
                    if (msg.type === 'state') setState(msg);
                    else if (msg.type === 'joined') {
                        pidRef.current = msg.pid;
                        try { sessionStorage.setItem('trivia-pid', msg.pid); } catch { /* ignore */ }
                    } else if (msg.type === 'you') setPrivateMsg(msg);
                } catch { /* ignore */ }
            };
            ws.onclose = () => {
                setConnected(false);
                if (alive) {
                    retry += 1;
                    timer = setTimeout(open, Math.min(5000, 400 * retry));
                }
            };
            ws.onerror = () => { try { ws.close(); } catch { /* ignore */ } };
        };
        open();
        return () => {
            alive = false;
            if (timer) clearTimeout(timer);
            try { wsRef.current?.close(); } catch { /* ignore */ }
        };
    }, [code, role, name]);

    const send = useCallback((msg) => {
        const ws = wsRef.current;
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
    }, []);

    return { state, send, connected, privateMsg, pid: pidRef.current };
}
