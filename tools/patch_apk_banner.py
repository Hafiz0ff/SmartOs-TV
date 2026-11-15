#!/usr/bin/env python3
"""
patch_apk_banner.py — Leanback TV Banner Patcher & Android 9+ v1 APK Signer.

Replaces TV leanback banners in APK packages, ensures 4096-byte page alignment
for uncompressed native libraries (.so), and applies an Android 9 compatible
JAR/v1 signature without incompatible S/MIME capabilities.
"""

import argparse
import base64
import datetime
import hashlib
import os
import sys
import zipfile

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs7
    from cryptography.x509.oid import NameOID
except ImportError:
    print("Error: 'cryptography' package is required. Install via: pip install cryptography")
    sys.exit(1)


def generate_self_signed_cert():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "SmartTV"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "SmartTV-Hub"),
    ])
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=1))
        .not_valid_after(datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=10000))
        .sign(key, hashes.SHA256())
    )
    return key, cert


def wrap_manifest_line(line: str) -> bytes:
    res = []
    bline = line.encode("utf-8")
    while len(bline) > 70:
        res.append(bline[:70] + b"\r\n ")
        bline = bline[70:]
    res.append(bline + b"\r\n")
    return b"".join(res)


def patch_and_sign(src_apk: str, dst_apk: str, replacements: dict):
    if not os.path.isfile(src_apk):
        raise FileNotFoundError(f"Source APK not found: {src_apk}")

    print(f"[*] Reading source APK: {src_apk}")
    manifest_entries = {}
    entries = []

    with zipfile.ZipFile(src_apk, "r") as zin:
        for item in zin.infolist():
            if item.filename.startswith("META-INF/"):
                continue
            content = replacements.get(item.filename, zin.read(item.filename))
            entries.append((item, content))

    key, cert = generate_self_signed_cert()

    print(f"[*] Writing patched APK with 4096-byte .so alignment: {dst_apk}")
    os.makedirs(os.path.dirname(os.path.abspath(dst_apk)), exist_ok=True)
    with zipfile.ZipFile(dst_apk, "w") as zout:
        for item, content in entries:
            if item.compress_type == 0 and item.filename.endswith(".so"):
                cur_pos = zout.fp.tell()
                local_header_size = 30 + len(item.filename.encode("utf-8"))
                needed_padding = (4096 - ((cur_pos + local_header_size) % 4096)) % 4096
                item.extra = b"\x00" * needed_padding
            elif item.compress_type == 0:
                cur_pos = zout.fp.tell()
                local_header_size = 30 + len(item.filename.encode("utf-8"))
                needed_padding = (4 - ((cur_pos + local_header_size) % 4)) % 4
                item.extra = b"\x00" * needed_padding
            else:
                item.extra = b""

            zout.writestr(item, content, compress_type=item.compress_type)
            digest = base64.b64encode(hashlib.sha256(content).digest()).decode("ascii")
            manifest_entries[item.filename] = digest

        # 1. Generate META-INF/MANIFEST.MF
        mf_lines = [
            b"Manifest-Version: 1.0\r\n",
            b"Created-By: 1.0 (Android)\r\n",
            b"\r\n",
        ]
        entry_sections = {}
        for fn in sorted(manifest_entries.keys()):
            sec = wrap_manifest_line(f"Name: {fn}") + wrap_manifest_line(f"SHA-256-Digest: {manifest_entries[fn]}") + b"\r\n"
            entry_sections[fn] = sec
            mf_lines.append(sec)

        manifest_bytes = b"".join(mf_lines)
        zout.writestr("META-INF/MANIFEST.MF", manifest_bytes, compress_type=zipfile.ZIP_DEFLATED)

        # 2. Generate META-INF/CERT.SF
        sf_lines = [
            b"Signature-Version: 1.0\r\n",
            b"Created-By: 1.0 (Android)\r\n",
            wrap_manifest_line(f"SHA-256-Digest-Manifest: {base64.b64encode(hashlib.sha256(manifest_bytes).digest()).decode('ascii')}"),
            b"\r\n",
        ]
        for fn in sorted(manifest_entries.keys()):
            sec_digest = base64.b64encode(hashlib.sha256(entry_sections[fn]).digest()).decode("ascii")
            sf_lines.append(wrap_manifest_line(f"Name: {fn}") + wrap_manifest_line(f"SHA-256-Digest: {sec_digest}") + b"\r\n")

        sf_bytes = b"".join(sf_lines)
        zout.writestr("META-INF/CERT.SF", sf_bytes, compress_type=zipfile.ZIP_DEFLATED)

        # 3. Generate META-INF/CERT.RSA (strictly NoCapabilities for Android 9 compatibility)
        builder = pkcs7.PKCS7SignatureBuilder().set_data(sf_bytes).add_signer(cert, key, hashes.SHA256())
        rsa_bytes = builder.sign(
            serialization.Encoding.DER,
            [pkcs7.PKCS7Options.DetachedSignature, pkcs7.PKCS7Options.NoCapabilities],
        )
        zout.writestr("META-INF/CERT.RSA", rsa_bytes, compress_type=zipfile.ZIP_DEFLATED)

    print(f"[✓] Successfully generated and signed: {dst_apk}")


def main():
    parser = argparse.ArgumentParser(description="Patch APK leanback banner & sign for Android 9+")
    parser.add_argument("--app", choices=["yt", "vk"], help="Predefined target app: 'yt' (TizenTube) or 'vk' (VK Video)")
    parser.add_argument("--src", help="Source APK path")
    parser.add_argument("--dst", help="Destination APK path")
    parser.add_argument("--banner", help="Replacement banner image path (.png)")
    parser.add_argument("--entry", help="Resource entry path inside APK (e.g. res/drawable-xhdpi/app_banner)")

    args = parser.parse_args()
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    if args.app == "yt":
        src = args.src or os.path.join(base_dir, "apks", "TizenTube-Cobalt-VOT.apk")
        dst = args.dst or os.path.join(base_dir, "apks", "TizenTube-Cobalt-Custom.apk")
        banner = args.banner or os.path.join(base_dir, "tools", "assets", "custom_yt_banner.png")
        entry = args.entry or "res/drawable-xhdpi/app_banner"
    elif args.app == "vk":
        src = args.src or os.path.join(base_dir, "apks", "VK-VideoApp-2.25.5-mod.apk")
        dst = args.dst or os.path.join(base_dir, "apks", "VK-VideoApp-Custom.apk")
        banner = args.banner or os.path.join(base_dir, "tools", "assets", "custom_vk_banner.png")
        entry = args.entry or "res/Zg.png"
    else:
        if not (args.src and args.dst and args.banner and args.entry):
            parser.error("Specify --app [yt|vk] or provide all of --src, --dst, --banner, --entry")
        src, dst, banner, entry = args.src, args.dst, args.banner, args.entry

    with open(banner, "rb") as bf:
        banner_bytes = bf.read()

    patch_and_sign(src, dst, {entry: banner_bytes})


if __name__ == "__main__":
    main()
