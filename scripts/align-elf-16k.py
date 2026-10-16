#!/usr/bin/env python3
"""Pad ELF PT_LOAD so p_align >= 16KiB and p_offset ≡ p_vaddr (mod 16KiB).
Android 15+ 16KB-page devices (Snapdragon 8 Elite / Xiaomi 17) abort dlopen otherwise.
"""
import struct
import sys

PAGE = 0x4000
PT_LOAD = 1


def unpack(fmt, data, off):
    return struct.unpack_from(fmt, data, off)


def pack_into(fmt, buf, off, *vals):
    struct.pack_into(fmt, buf, off, *vals)


def align_elf(path):
    raw = bytearray(open(path, "rb").read())
    if raw[:4] != b"\x7fELF":
        print("skip (not ELF)", path)
        return False
    little = raw[5] == 1
    elf64 = raw[4] == 2
    e = "<" if little else ">"
    if elf64:
        e_phoff = unpack(e + "Q", raw, 32)[0]
        e_shoff = unpack(e + "Q", raw, 40)[0]
        e_phentsize = unpack(e + "H", raw, 54)[0]
        e_phnum = unpack(e + "H", raw, 56)[0]
        e_shentsize = unpack(e + "H", raw, 58)[0]
        e_shnum = unpack(e + "H", raw, 60)[0]
        ph_off_field = 8  # p_offset in ELF64_Phdr after p_type,p_flags
        ph_align_field = 48
        ph_vaddr_field = 16
        off_fmt = e + "Q"
        hdr_off_pack = (e + "Q", 40)  # e_shoff
    else:
        e_phoff = unpack(e + "I", raw, 28)[0]
        e_shoff = unpack(e + "I", raw, 32)[0]
        e_phentsize = unpack(e + "H", raw, 42)[0]
        e_phnum = unpack(e + "H", raw, 44)[0]
        e_shentsize = unpack(e + "H", raw, 46)[0]
        e_shnum = unpack(e + "H", raw, 48)[0]
        ph_off_field = 4
        ph_align_field = 28
        ph_vaddr_field = 8
        off_fmt = e + "I"
        hdr_off_pack = (e + "I", 32)

    loads = []
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        p_type = unpack(e + "I", raw, o)[0]
        p_offset = unpack(off_fmt, raw, o + ph_off_field)[0]
        p_vaddr = unpack(off_fmt, raw, o + ph_vaddr_field)[0]
        p_align = unpack(off_fmt, raw, o + ph_align_field)[0]
        loads.append((i, p_type, p_offset, p_vaddr, p_align, o))

    # Insert padding from the end so earlier inserts don't shift later plans
    inserts = []  # (file_offset, pad)
    for i, p_type, p_offset, p_vaddr, p_align, o in loads:
        if p_type != PT_LOAD:
            continue
        want = max(p_align, PAGE)
        cur_mod = p_offset % PAGE
        need_mod = p_vaddr % PAGE
        pad = (need_mod - cur_mod) % PAGE
        if pad:
            inserts.append((p_offset, pad))
        if p_align < PAGE:
            pack_into(off_fmt, raw, o + ph_align_field, PAGE)

    if not inserts and all(a >= PAGE for _, t, _, _, a, _ in loads if t == PT_LOAD):
        print("already 16k", path)
        return False

    inserts.sort(key=lambda x: -x[0])
    for at, pad in inserts:
        raw[at:at] = b"\x00" * pad

        def bump(val):
            return val + pad if val >= at else val

        # program headers
        for i in range(e_phnum):
            po = e_phoff + i * e_phentsize
            p_offset = unpack(off_fmt, raw, po + ph_off_field)[0]
            # PHDR table itself is at start; only bump file offsets
            if p_offset >= at:
                pack_into(off_fmt, raw, po + ph_off_field, p_offset + pad)
        # section headers
        if e_shoff:
            new_shoff = bump(e_shoff)
            pack_into(hdr_off_pack[0], raw, hdr_off_pack[1], new_shoff)
            e_shoff = new_shoff
            for i in range(e_shnum):
                so = e_shoff + i * e_shentsize
                # sh_offset is at +16 ELF64, +16 ELF32 (after name,type,flags,addr) wait:
                # ELF32: name I, type I, flags I, addr I, offset I  -> offset at 16
                # ELF64: name I, type I, flags Q, addr Q, offset Q -> offset at 24
                sh_off_at = 24 if elf64 else 16
                sh_offset = unpack(off_fmt, raw, so + sh_off_at)[0]
                if sh_offset >= at:
                    pack_into(off_fmt, raw, so + sh_off_at, sh_offset + pad)
        e_phoff = bump(e_phoff) if False else e_phoff  # PHDR stays at start

    open(path, "wb").write(raw)
    print("aligned", path, "size", len(raw))
    return True


def verify(path):
    raw = open(path, "rb").read()
    little = raw[5] == 1
    elf64 = raw[4] == 2
    e = "<" if little else ">"
    if elf64:
        e_phoff = unpack(e + "Q", raw, 32)[0]
        e_phentsize = unpack(e + "H", raw, 54)[0]
        e_phnum = unpack(e + "H", raw, 56)[0]
        off_fmt = e + "Q"
        ph_off_field, ph_vaddr_field, ph_align_field = 8, 16, 48
    else:
        e_phoff = unpack(e + "I", raw, 28)[0]
        e_phentsize = unpack(e + "H", raw, 42)[0]
        e_phnum = unpack(e + "H", raw, 44)[0]
        off_fmt = e + "I"
        ph_off_field, ph_vaddr_field, ph_align_field = 4, 8, 28
    ok = True
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        p_type = unpack(e + "I", raw, o)[0]
        if p_type != PT_LOAD:
            continue
        p_offset = unpack(off_fmt, raw, o + ph_off_field)[0]
        p_vaddr = unpack(off_fmt, raw, o + ph_vaddr_field)[0]
        p_align = unpack(off_fmt, raw, o + ph_align_field)[0]
        good = p_align >= PAGE and (p_offset % PAGE) == (p_vaddr % PAGE)
        print("  LOAD", hex(p_offset), hex(p_vaddr), "align", hex(p_align), "OK" if good else "BAD")
        ok = ok and good
    return ok


if __name__ == "__main__":
    paths = sys.argv[1:]
    for p in paths:
        align_elf(p)
        if not verify(p):
            sys.exit("verify failed " + p)
    print("ALL_OK")
