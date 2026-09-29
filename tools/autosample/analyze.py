"""Plain-text entry points for the audited Timer SQLite pipeline."""

from contextlib import closing
from pathlib import Path
import sys


def pipeline(cfg):
    directory = Path(cfg.get("timer_tools", Path(__file__).resolve().parents[1] / "timer"))
    if not (directory / "timer.py").is_file():
        raise RuntimeError("Set timer_tools in automation/config.json")
    if str(directory) not in sys.path:
        sys.path.insert(0, str(directory))
    import timer

    return timer


def database(cfg):
    return cfg.get("database", str(Path(cfg["server"]) / "plugins/FoxAntiCheat/anticheat.sqlite"))


def ingest_trial(cfg, path):
    trial = pipeline(cfg).ingest(path, cfg["datasets"], database(cfg))
    print("Database:", trial, flush=True)


def analyze(cfg):
    if cfg.get("report_seconds", 180) != 180:
        raise ValueError("The Timer baseline uses 180-second trials")
    return pipeline(cfg).sync(cfg["datasets"], database(cfg), cfg.get("collection_date"))


def show(cfg):
    module = pipeline(cfg)
    with closing(module.connect(database(cfg))) as db:
        print(module.status(db))


if __name__ == "__main__":
    from runner import config

    show(config())
