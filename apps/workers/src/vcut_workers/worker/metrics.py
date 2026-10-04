from prometheus_client import Counter, Gauge, Histogram

WORKER_MESSAGES = Counter(
    "vcut_worker_messages_total",
    "Worker command deliveries by queue and terminal outcome.",
    ("queue", "outcome"),
)
WORKER_RETRIES = Counter(
    "vcut_worker_retries_total", "Worker command retries scheduled.", ("queue",)
)
WORKER_DEAD_LETTERS = Counter(
    "vcut_worker_dead_letters_total", "Worker command deliveries sent to a dead-letter queue.", ("queue",)
)
WORKER_DELIVERY_ERRORS = Counter(
    "vcut_worker_delivery_errors_total", "Worker failures that returned deliveries to the broker.", ("queue",)
)
WORKER_DURATION = Histogram(
    "vcut_worker_job_duration_seconds",
    "Time spent processing one worker command delivery.",
    ("queue",),
    buckets=(0.1, 0.5, 1, 5, 15, 30, 60, 120, 300, 900),
)
WORKER_IN_PROGRESS = Gauge(
    "vcut_worker_jobs_in_progress", "Worker commands currently executing.", ("queue",)
)


def record_worker_delivery(queue: str, outcome: str, duration_seconds: float) -> None:
    WORKER_MESSAGES.labels(queue=queue, outcome=outcome).inc()
    WORKER_DURATION.labels(queue=queue).observe(duration_seconds)
    if outcome == "RETRY_SCHEDULED":
        WORKER_RETRIES.labels(queue=queue).inc()
    if outcome == "FAILED":
        WORKER_DEAD_LETTERS.labels(queue=queue).inc()
    if outcome == "DELIVERY_ERROR":
        WORKER_DELIVERY_ERRORS.labels(queue=queue).inc()


__all__ = [
    "WORKER_DEAD_LETTERS",
    "WORKER_DELIVERY_ERRORS",
    "WORKER_DURATION",
    "WORKER_IN_PROGRESS",
    "WORKER_MESSAGES",
    "WORKER_RETRIES",
    "record_worker_delivery",
]
