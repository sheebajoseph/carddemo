#!/usr/bin/env python3
"""Field-by-field comparison of the Java port's outputs (EBCDIC, ../output)
with GnuCOBOL's outputs from the unmodified CBACT01C.cbl (ASCII, ./output).

Java text/zoned bytes are converted EBCDIC->ASCII (cp037) before comparing;
COMP-3 bytes are compared as-is. Differences that come from behaviour COBOL
leaves undefined, or from GnuCOBOL vs IBM conventions, are reported as KNOWN;
anything else is a MISMATCH and makes the script exit 1.
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
JAVA = HERE.parent / "output"
GNU = HERE / "output"

ACCT_LEN = 300
OUT_FIELDS = [("OUT-ACCT-ID", 0, 11, "text"), ("OUT-ACCT-ACTIVE-STATUS", 11, 1, "text"),
              ("OUT-ACCT-CURR-BAL", 12, 12, "zoned"), ("OUT-ACCT-CREDIT-LIMIT", 24, 12, "zoned"),
              ("OUT-ACCT-CASH-CREDIT-LIMIT", 36, 12, "zoned"), ("OUT-ACCT-OPEN-DATE", 48, 10, "text"),
              ("OUT-ACCT-EXPIRAION-DATE", 58, 10, "text"), ("OUT-ACCT-REISSUE-DATE", 68, 10, "text"),
              ("OUT-ACCT-CURR-CYC-CREDIT", 78, 12, "zoned"), ("OUT-ACCT-CURR-CYC-DEBIT", 90, 7, "comp3"),
              ("OUT-ACCT-GROUP-ID", 97, 10, "text")]
ARR_FIELDS = [("ARR-ACCT-ID", 0, 11, "text")]
for i in range(5):
    off = 11 + i * 19
    ARR_FIELDS += [(f"ARR-ACCT-CURR-BAL({i + 1})", off, 12, "zoned"),
                   (f"ARR-ACCT-CURR-CYC-DEBIT({i + 1})", off + 12, 7, "comp3")]
ARR_FIELDS.append(("ARR-FILLER", 106, 4, "text"))
VB_FIELDS = [
    [("VB1-ACCT-ID", 0, 11, "text"), ("VB1-ACCT-ACTIVE-STATUS", 11, 1, "text")],
    [("VB2-ACCT-ID", 0, 11, "text"), ("VB2-ACCT-CURR-BAL", 11, 12, "zoned"),
     ("VB2-ACCT-CREDIT-LIMIT", 23, 12, "zoned"), ("VB2-ACCT-REISSUE-YYYY", 35, 4, "text")],
]

counts = {"match": 0, "known": 0, "mismatch": 0}


def to_ascii(b, kind):
    return b if kind == "comp3" else b.decode("cp037").encode("latin-1")


def report(status, where, detail=""):
    counts[status] += 1
    if status != "match":
        print(f"  {status.upper():8} {where} {detail}")


def compare_field(where, java, gnu, kind, known=None):
    j = to_ascii(java, kind)
    if j == gnu:
        report("match", where)
    elif kind == "zoned" and numeric_value(j.decode("latin-1")) == numeric_value(gnu.decode("latin-1")):
        report("known", where, f"java={java.hex()} gnu={gnu.hex()}  "
               "(same value; +0 written with sign zone C by Java, unsigned/F zone by GnuCOBOL INITIALIZE)")
    elif known:
        report("known", where, f"java={java.hex()} gnu={gnu.hex()}  ({known})")
    else:
        report("mismatch", where, f"java={java.hex()} gnu={gnu.hex()}")


def split_fixed(data, length):
    return [data[i:i + length] for i in range(0, len(data), length)]


def input_debit_is_zero(acct_rec):
    return all(ch in "0{}" for ch in acct_rec[90:102].decode("cp037"))


def compare_outfile(accts):
    print("OUTFILE")
    java, gnu = split_fixed((JAVA / "OUTFILE.dat").read_bytes(), 107), split_fixed((GNU / "OUTFILE.dat").read_bytes(), 107)
    if len(java) != len(gnu):
        report("mismatch", "OUTFILE record count", f"java={len(java)} gnu={len(gnu)}")
    for n, (jr, gr, acct) in enumerate(zip(java, gnu, accts), 1):
        for name, off, ln, kind in OUT_FIELDS:
            where = f"rec {n} {name}"
            if name == "OUT-ACCT-REISSUE-DATE":
                compare_field(where + " (YYYYMMDD)", jr[off:off + 8], gr[off:off + 8], kind)
                compare_field(where + " (last 2 bytes)", jr[off + 8:off + 10], gr[off + 8:off + 10], kind,
                              "uninitialised WORKING-STORAGE: Java X'00', GnuCOBOL spaces")
            elif name == "OUT-ACCT-CURR-CYC-DEBIT" and not input_debit_is_zero(acct):
                compare_field(where, jr[off:off + ln], gr[off:off + ln], kind,
                              "COBOL never moves the debit when it is non-zero; record area contents are undefined")
            else:
                compare_field(where, jr[off:off + ln], gr[off:off + ln], kind)


def compare_arryfile():
    print("ARRYFILE")
    java, gnu = split_fixed((JAVA / "ARRYFILE.dat").read_bytes(), 110), split_fixed((GNU / "ARRYFILE.dat").read_bytes(), 110)
    if len(java) != len(gnu):
        report("mismatch", "ARRYFILE record count", f"java={len(java)} gnu={len(gnu)}")
    for n, (jr, gr) in enumerate(zip(java, gnu), 1):
        for name, off, ln, kind in ARR_FIELDS:
            compare_field(f"rec {n} {name}", jr[off:off + ln], gr[off:off + ln], kind)


def parse_vb(data, rdw_includes_prefix):
    recs, i = [], 0
    while i < len(data):
        ln = int.from_bytes(data[i:i + 2], "big")
        data_len = ln - 4 if rdw_includes_prefix else ln
        recs.append((data[i:i + 4], data[i + 4:i + 4 + data_len]))
        i += 4 + data_len
    return recs


def compare_vbrcfile():
    print("VBRCFILE")
    java = parse_vb((JAVA / "VBRCFILE.dat").read_bytes(), True)
    gnu = parse_vb((GNU / "VBRCFILE.dat").read_bytes(), False)
    if len(java) != len(gnu):
        report("mismatch", "VBRCFILE record count", f"java={len(java)} gnu={len(gnu)}")
    for n, ((jrdw, jd), (grdw, gd)) in enumerate(zip(java, gnu), 1):
        where = f"rec {n} RDW"
        if int.from_bytes(jrdw[:2], "big") - 4 == int.from_bytes(grdw[:2], "big") == len(gd):
            report("known", where, f"java={jrdw.hex()} gnu={grdw.hex()}  "
                   "(z/OS RDW length includes the 4-byte prefix; GnuCOBOL varseq_format 0 does not)")
        else:
            report("mismatch", where, f"java={jrdw.hex()} gnu={grdw.hex()}")
        for name, off, ln, kind in VB_FIELDS[(n - 1) % 2]:
            compare_field(f"rec {n} {name}", jd[off:off + ln], gd[off:off + ln], kind)


def numeric_value(text):
    """Java shows IBM overpunch (00000019400{); GnuCOBOL 3.1 shows digits+sign (000000194000+)."""
    m = re.fullmatch(r"(\d+)([+-])", text)
    if m:
        return int(m.group(1)) * (-1 if m.group(2) == "-" else 1)
    if re.fullmatch(r"\d+", text):
        return int(text)
    m = re.fullmatch(r"(\d*)([{A-I}J-R])", text)
    if m:
        last = m.group(2)
        pos = "{ABCDEFGHI"
        neg = "}JKLMNOPQR"
        digit, sign = (pos.index(last), 1) if last in pos else (neg.index(last), -1)
        return int(m.group(1) + str(digit)) * sign
    return None


def compare_console():
    print("console.log")
    java = (JAVA / "console.log").read_text(encoding="utf-8").splitlines()
    gnu = (GNU / "console.log").read_text(encoding="latin-1").splitlines()
    if len(java) != len(gnu):
        report("mismatch", "console line count", f"java={len(java)} gnu={len(gnu)}")
    for n, (j, g) in enumerate(zip(java, gnu), 1):
        if j == g:
            report("match", f"line {n}")
            continue
        jl, _, jv = j.partition(":")
        gl, _, gv = g.partition(":")
        jn, gn = numeric_value(jv), numeric_value(gv)
        if jl == gl and jn is not None and jn == gn:
            report("known", f"line {n} {jl.strip()}", f"java='{jv}' gnu='{gv}'  "
                   "(same value; IBM DISPLAY shows overpunch, GnuCOBOL 3.1 shows trailing sign)")
        else:
            report("mismatch", f"line {n}", f"java='{j}' gnu='{g}'")


def main():
    accts = split_fixed((JAVA / "ACCTFILE.dat").read_bytes(), ACCT_LEN)
    compare_outfile(accts)
    compare_arryfile()
    compare_vbrcfile()
    compare_console()
    print(f"\nSUMMARY: {counts['match']} identical, {counts['known']} known differences, "
          f"{counts['mismatch']} mismatches")
    return 1 if counts["mismatch"] else 0


if __name__ == "__main__":
    sys.exit(main())
