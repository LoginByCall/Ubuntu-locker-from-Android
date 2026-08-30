#!/usr/bin/env python3
"""
patch-libzt-detach.py — убрать jvm->DetachCurrentThread() из JNI-обёрток
zts_node_stop / zts_node_free в libzt.so (внутри AAR или отдельный .so).

Зачем: биндинг libzt после zts_node_stop()/zts_node_free() вызывает
DetachCurrentThread() на ПОТОКЕ ВЫЗОВА. Из Java-потока ART это всегда фатально:
  "attempting to detach while still running code" → SIGABRT
(наблюдалось на OnePlus 15 / Android 16 при idle-stop узла в ZtEmbedded.release).
Вызов не нужен: Java-поток и так остаётся присоединённым к VM.

Что делает: в теле каждой из двух обёрток находит пару инструкций
  ldr x8, [x8, #40]   ; слот JavaVM::DetachCurrentThread (JNIInvokeInterface[5])
  blr x8
и заменяет `blr x8` на `nop`. Идемпотентно. Только arm64.

Использование:
  python3 patch-libzt-detach.py app/libs/libzt-release.aar   # правит AAR на месте
  python3 patch-libzt-detach.py libzt.so                      # правит .so на месте
"""
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

SYMBOLS = (
    "Java_com_zerotier_sockets_ZeroTierNative_zts_1node_1stop",
    "Java_com_zerotier_sockets_ZeroTierNative_zts_1node_1free",
)
LDR_DETACH = bytes.fromhex("081540f9")  # ldr x8, [x8, #40]
BLR_X8 = bytes.fromhex("00013fd6")      # blr x8
NOP = bytes.fromhex("1f2003d5")         # nop
FUNC_SCAN = 64                          # обёртки короткие (~60 байт)


def vaddr_to_offset(elf: bytes, vaddr: int) -> int:
    """vaddr → смещение в файле по PT_LOAD сегментам (ELF64 LE)."""
    assert elf[:4] == b"\x7fELF" and elf[4] == 2 and elf[5] == 1, "ожидается ELF64 LE"
    e_phoff, = struct.unpack_from("<Q", elf, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", elf, 0x36)
    for i in range(e_phnum):
        p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz = struct.unpack_from(
            "<IIQQQQ", elf, e_phoff + i * e_phentsize)
        if p_type == 1 and p_vaddr <= vaddr < p_vaddr + p_filesz:
            return vaddr - p_vaddr + p_offset
    raise SystemExit(f"vaddr {vaddr:#x} вне PT_LOAD")


def symbol_addrs(so: Path) -> dict:
    out = subprocess.run(["nm", "-D", str(so)], capture_output=True, text=True, check=True).stdout
    addrs = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[2] in SYMBOLS:
            addrs[parts[2]] = int(parts[0], 16)
    missing = set(SYMBOLS) - addrs.keys()
    if missing:
        raise SystemExit(f"символы не найдены: {missing}")
    return addrs


def patch_so(so: Path) -> int:
    data = bytearray(so.read_bytes())
    patched = 0
    for sym, vaddr in symbol_addrs(so).items():
        off = vaddr_to_offset(data, vaddr)
        body = bytes(data[off:off + FUNC_SCAN])
        i = body.find(LDR_DETACH + BLR_X8)
        if i >= 0:
            data[off + i + 4:off + i + 8] = NOP
            print(f"{sym}: blr→nop @ {vaddr + i + 4:#x}")
            patched += 1
        elif body.find(LDR_DETACH + NOP) >= 0:
            print(f"{sym}: уже пропатчен")
        else:
            raise SystemExit(f"{sym}: паттерн не найден — другая сборка libzt? не трогаю")
    so.write_bytes(data)
    return patched


def patch_aar(aar: Path) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        tmpd = Path(tmp)
        with zipfile.ZipFile(aar) as z:
            names = z.namelist()
            z.extractall(tmpd)
        sos = [n for n in names if n.endswith("/libzt.so")]
        if not sos:
            raise SystemExit("в AAR нет libzt.so")
        for n in sos:
            print(n, end=": ")
            patch_so(tmpd / n)
        # пересобираем архив с тем же порядком записей
        with zipfile.ZipFile(aar, "w", zipfile.ZIP_DEFLATED) as z:
            for n in names:
                z.write(tmpd / n, n)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    target = Path(sys.argv[1])
    if target.suffix == ".aar":
        patch_aar(target)
    else:
        patch_so(target)


if __name__ == "__main__":
    main()
