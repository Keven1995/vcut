import socket
from urllib.error import URLError
from urllib.request import urlopen

from vcut_workers.config import WorkerSettings


def check_dependencies(settings: WorkerSettings) -> dict[str, str]:
    return {
        "postgres": check_tcp(settings.postgres_host, settings.postgres_port),
        "rabbitmq": check_tcp(settings.rabbitmq_host, settings.rabbitmq_port),
        "storage": check_http(settings.storage_health_url),
    }


def check_tcp(host: str, port: int) -> str:
    try:
        with socket.create_connection((host, port), timeout=1):
            return "UP"
    except OSError:
        return "DOWN"


def check_http(url: str) -> str:
    try:
        with urlopen(url, timeout=1) as response:  # noqa: S310
            return "UP" if 200 <= response.status < 300 else "DOWN"
    except (OSError, URLError, ValueError):
        return "DOWN"
