import os
from pathlib import Path
import subprocess
import unittest


class TestDatabaseBoundary(unittest.TestCase):
    def assert_rejected_before_test_jvm(self, url):
        env = os.environ.copy()
        for key in list(env):
            if key.startswith(("DB_", "TEST_DB_", "SPRING_", "CHAGOK_", "LIVE_", "JOB_", "BOOTSTRAP_")):
                env.pop(key)
        if url is not None:
            env["DB_URL"] = url
        # This suite has mocked repositories and never connects to a DB, even before the guard exists.
        result = subprocess.run(
            ["./gradlew", "test", "--tests", "com.skala.chagok.dlanalysis.LiveFeatureStartupTest", "--rerun-tasks", "--console=plain"],
            cwd=Path(__file__).resolve().parents[2] / "chagok",
            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=90,
        )
        self.assertNotEqual(result.returncode, 0, "test task accepted an unsafe or implicit database target")
        self.assertIn("Tests require an explicit loopback", result.stdout, result.stdout[-600:])

    def test_remote_database_cannot_be_used_by_standard_test_task(self):
        self.assert_rejected_before_test_jvm("jdbc:postgresql://cloud-db:15432/chagok")

    def test_missing_target_does_not_fall_back_to_project_env(self):
        self.assert_rejected_before_test_jvm(None)

    def test_loopback_authority_cannot_hide_a_driver_host_override(self):
        self.assert_rejected_before_test_jvm("jdbc:postgresql://127.0.0.1/chagok?PGHOST=cloud-db")
