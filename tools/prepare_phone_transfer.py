"""Create and verify a smaller transfer ZIP without changing the signed APK."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "artifacts" / "Folio-1.0.0.apk"
ARCHIVE = ROOT / "artifacts" / "Folio-Phone-Transfer.zip"
TRANSFER = Path.home() / "Downloads" / "Folio-Transfer-2026-10-15"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    source_hash = sha256(APK)
    with zipfile.ZipFile(APK) as package:
        assert package.testzip() is None, "Source APK failed CRC verification"
    instructions = (
        "FOLIO - PHONE TRANSFER\n\n"
        "This ZIP contains the original signed Folio APK, unchanged.\n"
        "Download the ZIP onto the phone, extract it in Files / My Files,\n"
        "then tap Folio-1.0.0.apk inside the extracted folder to install.\n"
        "Do not try to install the outer ZIP.\n\n"
        "If the cloud download still fails, use a USB data cable:\n"
        "unlock the phone, select File transfer in its USB notification,\n"
        "and copy Folio-1.0.0.apk from this folder on the PC to the phone's\n"
        "Download folder. No USB debugging is required for file transfer.\n\n"
        "This is an alternative transfer package, not a confirmed fix for\n"
        "the cloud download failure. No phone installation has been verified.\n\n"
        f"Original APK SHA-256: {source_hash}\n"
    )
    with zipfile.ZipFile(ARCHIVE, "x", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.write(APK, APK.name)
        archive.writestr("READ-ME-FIRST.txt", instructions)
    with zipfile.ZipFile(ARCHIVE) as archive:
        assert archive.testzip() is None, "Transfer ZIP failed CRC verification"
        assert hashlib.sha256(archive.read(APK.name)).hexdigest() == source_hash
    TRANSFER.mkdir(parents=True, exist_ok=True)
    for source in (APK, ARCHIVE):
        destination = TRANSFER / source.name
        if destination.exists():
            raise FileExistsError(f"Refusing to replace existing file: {destination}")
        shutil.copy2(source, destination)
        assert sha256(destination) == sha256(source), "Transfer copy hash mismatch"
    (TRANSFER / "READ-ME-FIRST.txt").write_text(instructions, encoding="utf-8")
    report = {
        "original_apk_bytes": APK.stat().st_size,
        "transfer_zip_bytes": ARCHIVE.stat().st_size,
        "reduction_percent": round((1 - ARCHIVE.stat().st_size / APK.stat().st_size) * 100, 1),
        "original_apk_sha256": source_hash,
        "transfer_zip_sha256": sha256(ARCHIVE),
        "source_apk_crc": "passed",
        "transfer_zip_crc": "passed",
        "embedded_apk_hash_equals_original": True,
        "copies_verified": True,
        "outside_cloud_folder": str(TRANSFER),
        "phone_download_or_install_verified": False,
    }
    (ROOT / "artifacts" / "phone-transfer-verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
