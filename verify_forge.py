"""Verify native Forge client/server and saved-world reload using only local fixtures."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parent / "forge"
REPORTS = ROOT / "build" / "smoke"
WORLD = ROOT / "build" / "run-server-smoke"
WRAPPER = ["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["./gradlew"]


def launch(task: str, log_name: str, *args: str) -> subprocess.Popen:
    with (REPORTS / log_name).open("w") as log:
        return subprocess.Popen(
            [*WRAPPER, "--no-daemon", task, *args],
            cwd=ROOT,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=os.name != "nt",
        )


def stop(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=False)
    else:
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            process.kill()
        else:
            os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)


def passed(process: subprocess.Popen, name: str) -> dict:
    code = process.wait(timeout=600)
    report = REPORTS / f"{name}.json"
    if code != 0 or not report.is_file():
        raise RuntimeError(f"{name} failed with exit {code}; see {REPORTS}")
    data = json.loads(report.read_text())
    if data["status"] != "passed":
        raise RuntimeError(f"{name}: {data}")
    return data


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true", help="Accept the Minecraft EULA for the isolated test server")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Read https://aka.ms/MinecraftEULA and pass --accept-eula for this local test")
    # Refuse to attach to an unrelated local server.
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 25596))
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "verification.json").unlink(missing_ok=True)
    WORLD.mkdir(parents=True, exist_ok=True)
    (WORLD / "eula.txt").write_text("eula=true\n")
    (WORLD / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25596\nonline-mode=false\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\n"
        "gamemode=creative\nview-distance=3\nsimulation-distance=3\n"
        "enable-rcon=false\nmax-players=1\n"
    )
    client_directory = ROOT / "build" / "run-client-smoke"
    client_directory.mkdir(parents=True, exist_ok=True)
    # Isolated test preferences; never edit a player's real Minecraft options.
    options = client_directory / "options.txt"
    settings = (
        dict(line.split(":", 1) for line in options.read_text().splitlines() if ":" in line)
        if options.exists()
        else {}
    )
    settings.update(
        onboardAccessibility="false",
        pauseOnLostFocus="false",
        tutorialStep="none",
        lang="en_us",
    )
    options.write_text("".join(f"{key}:{value}\n" for key, value in settings.items()))
    processes: list[subprocess.Popen] = []
    try:
        server = launch("runSmokeServer", "server.log")
        processes.append(server)
        deadline = time.monotonic() + 600
        while True:
            if server.poll() is not None:
                raise RuntimeError(f"Server stopped before accepting clients; see {REPORTS / 'server.log'}")
            try:
                with socket.create_connection(("127.0.0.1", 25596), timeout=1):
                    break
            except OSError:
                if time.monotonic() > deadline:
                    raise TimeoutError("Dedicated server startup timed out")
                time.sleep(1)
        client = launch("runSmokeClient", "client.log")
        processes.append(client)
        client_result = passed(client, "client")
        server_result = passed(server, "server")
        reload = launch("runSmokeServer", "reload.log", "-PsmokeReload=true")
        processes.append(reload)
        reload_result = passed(reload, "server")
        result = {"client": client_result, "server": server_result, "reload": reload_result}
        (REPORTS / "verification.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2), flush=True)
    finally:
        for process in reversed(processes):
            stop(process)


if __name__ == "__main__":
    main()
