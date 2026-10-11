#!/usr/bin/env python3
"""Build and sign SightBridge Testing without exposing signing passwords.

The persistent testing key stays outside the checkout. Back up its entire
directory securely: both the keystore and password file are needed for updates.
"""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import subprocess


ROOT = Path(__file__).resolve().parents[1]
ALIAS = "sightbridge-testing"


def run(args, **kwargs):
    subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", default="arm64-v8a", help="Default: current OnePlus ABI")
    parser.add_argument("--signing-dir", type=Path,
                        default=Path.home() / ".local/share/sightbridge/signing/testing")
    args = parser.parse_args()
    directory = args.signing_dir.expanduser().resolve()
    if directory == ROOT or ROOT in directory.parents:
        parser.error("The signing directory must be outside the repository")
    if directory == Path("/tmp") or Path("/tmp") in directory.parents:
        parser.error("Use persistent storage, not /tmp, for update continuity")
    os.umask(0o077)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    keystore = directory / "sightbridge-testing.p12"
    password = directory / "keystore-password"
    if keystore.exists() and not password.exists():
        parser.error("Existing keystore has no password file; restore the file, do not replace the key")
    if not password.exists():
        with password.open("x") as output:
            output.write(secrets.token_urlsafe(48) + "\n")
    password.chmod(0o600)
    java_home = Path(os.environ.get("SIGHTBRIDGE_JAVA_HOME", "/usr/lib/jvm/java-17-openjdk"))
    environment = dict(os.environ, JAVA_HOME=str(java_home),
                       PATH=str(java_home / "bin") + os.pathsep + os.environ.get("PATH", ""))
    if not keystore.exists():
        run([java_home / "bin/keytool", "-genkeypair", "-storetype", "PKCS12",
             "-keystore", keystore, "-storepass:file", password, "-keypass:file", password,
             "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA",
             "-validity", "10000", "-dname", "CN=SightBridge Testing"], env=environment)
    keystore.chmod(0o600)

    # Read only SDK location, never print local GitHub Packages credentials.
    sdk = None
    local_properties = ROOT / "local.properties"
    if local_properties.exists():
        for line in local_properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].strip()
                break
    sdk = sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        parser.error("Set ANDROID_HOME or sdk.dir in local.properties")
    tools = Path(sdk) / "build-tools/35.0.0"
    run([ROOT / "gradlew", f"-Dorg.gradle.java.home={java_home}",
         f"-PsightbridgeAbi={args.abi}", ":app:assembleQa"], cwd=ROOT, env=environment)
    output_dir = ROOT / "app/build/outputs/apk/qa"
    unsigned = output_dir / "sightbridge-unsigned.apk"
    signed = output_dir / "sightbridge.apk"
    candidate = output_dir / ".sightbridge-signed.apk"
    run([tools / "zipalign", "-c", "-P", "16", "4", unsigned], env=environment)
    run([tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", ALIAS,
         "--ks-pass", f"file:{password}",
         "--out", candidate, unsigned], env=environment)
    run([tools / "apksigner", "verify", "--verbose", "--print-certs", candidate], env=environment)
    run([tools / "zipalign", "-c", "-P", "16", "4", candidate], env=environment)
    run(["python3", ROOT / "scripts/check_native_alignment.py", candidate], env=environment)
    # Publish only after all checks pass; keep an earlier verified APK on failure.
    candidate.replace(signed)
    signed.chmod(0o644)
    output_dir.chmod(0o755)
    digest = hashlib.sha256(signed.read_bytes()).hexdigest()
    checksum = output_dir / "sightbridge.apk.sha256"
    checksum.write_text(f"{digest}  sightbridge.apk\n")
    checksum.chmod(0o644)
    print(f"Signed testing APK: {signed}")
    print(f"SHA-256: {digest}")
    print(f"Back up this private signing directory securely: {directory}")


if __name__ == "__main__":
    main()
