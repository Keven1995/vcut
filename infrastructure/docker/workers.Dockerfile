FROM jrottenberg/ffmpeg:7.1-ubuntu

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src \
    PATH=/opt/venv/bin:$PATH

WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends --yes python3.12 python3.12-venv fonts-dejavu-core \
    && python3.12 -m venv /opt/venv \
    && rm -rf /var/lib/apt/lists/*

COPY apps/workers/pyproject.toml apps/workers/requirements.txt ./
COPY apps/workers/src ./src

RUN pip install --no-cache-dir --upgrade pip==25.0.1 \
    && pip install --no-cache-dir . \
    && useradd --create-home --uid 10001 worker

USER worker

EXPOSE 8090

ENTRYPOINT ["python", "-m", "vcut_workers"]
