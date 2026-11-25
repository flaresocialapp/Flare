import json
import os
import signal
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

SAMPLE_INTERVAL = 15
FIRST_STALL_DUMP = 180
SECOND_STALL_DUMP = 480
RUN_LIMIT = 65 * 60
LOW_SPACE_KIB = 1536 * 1024
STOP_SPACE_KIB = 128 * 1024
LOW_INODES = 30_000
STOP_INODES = 1_000
STABLE_SPACE_KIB = 128 * 1024

JVM_ROLES = {
    "org.gradle.wrapper.GradleWrapperMain": "wrapper",
    "org.gradle.launcher.daemon.bootstrap.GradleDaemon": "gradle-daemon",
    "worker.org.gradle.process.internal.worker.GradleWorkerMain": "test-worker",
    "org.jetbrains.kotlin.daemon.KotlinCompileDaemon": "kotlin-daemon",
}


def timestamp():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def sample_volumes(volumes):
    readings = {}
    for name, path in volumes.items():
        if not path.exists():
            continue
        stat = os.statvfs(path)
        readings[name] = {
            "free_kib": stat.f_bavail * stat.f_frsize // 1024,
            "used_kib": (stat.f_blocks - stat.f_bfree) * stat.f_frsize // 1024,
            "free_inodes": stat.f_favail if stat.f_files else None,
        }
    return readings


def latest_test_event(report):
    events = report / "_shared_jvmTest.tsv"
    if not events.exists():
        return None
    complete = events.read_text(encoding="utf-8", errors="replace").rpartition("\n")[0]
    return complete.rsplit("\n", 1)[-1] or None


def command_output(command, timeout):
    try:
        result = subprocess.run(
            command, check=False, text=True, errors="replace",
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
        )
        return result.stdout
    except (OSError, subprocess.TimeoutExpired) as error:
        return str(error) + "\n"


def record_usage(report, workspace, gradle_home, runner_temp, tag):
    paths = [
        workspace / "shared/build/test-results",
        workspace / "shared/build",
        workspace / "app/build",
        workspace,
        gradle_home / "daemon",
        gradle_home / "caches",
        gradle_home,
        Path.home() / ".konan",
        runner_temp,
        Path("/tmp"),
    ]
    existing = [str(path) for path in paths if path.exists()]
    output = command_output(["du", "-xsk", "--count-links", *existing], timeout=12)
    with (report / "directory-usage.tsv").open("a") as log:
        log.write(f"# {timestamp()} {tag} (KiB)\n{output}")


def record_threads(report, tag):
    target = report / tag
    target.mkdir()
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/jcmd")
    jvms = []
    for line in command_output([java, "-l"], timeout=8).splitlines():
        fields = line.split(maxsplit=2)
        if len(fields) >= 2 and fields[0].isdecimal() and fields[1] in JVM_ROLES:
            jvms.append((fields[0], JVM_ROLES[fields[1]]))
    (target / "processes.txt").write_text(command_output([
        "ps", "-eo", "pid,ppid,pcpu,pmem,rss,etime,stat,comm", "--sort=-rss",
    ], timeout=5))
    if not jvms:
        print(f"[preflight] {tag} found no Gradle JVMs to attach to", flush=True)
    for pid, role in jvms:
        with (target / f"{role}-{pid}.txt").open("w") as dump:
            try:
                subprocess.run(
                    [java, pid, "Thread.print", "-l"], check=False, timeout=12,
                    stdout=dump, stderr=subprocess.STDOUT,
                )
            except (OSError, subprocess.TimeoutExpired) as error:
                dump.write(str(error) + "\n")


def stop_gradle(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return process.wait()
    try:
        return process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        return process.wait()


def main():
    report = Path(os.environ["FLARE_CI_DIAG"])
    workspace = Path.cwd()
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    runner_temp = Path(os.environ["RUNNER_TEMP"])
    volumes = {
        "workspace": workspace,
        "gradle": gradle_home,
        "runner_temp": runner_temp,
        "tmp": Path("/tmp"),
        "diagnostics": report,
    }
    init_script = report / "ci-play-preflight.init.gradle"
    init_script.write_bytes((workspace / ".github/scripts/ci_play_preflight_diagnostics.groovy").read_bytes())
    command = [
        "./gradlew", "allTests", ":app:check", ":app:lint",
        "-x", "wasmJsBrowserTest", "-x", "ktlintCheck", "--stacktrace",
        "--console=plain", "-I", str(init_script),
    ]
    record_usage(report, workspace, gradle_home, runner_temp, "before-gradle")
    process = subprocess.Popen(command, start_new_session=True)
    started = time.monotonic()
    next_usage = started
    first_stall = None
    last_stall = None
    disk_dumped = False
    stop_reason = None
    ordinal = 0

    def snapshot(reason):
        nonlocal ordinal
        ordinal += 1
        tag = f"{ordinal:02d}-{reason}"
        print(f"[preflight] capturing JVM threads and directory sizes for {tag}", flush=True)
        record_threads(report, tag)
        record_usage(report, workspace, gradle_home, runner_temp, tag)

    try:
        while True:
            readings = sample_volumes(volumes)
            event = latest_test_event(report)
            with (report / "disk.jsonl").open("a") as log:
                log.write(json.dumps({"time": timestamp(), "volumes": readings, "shared_jvm": event}) + "\n")
            sizes = " ".join(f"{name}={item['free_kib'] // 1024}MiB/{item['free_inodes']}i"
                             for name, item in readings.items())
            print(f"[preflight] {timestamp()} free {sizes} shared-jvm={event or 'waiting'}", flush=True)
            if process.poll() is not None:
                break

            build_volumes = [reading for name, reading in readings.items() if name != "diagnostics"]
            free_space = min(reading["free_kib"] for reading in build_volumes)
            free_inodes = min(
                (reading["free_inodes"] for reading in build_volumes if reading["free_inodes"] is not None),
                default=LOW_INODES + 1,
            )
            if not disk_dumped and (free_space <= LOW_SPACE_KIB or free_inodes <= LOW_INODES):
                snapshot("low-disk")
                disk_dumped = True
            if free_space <= STOP_SPACE_KIB or free_inodes <= STOP_INODES:
                stop_reason = "disk reached the emergency threshold"
                break

            now = time.monotonic()
            if now >= next_usage:
                record_usage(report, workspace, gradle_home, runner_temp, "sample")
                next_usage = now + 90
            if event:
                epoch, kind, _ = event.split("\t", maxsplit=2)
                age = int(time.time()) - int(epoch)
                targeted = "TimelineInclusionTest.excludedSourceLoadsFromNonHomeEntry" in event
                first_dump = 25 if targeted else FIRST_STALL_DUMP
                second_dump = 55 if targeted else SECOND_STALL_DUMP
                if kind != "task-finished" and age >= first_dump:
                    if first_stall is None or first_stall[0] != event:
                        snapshot("shared-jvm-stall")
                        first_stall = (event, free_space)
                    elif age >= second_dump and last_stall != event:
                        snapshot("shared-jvm-stall-repeat")
                        last_stall = event
                        if (free_space > LOW_SPACE_KIB and free_inodes > LOW_INODES
                                and abs(free_space - first_stall[1]) <= STABLE_SPACE_KIB
                                and latest_test_event(report) == event and process.poll() is None):
                            stop_reason = "shared JVM tests stalled with stable free disk space"
                            break

            if now - started >= RUN_LIMIT:
                snapshot("time-limit")
                stop_reason = "diagnostic time limit reached"
                break
            try:
                process.wait(timeout=SAMPLE_INTERVAL)
            except subprocess.TimeoutExpired:
                pass
    finally:
        status = process.returncode if process.poll() is not None else stop_gradle(process)

    record_usage(report, workspace, gradle_home, runner_temp, "after-gradle")
    result = {"gradle_exit": status, "stop_reason": stop_reason, "time": timestamp()}
    (report / "result.json").write_text(json.dumps(result) + "\n")
    print(f"[preflight] result {result}", flush=True)
    return 124 if stop_reason else status


if __name__ == "__main__":
    raise SystemExit(main())
