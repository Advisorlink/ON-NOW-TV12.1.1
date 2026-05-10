import React from 'react';
import '@/index.css';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import Home from '@/pages/Home';
import Sources from '@/pages/Sources';
import Detail from '@/pages/Detail';
import Player from '@/pages/Player';
import Search from '@/pages/Search';

function NotImplemented({ name }) {
    return (
        <div
            className="w-screen h-[100dvh] min-h-screen flex items-center justify-center"
            style={{ background: 'var(--vesper-bg-0)' }}
        >
            <div
                className="vesper-display"
                style={{ fontSize: 56, letterSpacing: '-0.03em' }}
            >
                {name} <span style={{ color: 'var(--vesper-blue)' }}>·</span> coming
                next
            </div>
        </div>
    );
}

function App() {
    return (
        <div className="App">
            <BrowserRouter>
                <Routes>
                    <Route path="/" element={<Home />} />
                    <Route path="/sources" element={<Sources />} />
                    <Route path="/search" element={<Search />} />
                    <Route path="/library" element={<NotImplemented name="My Library" />} />
                    <Route path="/settings" element={<NotImplemented name="Settings" />} />
                    <Route path="/title/:type/:id" element={<Detail />} />
                    <Route path="/title/:id" element={<Detail />} />
                    <Route path="/play" element={<Player />} />
                </Routes>
            </BrowserRouter>
        </div>
    );
}

export default App;
