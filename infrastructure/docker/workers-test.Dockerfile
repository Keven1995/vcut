ARG WORKER_IMAGE=vcut-worker:local
FROM ${WORKER_IMAGE}

USER root

COPY apps/workers/tests /app/tests

RUN pip install --no-cache-dir pytest==8.3.4

USER worker

ENTRYPOINT ["python", "-m", "pytest", "-p", "no:cacheprovider"]
CMD ["tests/test_ffmpeg_processor.py"]
