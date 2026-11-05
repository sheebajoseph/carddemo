#!/usr/bin/env bash
# Runs the unmodified app/cbl/CBACT01C.cbl under GnuCOBOL 3.x on the same
# ACCTFILE as the Java port and compares the results with ../output/.
# Requires: cobc (GnuCOBOL 3.x with an indexed-file handler), python3, xxd.
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd)
APP=$(cd ../.. && pwd)
BUILD="$HERE/build"
OUT="$HERE/output"

rm -rf "$BUILD" && mkdir -p "$BUILD" "$OUT"
rm -f "$OUT"/*

# -std=ibm + -fsign=EBCDIC: IBM dialect and EBCDIC-style sign overpunch, so the
# ASCII data holds the same characters as the EBCDIC data decoded with cp037.
COBFLAGS=(-std=ibm -fsign=EBCDIC)
cobc -x "${COBFLAGS[@]}" -o "$BUILD/loadacct" LOADACCT.cbl
cobc -x "${COBFLAGS[@]}" -fstatic-call -I "$APP/cpy" -o "$BUILD/cbact01c" \
     "$APP/cbl/CBACT01C.cbl" COBDATFT.cbl CEE3ABD.cbl

# GnuCOBOL runs in ASCII: convert the Java port's EBCDIC ACCTFILE, then load it
# into an indexed file (stand-in for the VSAM KSDS).
python3 -c "import sys; d=open(sys.argv[1],'rb').read(); open(sys.argv[2],'wb').write(d.decode('cp037').encode('latin-1'))" \
        ../output/ACCTFILE.dat "$BUILD/ACCTSEQ.dat"
DD_ACCTSEQ="$BUILD/ACCTSEQ.dat" DD_ACCTFILE="$BUILD/ACCTFILE.idx" "$BUILD/loadacct"

DD_ACCTFILE="$BUILD/ACCTFILE.idx" DD_OUTFILE="$OUT/OUTFILE.dat" \
DD_ARRYFILE="$OUT/ARRYFILE.dat" DD_VBRCFILE="$OUT/VBRCFILE.dat" \
  "$BUILD/cbact01c" > "$OUT/console.log" 2> "$BUILD/stderr.log"

for f in OUTFILE ARRYFILE VBRCFILE; do
  xxd "$OUT/$f.dat" > "$OUT/$f.dat.hex"
done

python3 compare.py | tee "$OUT/comparison.txt"
