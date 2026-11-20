"""Build and sign manually dispatched APKs with Android SDK tools."""
import base64
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory


PACKAGE = "com.resker666.yeshunguangpet"
GRADLE_CONFIG = Path("app/build.gradle.kts")
RELEASE_APK = Path("app/build/outputs/apk/release/app-release-unsigned.apk")
UPGRADE_BASE = "upgrade-base-test-only.apk"
UPGRADE_TARGET = "upgrade-target-test-only.apk"


def build(output, upgrade_test):
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run(["./gradlew", "--no-daemon", "testDebugUnitTest", "lintRelease", "assembleRelease"], check=True)
    shutil.copyfile(RELEASE_APK, output / UPGRADE_BASE)
    if upgrade_test:
        original = GRADLE_CONFIG.read_text()
        candidate, count = re.subn(
            r"^(\s*versionCode\s*=\s*)([0-9]+)$",
            lambda match: f"{match[1]}{int(match[2]) + 1}",
            original,
            flags=re.MULTILINE,
        )
        if count != 1:
            raise ValueError("Expected one literal versionCode in app/build.gradle.kts")
        try:
            GRADLE_CONFIG.write_text(candidate)
            subprocess.run(["./gradlew", "--no-daemon", "assembleRelease"], check=True)
            shutil.copyfile(RELEASE_APK, output / UPGRADE_TARGET)
        finally:
            GRADLE_CONFIG.write_text(original)


def required_env(name):
    value = os.environ.get(name)
    if not value:
        raise ValueError(f"Configure {name} for release mode.")
    return value


def signing_key(work, upgrade_test):
    key = work / "signing.keystore"
    env = dict(os.environ)
    if upgrade_test:
        password = secrets.token_hex(24)
        env.update(ANDROID_KEYSTORE_PASSWORD=password, ANDROID_KEY_PASSWORD=password)
        subprocess.run([
            "keytool", "-genkeypair", "-storetype", "PKCS12", "-keystore", key,
            "-alias", "upgrade-test", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=YeShunguangPet Upgrade Test", "-validity", "30",
            "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
            "-keypass:env", "ANDROID_KEY_PASSWORD",
        ], check=True, env=env)
        return key, "upgrade-test", env, None

    required_env("ANDROID_KEYSTORE_PASSWORD")
    required_env("ANDROID_KEY_PASSWORD")
    fingerprint = re.sub(r"[:\s]", "", required_env("ANDROID_SIGNING_CERT_SHA256")).lower()
    if not re.fullmatch(r"[a-f0-9]{64}", fingerprint):
        raise ValueError("ANDROID_SIGNING_CERT_SHA256 must contain a SHA-256 certificate fingerprint.")
    key.write_bytes(base64.b64decode("".join(required_env("ANDROID_KEYSTORE_BASE64").split()), validate=True))
    return key, required_env("ANDROID_KEY_ALIAS"), env, fingerprint


def apk_version(aapt2, apk):
    badging = subprocess.check_output([aapt2, "dump", "badging", apk], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    if package is None or package[1] != PACKAGE or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", package[3]):
        raise ValueError(f"Unexpected package metadata for {apk.name}")
    return int(package[2]), package[3]


def sign(root, upgrade_test):
    sdk_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    apksigner, zipalign, aapt2 = (sdk_tools / tool for tool in ("apksigner", "zipalign", "aapt2"))
    output = root / "signed"
    output.mkdir(parents=True, exist_ok=True)
    apk_names = (UPGRADE_BASE, UPGRADE_TARGET) if upgrade_test else (UPGRADE_BASE,)
    metadata = [apk_version(aapt2, root / "unsigned" / name) for name in apk_names]
    if upgrade_test and metadata[1][0] != metadata[0][0] + 1:
        raise ValueError("Upgrade test APK must increment versionCode by one.")

    os.umask(0o077)
    with TemporaryDirectory(dir=root) as directory:
        work = Path(directory)
        key, alias, env, expected = signing_key(work, upgrade_test)
        aligned = work / "aligned.apk"
        for name, (version_code, version_name) in zip(apk_names, metadata):
            signed_name = name if upgrade_test else f"YeShunguangPet-Android-v{version_name}.apk"
            signed = output / signed_name
            subprocess.run([zipalign, "-P", "16", "-f", "4", root / "unsigned" / name, aligned], check=True)
            subprocess.run([
                apksigner, "sign", "--ks", key, "--ks-key-alias", alias,
                "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
                "--out", signed, aligned,
            ], check=True, env=env)
            subprocess.run([zipalign, "-c", "-P", "16", "4", signed], check=True)
            report = subprocess.check_output([apksigner, "verify", "--print-certs", signed], text=True)
            signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", report, re.MULTILINE)
            if len(signers) != 1 or (expected and signers[0].lower() != expected):
                raise ValueError(f"Unexpected signing certificate for {signed_name}")
            expected = signers[0].lower()
            print(f"Verified {signed_name}: {PACKAGE}, versionCode={version_code}, SHA-256={expected}", flush=True)


if __name__ == "__main__":
    root = Path(os.environ["RUNNER_TEMP"]) / "pet-release"
    mode = os.environ["BUILD_MODE"]
    if mode not in ("upgrade-test", "release"):
        sys.exit(f"Unsupported build mode: {mode}")
    if sys.argv[1:] == ["build"]:
        build(root / "unsigned", upgrade_test=(mode == "upgrade-test"))
    elif sys.argv[1:] == ["sign"]:
        sign(root, upgrade_test=(mode == "upgrade-test"))
    else:
        sys.exit("Usage: release-apks.py {build|sign}")
