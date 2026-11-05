#!/usr/bin/env bash
# Build the CBACT01C Java port, regenerate the sample data and outputs in
# output/, then run the tests against them. Requires JDK 11+.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build
javac --release 11 -Xlint:all -Werror -d build Cbact01c.java MakeTestData.java Cbact01cTest.java

(
  cd output
  java -cp ../build MakeTestData
  java -cp ../build Cbact01c ACCTFILE.dat > console.log 2>&1
  for f in ACCTFILE OUTFILE ARRYFILE VBRCFILE; do
    xxd "$f.dat" > "$f.dat.hex"
  done
)

java -cp build Cbact01cTest
