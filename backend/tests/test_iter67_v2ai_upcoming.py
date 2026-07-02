"""Iteration 67 backend tests for:
- Music artist/album 404 fix (stale-client 'artist-'/'album-' id prefixes)
- V2AI upcoming intent (TMDB release date resolution)
- V2AI transcribe-partial proxy
- Regressions: play_movie, trending, person/qa about a director
"""
import os
import io
import requests
import pytest

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL").rstrip("/")


@pytest.fixture(scope="module")
def s():
    sess = requests.Session()
    return sess


# ---------- Music artist/album prefix strip ----------
class TestMusicPrefixStrip:
    @staticmethod
    def _extract_name(d):
        # Response is {cached, data: {name, ...}} shape
        data = d.get("data") if isinstance(d.get("data"), dict) else d
        return (data.get("name") or data.get("artist", {}).get("name") or "").lower()

    def test_artist_13(self, s):
        r = s.get(f"{BASE_URL}/api/music/artist/13", timeout=30)
        assert r.status_code == 200, r.text[:300]
        assert "eminem" in self._extract_name(r.json())

    def test_artist_prefixed_13(self, s):
        r = s.get(f"{BASE_URL}/api/music/artist/artist-13", timeout=30)
        assert r.status_code == 200, r.text[:300]
        assert "eminem" in self._extract_name(r.json())

    def test_album_302127(self, s):
        r = s.get(f"{BASE_URL}/api/music/album/302127", timeout=30)
        assert r.status_code == 200, r.text[:200]

    def test_album_prefixed_302127(self, s):
        r = s.get(f"{BASE_URL}/api/music/album/album-302127", timeout=30)
        assert r.status_code == 200, r.text[:200]


# ---------- V2AI upcoming intent (TMDB release date) ----------
def _post_v2ai(s, text, dev):
    return s.post(
        f"{BASE_URL}/api/v2ai/process-text",
        json={"text": text, "device_id": dev},
        timeout=60,
    )


class TestV2AIUpcoming:
    def test_avatar_3(self, s):
        r = _post_v2ai(s, "when does avatar 3 come out", "qa1")
        assert r.status_code == 200, r.text[:300]
        d = r.json()
        assert d.get("intent") == "qa", f"got intent={d.get('intent')} body={d}"
        ans = (d.get("answer") or "") + " " + (d.get("qa_answer") or "")
        assert "Fire and Ash" in ans or "Avatar" in ans, f"answer missing Avatar 3 title: {ans[:300]}"
        assert "December 17, 2025" in ans or "2025" in ans, f"missing release date: {ans[:300]}"
        # subject_poster_url should be set
        assert d.get("subject_poster_url") or d.get("poster_url"), f"missing poster: keys={list(d.keys())}"

    def test_dune_part_3_future(self, s):
        r = _post_v2ai(s, "when does dune part 3 come out", "qa2")
        assert r.status_code == 200, r.text[:300]
        d = r.json()
        assert d.get("intent") == "qa"
        ans = (d.get("answer") or "") + " " + (d.get("qa_answer") or "")
        assert "2026" in ans, f"missing 2026 date: {ans[:300]}"
        # Should contain some relative phrase (months/away/from now/in X months)
        rel_ok = any(k in ans.lower() for k in ["month", "away", "from now", "year"])
        assert rel_ok, f"missing relative phrase: {ans[:300]}"

    def test_stranger_things_ended(self, s):
        r = _post_v2ai(s, "when is the next season of stranger things coming out", "qa3")
        assert r.status_code == 200, r.text[:300]
        d = r.json()
        assert d.get("intent") == "qa"
        ans = ((d.get("answer") or "") + " " + (d.get("qa_answer") or "")).lower()
        # Should mention it ended / final episode / no upcoming season
        ended_ok = any(k in ans for k in ["ended", "final", "last", "concluded", "no upcoming", "no new season"])
        assert ended_ok, f"expected ended/final status, got: {ans[:400]}"

    def test_mandalorian_and_grogu(self, s):
        r = _post_v2ai(s, "whats the release date of the mandalorian and grogu", "qa4")
        assert r.status_code == 200, r.text[:300]
        d = r.json()
        assert d.get("intent") == "qa"
        ans = (d.get("answer") or "") + " " + (d.get("qa_answer") or "")
        # Expect a real year like 2026
        assert any(y in ans for y in ["2026", "2025", "2027"]), f"missing year: {ans[:300]}"


# ---------- V2AI transcribe-partial ----------
class TestV2AITranscribePartial:
    def test_garbage_bytes_returns_empty(self, s):
        # ~3KB of random-like bytes
        blob = os.urandom(3000)
        files = {"file": ("chunk.webm", io.BytesIO(blob), "audio/webm")}
        r = s.post(f"{BASE_URL}/api/v2ai/transcribe-partial", files=files, timeout=60)
        assert r.status_code == 200, f"{r.status_code}: {r.text[:300]}"
        d = r.json()
        assert "text" in d, f"missing text field: {d}"
        # Should be empty string (or at least a string) — not 5xx
        assert isinstance(d["text"], str)


# ---------- Regressions ----------
class TestV2AIRegressions:
    def test_play_the_matrix(self, s):
        r = _post_v2ai(s, "play the matrix", "reg1")
        assert r.status_code == 200, r.text[:200]
        d = r.json()
        assert d.get("intent") == "play_movie", f"got {d.get('intent')}"
        title = (d.get("title") or d.get("movie_title") or "").lower()
        assert "matrix" in title, f"expected matrix title, got {title!r}"

    def test_trending(self, s):
        r = _post_v2ai(s, "whats trending right now", "reg2")
        assert r.status_code == 200, r.text[:200]
        d = r.json()
        assert d.get("intent") == "trending", f"got {d.get('intent')}"
        recs = d.get("recommendations") or d.get("results") or []
        assert isinstance(recs, list) and len(recs) > 0, f"no recommendations: {list(d.keys())}"

    def test_directed_pulp_fiction(self, s):
        r = _post_v2ai(s, "who directed pulp fiction", "reg3")
        assert r.status_code == 200, r.text[:200]
        d = r.json()
        intent = d.get("intent")
        assert intent in ("person_info", "qa", "director_info"), f"unexpected intent {intent}"
        body = str(d).lower()
        assert "tarantino" in body, f"no Tarantino ref in: {body[:400]}"
