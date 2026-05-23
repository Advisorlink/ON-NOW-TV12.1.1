FROM python:3.12-slim

WORKDIR /app

# System deps — needed for aiofiles + uvicorn's reload watchdog.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies first so they're cached across code edits.
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# Copy source.
COPY main.py /app/main.py
COPY admin /app/admin

# Storage volume — mount /data from the host so the JSON store +
# uploaded icons / wallpapers / APKs survive container restarts.
ENV DATA_DIR=/data
VOLUME ["/data"]

# Health check Docker can probe.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8002/api/launcher/health || exit 1

EXPOSE 8002

# Single-process uvicorn.  Add --workers 2 if you ever need higher
# throughput, but the launcher backend handles a single dashboard
# admin + thousands of polling TVs from one worker just fine.
CMD ["python", "-m", "uvicorn", "main:app", \
     "--host", "0.0.0.0", "--port", "8002", \
     "--no-access-log"]
