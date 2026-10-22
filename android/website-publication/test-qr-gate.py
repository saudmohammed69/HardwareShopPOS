"""Local unit tests for the QR generator's deployment approval gate.

Synthetic values below exercise parsing only. They do not authorize a real
deployment or issue a QR code.
"""
import hashlib
import importlib.util
import json
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("make-verified-qr.py")
SPEC = importlib.util.spec_from_file_location("verified_qr", MODULE_PATH)
QR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QR)


class QrApprovalGateTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            "mode": "MAINTENANCE",
            "liveOrdering": False,
            "customerMutations": False,
            "functions": False,
        }
        self.manifest_bytes = json.dumps(self.manifest, sort_keys=True).encode()
        self.approval = {
            "accountApproved": True,
            "projectApproved": True,
            "contentApproved": True,
            "deploymentApproved": True,
            "freePlanVerified": True,
            "pagesProject": "hardware-shoppos",
            "websiteUrl": "https://hardware-shoppos.pages.dev/",
            "artifactManifestSha256": hashlib.sha256(self.manifest_bytes).hexdigest(),
            "deploymentId": "synthetic-test-only",
            "approvedAt": "2026-10-22T00:00:00Z",
        }

    def test_accepts_exact_static_artifact_and_project_url(self):
        self.assertEqual(
            QR.approved_target(self.approval, self.manifest_bytes),
            "https://hardware-shoppos.pages.dev/",
        )

    def test_rejects_each_missing_approval(self):
        for key in (
            "accountApproved",
            "projectApproved",
            "contentApproved",
            "deploymentApproved",
            "freePlanVerified",
        ):
            with self.subTest(key=key):
                approval = dict(self.approval, **{key: False})
                with self.assertRaises(ValueError):
                    QR.approved_target(approval, self.manifest_bytes)

    def test_rejects_wrong_url_or_manifest_hash(self):
        for updates in (
            {"websiteUrl": "http://hardware-shoppos.pages.dev/"},
            {"websiteUrl": "https://another-site.pages.dev/"},
            {"artifactManifestSha256": "0" * 64},
        ):
            with self.subTest(updates=updates):
                with self.assertRaises(ValueError):
                    QR.approved_target(dict(self.approval, **updates), self.manifest_bytes)

    def test_rejects_live_or_unknown_artifact_mode(self):
        for updates in (
            {"liveOrdering": True},
            {"customerMutations": True},
            {"functions": True},
            {"mode": "LIVE_ORDERS"},
        ):
            with self.subTest(updates=updates):
                artifact = dict(self.manifest, **updates)
                artifact_bytes = json.dumps(artifact, sort_keys=True).encode()
                approval = dict(
                    self.approval,
                    artifactManifestSha256=hashlib.sha256(artifact_bytes).hexdigest(),
                )
                with self.assertRaises(ValueError):
                    QR.approved_target(approval, artifact_bytes)


if __name__ == "__main__":
    unittest.main(verbosity=2)
