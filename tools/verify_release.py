"""Verify the personal release and stage an exact copy outside cloud-synced folders."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/build/outputs/apk/release/app-release.apk"
OLD = ROOT / "artifacts/Folio-1.0.0.apk"
DEST = ROOT / "artifacts/Folio-1.0.1.apk"
REPORT = ROOT / "artifacts/verification-1.0.1"
SDK = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/build-tools/35.0.0"
TRANSFER = Path.home() / "Downloads/Folio-1.0.1-Offline-Voices"


def sha(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def run(args, log):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    (REPORT / log).write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{log} failed: {result.stdout} {result.stderr}")
    return result.stdout


def verify():
    REPORT.mkdir(parents=True, exist_ok=True)
    assert sha(OLD) == "5a2ff62377a1a0c0a38cdb74d2a471ffead96f898cb3441a770b80e2fc4b0800", "Original APK changed"
    signer = ["java", "-jar", SDK / "lib/apksigner.jar", "verify", "--verbose", "--print-certs"]
    old_sign = run(signer + [OLD], "original-signature.txt")
    new_sign = run(signer + [SOURCE], "signature.txt")
    certificate = re.compile(r"Signer #1 certificate SHA-256 digest: (\w+)")
    assert certificate.search(old_sign).group(1) == certificate.search(new_sign).group(1), "Update signing certificate differs"
    run([SDK / "zipalign.exe", "-c", "-P", "16", "-v", "4", SOURCE], "alignment.txt")
    metadata = run([SDK / "aapt.exe", "dump", "badging", SOURCE], "apk-metadata.txt")
    assert "name='com.folio.reader' versionCode='2' versionName='1.0.1'" in metadata
    assert "application-debuggable" not in metadata
    assert "android.permission.INTERNET" not in metadata
    assert "sdkVersion:'26'" in metadata
    model_count = 0
    native = {}
    with zipfile.ZipFile(SOURCE) as archive, zipfile.ZipFile(OLD) as previous:
        assert archive.testzip() is None
        for asset in ["index.html", "app.css", "app.js", "core.js"]:
            assert archive.read("assets/" + asset) == (ROOT / "app/src/main/assets" / asset).read_bytes(), asset
        # Bundled recognition data and native binaries are unchanged, only compressed differently.
        for entry in previous.namelist():
            if entry.startswith("assets/mlkit-google-ocr-models/") or entry.endswith(".so"):
                assert archive.read(entry) == previous.read(entry), entry
                model_count += entry.startswith("assets/")
            if entry.endswith(".so"):
                data = archive.read(entry)
                assert data[:4] == b"\x7fELF"
                is64 = data[4] == 2
                endian = "<" if data[5] == 1 else ">"
                offset = struct.unpack_from(endian + ("Q" if is64 else "I"), data, 32 if is64 else 28)[0]
                size, count = struct.unpack_from(endian + "HH", data, 54 if is64 else 42)
                alignment = []
                for i in range(count):
                    start = offset + i * size
                    if struct.unpack_from(endian + "I", data, start)[0] == 1:
                        alignment.append(struct.unpack_from(endian + ("Q" if is64 else "I"), data, start + (48 if is64 else 28))[0])
                assert alignment and min(alignment) >= (16384 if is64 else 4096)
                assert archive.getinfo(entry).compress_type == zipfile.ZIP_DEFLATED
                native[entry.split("/")[1]] = alignment
        assert set(native) == {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
        assert model_count > 0
    shutil.copy2(SOURCE, DEST)
    TRANSFER.mkdir(parents=True, exist_ok=True)
    copy = TRANSFER / DEST.name
    shutil.copy2(DEST, copy)
    digest = sha(SOURCE)
    assert sha(DEST) == sha(copy) == digest
    notes = """FOLIO 1.0.1 - OFFLINE VOICES UPDATE

Copy Folio-1.0.1.apk to your phone's Download folder using USB File transfer.
Open it from the phone's Files app and install over Folio 1.0.0.
Do not uninstall the old app if you want to retain your library and statistics.

In Folio, tap Voice on the listening screen or open Settings.
Choose your language/accent and an offline voice, then tap Hear sample.
Normal pitch & pace resets both controls to 1.0.
Automatic chooses an installed offline voice using the engine's quality rating.
For more voices, use Download voices, then Reload voices after downloading.
Speech never intentionally falls back to a network voice.

This is a personal APK signed with the same local key as 1.0.0.
Automated checks do not replace testing actual speech/background playback on a phone.
The exact cause of the earlier Drive download failure remains unknown.
"""
    (TRANSFER / "READ-ME-FIRST.txt").write_text(notes, encoding="utf-8")
    (ROOT / "artifacts/START-HERE-1.0.1.txt").write_text(notes, encoding="utf-8")
    (DEST.with_suffix(".apk.sha256")).write_text(digest + "  " + DEST.name + "\n", encoding="ascii")
    result = {"version": "1.0.1", "apk": str(DEST), "bytes": DEST.stat().st_size, "sha256": digest,
              "old_apk_unchanged": True, "same_signing_certificate": True, "debuggable": False,
              "zip_crc": "passed", "zip_alignment": "passed", "bundled_ui_matches_sources": True,
              "ocr_model_files_identical": model_count, "native_elf_load_alignment": native,
              "internet_permission": False, "outside_cloud_copy": str(copy), "copy_hash_matches": True,
              "physical_device_validation": "Not performed by the build tools"}
    (REPORT / "verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    verify()
