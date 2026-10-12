"""Shared Playwright setup: BASE, SEED (profile/auth stub) and a sample library payload."""
import re, json
from playwright.async_api import async_playwright  # noqa: F401
ENV = open('/app/frontend/.env').read()
BASE = re.search(r'REACT_APP_BACKEND_URL=(\S+)', ENV).group(1)
SEED = open('/app/tests/perf_nav_probe.py').read().split('SEED = """')[1].split('"""')[0]
LIB = {
 "favorites": {
   "tt0944947": {"type":"series","addedAt":"2026-10-01T00:00:00Z","meta":{"name":"Game of Thrones","poster":"https://images.metahub.space/poster/small/tt0944947/img","year":"2011"}},
   "tt0903747": {"type":"series","addedAt":"2026-10-02T00:00:00Z","meta":{"name":"Breaking Bad","poster":"https://images.metahub.space/poster/small/tt0903747/img","year":"2008"}},
   "tt1375666": {"type":"movie","addedAt":"2026-10-03T00:00:00Z","meta":{"name":"Inception","poster":"https://images.metahub.space/poster/small/tt1375666/img","year":"2010"}},
   "tt0816692": {"type":"movie","addedAt":"2026-10-04T00:00:00Z","meta":{"name":"Interstellar","poster":"https://images.metahub.space/poster/small/tt0816692/img","year":"2014"}},
 },
 "watchLater": [
   {"type":"movie","id":"tt0468569","addedAt":"2026-10-05T00:00:00Z","movie":{"name":"The Dark Knight","poster":"https://images.metahub.space/poster/small/tt0468569/img","background":"https://images.metahub.space/background/medium/tt0468569/img","year":"2008"}},
   {"type":"series","id":"tt0903747","addedAt":"2026-10-06T00:00:00Z","showMeta":{"name":"Breaking Bad","poster":"https://images.metahub.space/poster/small/tt0903747/img","background":"https://images.metahub.space/background/medium/tt0903747/img"},"episode":{"season":5,"number":14,"title":"Ozymandias"}},
   {"type":"series","id":"tt0944947","addedAt":"2026-10-07T00:00:00Z","showMeta":{"name":"Game of Thrones","poster":"https://images.metahub.space/poster/small/tt0944947/img","background":"https://images.metahub.space/background/medium/tt0944947/img"},"episode":{"season":8,"number":3,"title":"The Long Night"}},
   {"type":"movie","id":"tt4154796","addedAt":"2026-10-08T00:00:00Z","movie":{"name":"Avengers: Endgame","poster":"https://images.metahub.space/poster/small/tt4154796/img","background":"https://images.metahub.space/background/medium/tt4154796/img","year":"2019"}},
 ],
 "dismissed": {}, "actors": {}, "notifyList": {}
}
