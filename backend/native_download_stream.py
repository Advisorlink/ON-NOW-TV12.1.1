"""Browser-managed download, including single-range resume on Starlette 0.37."""
import re

import anyio
from fastapi.responses import FileResponse, Response, StreamingResponse


def apk_response(path, filename, stat, request, headers):
    response = FileResponse(path, filename=filename, stat_result=stat,
                            media_type="application/vnd.android.package-archive",
                            headers={**headers, "Accept-Ranges": "bytes", "X-Content-Type-Options": "nosniff"})
    if request.method == "HEAD":
        return Response(headers=dict(response.headers))
    value = request.headers.get("range")
    if_range = request.headers.get("if-range")
    if not value or (if_range and if_range not in (response.headers["etag"], response.headers["last-modified"])):
        return response
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
    total = stat.st_size
    try:
        if not match or not any(match.groups()) or total == 0:
            raise ValueError()
        first, last = match.groups()
        if first:
            start, end = int(first), min(int(last), total - 1) if last else total - 1
        else:
            suffix = int(last)
            if suffix <= 0:
                raise ValueError()
            start, end = max(0, total - suffix), total - 1
        if start < 0 or start >= total or end < start:
            raise ValueError()
    except ValueError:
        return Response(status_code=416, headers={**headers, "Content-Range": f"bytes */{total}", "Accept-Ranges": "bytes"})

    async def chunks():
        async with await anyio.open_file(path, "rb") as stream:
            await stream.seek(start)
            remaining = end - start + 1
            while remaining:
                chunk = await stream.read(min(65536, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    range_headers = {**dict(response.headers), "content-range": f"bytes {start}-{end}/{total}", "content-length": str(end - start + 1)}
    return StreamingResponse(chunks(), status_code=206, headers=range_headers)