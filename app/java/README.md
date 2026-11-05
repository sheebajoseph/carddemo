# CBACT01C Java port

A Java port of the batch program `app/cbl/CBACT01C.cbl` that produces the same bytes as the COBOL program.

| File | Purpose |
|------|---------|
| `Cbact01c.java` | The program. |
| `MakeTestData.java` | Writes `ACCTFILE.dat`: 3 EBCDIC records laid out per `CVACT01Y` (a normal account, one with zero cycle debit, one with a negative balance). |
| `Cbact01cTest.java` | Tests for the codecs, the record layouts and COBDATFT, plus a golden-file run against `output/` and the abend paths (exit code 12). |
| `run.sh` | Builds everything, rebuilds `output/` and runs the tests. |

## Build and run (JDK 11+)

```
./run.sh
```

Run it by hand:

```
javac --release 11 -d build Cbact01c.java MakeTestData.java Cbact01cTest.java
cd output && java -cp ../build MakeTestData && java -cp ../build Cbact01c ACCTFILE.dat
```

`Cbact01c [ACCTFILE [OUTFILE ARRYFILE VBRCFILE]]` defaults to `ACCTFILE.dat`, `OUTFILE.dat`, `ARRYFILE.dat` and `VBRCFILE.dat` in the current directory.

## Code layout (`Cbact01c.java`)

- `CobolCodec`: converts EBCDIC text (IBM037), zoned decimal and COMP-3.
- `Field`: one elementary item (offset, PIC, USAGE) with `number`/`setNumber`/`text`/`setText`. `move(src, srcRec, dst, dstRec)` copies one field to another the way a COBOL `MOVE` does.
- Record layouts are declared once as `Field` constants:
  - `AccountRecord` (CVACT01Y, 300 bytes)
  - `OutAcctRec` (107)
  - `ArrArrayRec` (110; `currBal(i)`/`currCycDebit(i)` for the OCCURS 5 entries)
  - `VbrcRec1` (12)
  - `VbrcRec2` (39)
- `Cobdatft`: the COBDATFT assembler routine (`app/asm/COBDATFT.asm`), following the `CODATECN` copybook.
- `AccountFile` / `OutputFile`: file I/O with COBOL FILE STATUS values (`FileStatus`). `OutputFile.writeVariable` adds the 4-byte RDW.
- The program methods are named after the COBOL paragraphs, e.g. `populAcctRecord` is `1300-POPUL-ACCT-RECORD`.

If you change the program's output on purpose, run `./run.sh` again and commit the updated files in `output/`.

## COBOL behaviour kept as-is

- `OUT-ACCT-CURR-CYC-DEBIT` is only set when the input debit is zero (it gets 2525.00). Otherwise it keeps whatever was already in the record area. The port keeps that record area between records and starts it as `X'00'`.
- `OUT-ACCT-REISSUE-DATE` takes 10 bytes of the COBDATFT output, but COBDATFT only fills 8 of them. The last 2 bytes are uninitialized storage, written as `X'00'` here.
- `DISPLAY` prints signed zoned fields as their raw characters, with the sign folded into the last digit (e.g. `00000002757N` = -275.75).
