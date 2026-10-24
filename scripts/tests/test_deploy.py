import fcntl
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "deploy-server.sh"
OLD_ID = "sha256:" + "a" * 64
NEW_ID = "sha256:" + "b" * 64
IMAGE = "ghcr.io/chagokai/server@sha256:" + "c" * 64
ROLLBACK = "chagok-server-rollback:" + "a" * 64

# Simulate Docker and HTTP boundaries while running the real deployment script.
# Failures happen after the service changes, so the tests exercise restoration.
FAKE_TOOL = r'''#!/usr/bin/env python3
import json, os, pathlib, re, shlex, subprocess, sys, time
root = pathlib.Path(os.environ["MOCK_ROOT"])
state_file = root / "docker.json"
state = json.loads(state_file.read_text())
args = sys.argv[1:]
tool = pathlib.Path(sys.argv[0]).name
with (root / "commands.jsonl").open("a") as log:
    log.write(json.dumps([tool, *args]) + "\n")
def save():
    state_file.write_text(json.dumps(state))
def fail():
    sys.exit(1)
if tool == "ssh":
    result = subprocess.run(shlex.split(args[-1]), input=sys.stdin.read(), text=True)
    sys.exit(result.returncode)
elif tool == "curl":
    url = args[-1]
    if url.endswith("/healthz"):
        print("ok", end="")
    elif url.endswith("/login"):
        print('<html><div id="app"></div></html>')
    elif state.get("bad_api") and state["current"] == state["new"]:
        print('<html><div id="app"></div></html>')
    else:
        print(json.dumps({"data": {"headerName": "X-CSRF-TOKEN", "token": "test"}}))
elif args[0] == "compose":
    if "config" in args:
        pass
    elif "ps" in args:
        if not state.get("missing_container"):
            print("postgres-container" if args[-1] == "postgres" else "backend-container")
    elif "up" in args:
        index = len(args) - 1 - args[::-1].index("-f")
        override = pathlib.Path(args[index + 1]).read_text()
        image = re.search(r'image: "([^"]+)"', override)[1]
        ddl = re.search(r"DDL_AUTO: (\S+)", override)
        state["current"] = state["images"][image]
        state["ddl"] = ddl[1] if ddl else None
        state["live"] = {}
        for name in ("LIVE_FEEDER_ENABLED", "LIVE_ALLOW_REMOTE_DATABASE"):
            setting = re.search(name + r': "(true|false)"', override)
            if setting:
                state["live"][name] = setting[1]
        save()
        if (state.get("bad_start") and state["current"] == state["new"]) or state.get("bad_rollback"):
            fail()
        # An image whose entities do not match the schema cannot start under validate.
        if state["ddl"] == "validate" and state["current"] in state.get("validate_breaks", []):
            fail()
    else:
        fail()
elif args[0] == "exec":
    if state.get("bad_backup"):
        fail()
    print("mock database dump")
elif args[0] == "inspect":
    if "Config.Env" in args[2]:
        if state.get("ddl"):
            print("DDL_AUTO=" + state["ddl"])
        for name, value in state.get("live", {}).items():
            print(name + "=" + value)
    else:
        print(state["current"])
elif args[0] == "pull":
    if state.get("bad_pull"):
        fail()
    if state.get("pause_pull"):
        (root / "pull-started").touch()
        deadline = time.monotonic() + 5
        while not (root / "continue-pull").exists():
            if time.monotonic() > deadline:
                fail()
            time.sleep(0.02)
elif args[:2] == ["image", "inspect"]:
    print(state["images"][args[-1]])
elif args[:2] == ["image", "tag"]:
    state["images"][args[-1]] = args[-2]
    save()
elif args[0] == "run":
    if state.get("bad_preflight"):
        fail()
else:
    fail()
'''


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.deploy_state = self.root / "releases"
        self.deploy_state.mkdir()
        for tool in ("docker", "curl", "ssh"):
            path = self.bin / tool
            path.write_text(FAKE_TOOL)
            path.chmod(0o755)
        self.state = {
            "current": OLD_ID,
            # The running backend predates validated deployments.
            "ddl": "update",
            "new": NEW_ID,
            "images": {IMAGE: NEW_ID},
        }
        self.save()
        compose = self.root / "compose.yaml"
        compose.write_text("services: {}\n")
        self.config = self.root / "deploy.conf"
        quote = lambda value: shlex.quote(str(value))
        self.config.write_text(
            f"COMPOSE_PROJECT_DIRECTORY={quote(self.root)}\n"
            "COMPOSE_PROJECT_NAME=existing-stack\n"
            f"COMPOSE_FILES=({quote(compose)})\n"
            f"DEPLOY_STATE_DIR={quote(self.deploy_state)}\n"
            f"DB_BACKUP_DIR={quote(self.root / 'backups')}\n"
            "SMOKE_BASE_URL=http://127.0.0.1:8080\n"
            "HTTP_ATTEMPTS=1\n"
            "HTTP_RETRY_DELAY=0\n"
        )

    def save(self):
        (self.root / "docker.json").write_text(json.dumps(self.state))

    def run_deploy(self, mode="deploy", image=IMAGE, live_feeder="", live_allow_remote=""):
        return subprocess.run(
            ["bash", str(SCRIPT), mode, image, str(self.config), live_feeder, live_allow_remote],
            env={**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}", "MOCK_ROOT": str(self.root)},
            text=True,
            capture_output=True,
            timeout=10,
        )

    def commands(self):
        path = self.root / "commands.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def current(self):
        return json.loads((self.root / "docker.json").read_text())["current"]

    def live_settings(self):
        return json.loads((self.root / "docker.json").read_text()).get("live", {})

    def up_commands(self):
        return [cmd for cmd in self.commands() if cmd[:2] == ["docker", "compose"] and "up" in cmd]

    def test_success_changes_only_backend_and_records_rollback(self):
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.current(), NEW_ID)
        self.assertEqual((self.deploy_state / "current-image").read_text().strip(), IMAGE)
        self.assertEqual((self.deploy_state / "previous-image").read_text().strip(), ROLLBACK)
        command = self.up_commands()[0]
        self.assertIn("existing-stack", command)
        for option in ("--no-deps", "--no-build", "--wait"):
            self.assertIn(option, command)
        self.assertEqual(command[-1], "backend")
        self.assertFalse(any("down" in cmd or "prune" in cmd for cmd in self.commands()))
        override = (self.deploy_state / "backend.image.yaml").read_text()
        self.assertIn("DDL_AUTO: validate", override)
        self.assertEqual((self.deploy_state / "previous-ddl-auto").read_text().strip(), "update")
        backups = list((self.root / "backups").glob("*.dump"))
        self.assertEqual(len(backups), 1)
        self.assertIn("mock database dump", backups[0].read_text())

    def test_failed_pull_leaves_running_service_and_state_untouched(self):
        self.state["bad_pull"] = True
        self.save()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.current(), OLD_ID)
        self.assertEqual(self.up_commands(), [])
        self.assertFalse((self.deploy_state / "backend.image.yaml").exists())

    def test_native_jvm_failure_leaves_running_service_untouched(self):
        self.state["bad_preflight"] = True
        self.save()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.current(), OLD_ID)
        self.assertEqual(self.up_commands(), [])
        self.assertFalse((self.deploy_state / "backend.image.yaml").exists())

    def test_failed_database_backup_prevents_backend_replacement(self):
        self.state["bad_backup"] = True
        self.save()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Database backup failed", result.stderr)
        self.assertEqual(self.current(), OLD_ID)
        self.assertEqual(self.up_commands(), [])
        self.assertEqual(list((self.root / "backups").glob("*.dump")), [])

    def test_shared_frontend_lock_blocks_backend_deployment(self):
        lock_path = self.root / "frontend.lock"
        with self.config.open("a") as config:
            config.write(f"DEPLOY_LOCK_FILE={shlex.quote(str(lock_path))}\n")
        with lock_path.open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("already running", result.stderr)
        self.assertEqual(self.commands(), [])

    def test_unhealthy_new_container_restores_old_image(self):
        self.state["bad_start"] = True
        self.save()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.current(), OLD_ID)
        self.assertEqual(len(self.up_commands()), 2)
        self.assertIn("Previous backend restored", result.stderr)
        self.assertIn(ROLLBACK, (self.deploy_state / "backend.image.yaml").read_text())
        self.assertFalse((self.deploy_state / "current-image").exists())

    def test_restore_keeps_schema_mode_of_old_container(self):
        # The old image only ran with update; forcing validate on restore left the API down.
        self.state.update(bad_start=True, validate_breaks=[OLD_ID])
        self.save()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.current(), OLD_ID)
        self.assertIn("Previous backend restored", result.stderr)
        self.assertIn("DDL_AUTO: update", (self.deploy_state / "backend.image.yaml").read_text())
        self.assertIn("restored", (self.deploy_state / "history.tsv").read_text())

    def test_old_container_without_ddl_auto_is_restored_with_app_default(self):
        self.state.update(bad_start=True, ddl=None)
        self.save()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.current(), OLD_ID)
        self.assertIn("DDL_AUTO: update", (self.deploy_state / "backend.image.yaml").read_text())

    def test_html_from_api_is_failure_even_when_http_status_is_200(self):
        self.state["bad_api"] = True
        self.save()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.current(), OLD_ID)
        self.assertIn("restored", (self.deploy_state / "history.tsv").read_text())

    def test_failed_rollback_is_reported_as_failure(self):
        self.state.update(bad_start=True, bad_rollback=True)
        self.save()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Automatic rollback failed", result.stderr)
        self.assertIn("rollback-failed", (self.deploy_state / "history.tsv").read_text())

    def test_manual_rollback_restores_recorded_image(self):
        self.state["validate_breaks"] = [OLD_ID]
        self.save()
        first = self.run_deploy()
        self.assertEqual(first.returncode, 0, first.stderr)
        result = self.run_deploy(mode="rollback", image="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.current(), OLD_ID)
        self.assertEqual((self.deploy_state / "current-image").read_text().strip(), ROLLBACK)
        self.assertIn("DDL_AUTO: update", (self.deploy_state / "backend.image.yaml").read_text())
        self.assertEqual((self.deploy_state / "previous-ddl-auto").read_text().strip(), "validate")

    def test_redeploying_same_image_preserves_previous_release(self):
        self.assertEqual(self.run_deploy().returncode, 0)
        command_count = len(self.up_commands())
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.up_commands()), command_count)
        self.assertEqual((self.deploy_state / "previous-image").read_text().strip(), ROLLBACK)

    def test_live_variables_reach_backend_through_ssh(self):
        result = subprocess.run(
            ["bash", str(SCRIPT.with_name("deploy-over-ssh.sh")), "deploy", IMAGE],
            env={
                **os.environ,
                "PATH": f"{self.bin}:{os.environ['PATH']}",
                "MOCK_ROOT": str(self.root),
                "DEPLOY_HOST": "deploy.example.com",
                "DEPLOY_USER": "deploy",
                "DEPLOY_CONFIG": str(self.config),
                "DEPLOY_SSH_KEY": "test-private-key",
                "DEPLOY_KNOWN_HOSTS": "test-host-key",
                "LIVE_FEEDER_ENABLED": "true",
                "LIVE_ALLOW_REMOTE_DATABASE": "true",
            },
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.current(), NEW_ID)
        self.assertEqual(self.live_settings(), {
            "LIVE_FEEDER_ENABLED": "true", "LIVE_ALLOW_REMOTE_DATABASE": "true",
        })

    def test_changing_live_variables_redeploys_same_image_and_rollback_restores_values(self):
        self.assertEqual(self.run_deploy().returncode, 0)
        result = self.run_deploy(live_feeder="true", live_allow_remote="true")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.up_commands()), 2)
        self.assertEqual(self.live_settings()["LIVE_FEEDER_ENABLED"], "true")
        result = self.run_deploy(mode="rollback", image="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.up_commands()), 3)
        self.assertEqual(self.current(), NEW_ID)
        self.assertEqual(self.live_settings(), {
            "LIVE_FEEDER_ENABLED": "false", "LIVE_ALLOW_REMOTE_DATABASE": "false",
        })

    def test_failed_release_restores_live_variables_and_previous_record(self):
        self.assertEqual(self.run_deploy(live_feeder="true", live_allow_remote="true").returncode, 0)
        previous_live = (self.deploy_state / "previous-live-env").read_text()
        self.state = json.loads((self.root / "docker.json").read_text())
        third_id = "sha256:" + "d" * 64
        third_image = IMAGE.replace("c" * 64, "e" * 64)
        self.state.update(new=third_id, bad_api=True)
        self.state["images"][third_image] = third_id
        self.save()
        result = self.run_deploy(image=third_image, live_feeder="false", live_allow_remote="false")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.current(), NEW_ID)
        self.assertEqual(self.live_settings(), {
            "LIVE_FEEDER_ENABLED": "true", "LIVE_ALLOW_REMOTE_DATABASE": "true",
        })
        self.assertEqual((self.deploy_state / "previous-live-env").read_text(), previous_live)
        result = self.run_deploy(mode="rollback", image="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.current(), OLD_ID)
        self.assertEqual(self.live_settings()["LIVE_FEEDER_ENABLED"], "false")

    def test_omitted_live_arguments_preserve_running_settings(self):
        self.state["live"] = {"LIVE_FEEDER_ENABLED": "true", "LIVE_ALLOW_REMOTE_DATABASE": "true"}
        self.save()
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.live_settings(), self.state["live"])

    def test_legacy_rollback_record_uses_disabled_live_defaults(self):
        self.assertEqual(self.run_deploy(live_feeder="true", live_allow_remote="true").returncode, 0)
        (self.deploy_state / "previous-live-env").unlink()
        result = self.run_deploy(mode="rollback", image="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.current(), OLD_ID)
        self.assertEqual(self.live_settings(), {
            "LIVE_FEEDER_ENABLED": "false", "LIVE_ALLOW_REMOTE_DATABASE": "false",
        })

    def test_invalid_live_values_are_rejected_before_deployment(self):
        for value in ("TRUE", "1", "true'; touch /tmp/injected; '"):
            for argument in ("live_feeder", "live_allow_remote"):
                with self.subTest(value=value, argument=argument):
                    result = self.run_deploy(**{argument: value})
                    self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.commands(), [])

    def test_mutable_tag_and_other_repository_are_rejected(self):
        for image in ("ghcr.io/chagokai/server:latest", IMAGE.replace("chagokai/server", "other/client")):
            with self.subTest(image=image):
                self.assertNotEqual(self.run_deploy(image=image).returncode, 0)
        self.assertEqual(self.up_commands(), [])
        self.assertFalse(any(cmd[1:2] == ["pull"] for cmd in self.commands()))

    def test_missing_backend_requires_bootstrap_without_starting_other_services(self):
        self.state["missing_container"] = True
        self.save()
        result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("bootstrap the stack first", result.stderr)
        self.assertEqual(self.up_commands(), [])

    def test_server_lock_blocks_overlapping_deployments(self):
        with (self.deploy_state / "deploy.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("already running", result.stderr)
        self.assertFalse(any(cmd[0] == "docker" for cmd in self.commands()))

    def test_lock_is_held_until_shell_finishes_and_released_after_exit(self):
        self.state["pause_pull"] = True
        self.save()
        process = subprocess.Popen(
            ["bash", str(SCRIPT), "deploy", IMAGE, str(self.config)],
            env={**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}", "MOCK_ROOT": str(self.root)},
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        try:
            deadline = time.monotonic() + 5
            while not (self.root / "pull-started").exists():
                self.assertIsNone(process.poll(), "Deployment exited before the lock probe")
                self.assertLess(time.monotonic(), deadline)
                time.sleep(0.02)
            with (self.deploy_state / "deploy.lock").open("w") as lock:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            (self.root / "continue-pull").touch()
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 0, stdout + stderr)
            with (self.deploy_state / "deploy.lock").open("w") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_failed_release_preserves_previous_successful_release_records(self):
        self.assertEqual(self.run_deploy().returncode, 0)
        self.state = json.loads((self.root / "docker.json").read_text())
        third_id = "sha256:" + "d" * 64
        third_image = IMAGE.replace("c" * 64, "e" * 64)
        self.state.update(new=third_id, bad_api=True)
        self.state["images"][third_image] = third_id
        self.save()
        result = self.run_deploy(image=third_image)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.current(), NEW_ID)
        self.assertEqual((self.deploy_state / "current-image").read_text().strip(), IMAGE)
        self.assertEqual((self.deploy_state / "previous-image").read_text().strip(), ROLLBACK)


class SshTransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        ssh = self.root / "ssh"
        ssh.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, pathlib, stat, sys\n"
            "args = sys.argv[1:]\n"
            "key = pathlib.Path(args[args.index('-i') + 1])\n"
            "pathlib.Path(os.environ['SSH_CAPTURE']).write_text(json.dumps({"
            "'args': args, 'key': str(key), 'mode': stat.S_IMODE(key.stat().st_mode), "
            "'script': sys.stdin.read()}))\n"
            "sys.exit(int(os.environ.get('SSH_EXIT', '0')))\n"
        )
        ssh.chmod(0o755)
        self.env = {
            **os.environ,
            "PATH": f"{self.root}:{os.environ['PATH']}",
            "DEPLOY_HOST": "deploy.example.com",
            "DEPLOY_USER": "deploy",
            "DEPLOY_PORT": "2222",
            "DEPLOY_CONFIG": "/opt/chagok/client-deploy.conf",
            "DEPLOY_SSH_KEY": "test-private-key",
            "DEPLOY_KNOWN_HOSTS": "test-host-key",
            "SSH_CAPTURE": str(self.root / "ssh.json"),
        }

    def run_transport(self, image=IMAGE):
        return subprocess.run(
            ["bash", str(SCRIPT.with_name("deploy-over-ssh.sh")), "deploy", image],
            env=self.env,
            capture_output=True,
            text=True,
            timeout=10,
        )

    def test_transport_checks_host_key_and_removes_private_key_after_use(self):
        result = self.run_transport()
        self.assertEqual(result.returncode, 0, result.stderr)
        captured = json.loads((self.root / "ssh.json").read_text())
        self.assertIn("StrictHostKeyChecking=yes", captured["args"])
        self.assertEqual(captured["mode"], 0o600)
        self.assertFalse(Path(captured["key"]).exists())
        self.assertEqual(captured["script"], SCRIPT.read_text())
        self.assertNotIn("test-private-key", result.stdout + result.stderr)

    def test_transport_preserves_remote_failure_status(self):
        self.env["SSH_EXIT"] = "41"
        result = self.run_transport()
        self.assertEqual(result.returncode, 41)
        captured = json.loads((self.root / "ssh.json").read_text())
        self.assertFalse(Path(captured["key"]).exists())

    def test_shell_characters_are_rejected_before_remote_execution(self):
        result = self.run_transport(image=IMAGE + "'; touch /tmp/injected; '")
        self.assertNotEqual(result.returncode, 0)
        self.env["DEPLOY_CONFIG"] = "/opt/chagok/config'; touch /tmp/injected; '"
        self.assertNotEqual(self.run_transport().returncode, 0)
        self.assertFalse((self.root / "ssh.json").exists())

    def test_invalid_ports_are_rejected_before_remote_execution(self):
        for port in ("0", "65536", "abc", "22; echo invalid"):
            with self.subTest(port=port):
                self.env["DEPLOY_PORT"] = port
                self.assertNotEqual(self.run_transport().returncode, 0)
        self.assertFalse((self.root / "ssh.json").exists())

    def test_invalid_live_values_are_rejected_before_remote_execution(self):
        for name in ("LIVE_FEEDER_ENABLED", "LIVE_ALLOW_REMOTE_DATABASE"):
            with self.subTest(name=name):
                self.env[name] = "true'; touch /tmp/injected; '"
                self.assertNotEqual(self.run_transport().returncode, 0)
                self.env.pop(name)
        self.assertFalse((self.root / "ssh.json").exists())


if __name__ == "__main__":
    unittest.main()
