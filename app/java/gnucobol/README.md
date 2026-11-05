# Running the original CBACT01C under GnuCOBOL

`run.sh` compiles the **unmodified** `app/cbl/CBACT01C.cbl` with GnuCOBOL 3.x, runs it on the same 3 account records the Java port uses (`../output/ACCTFILE.dat`), and compares the results field by field with the Java output in `../output/`.

```bash
sudo apt-get install -y gnucobol3   # GnuCOBOL 3.1.2 was used
./run.sh                            # exits 1 if any field differs unexpectedly
```

## Files

- `COBDATFT.cbl`: COBOL stand-in for the assembler routine `app/asm/COBDATFT.asm` (same `CODATECN` contract and branches).
- `CEE3ABD.cbl`: stand-in for the Language Environment abend service; ends the run with return code 12.
- `LOADACCT.cbl`: loads the fixed 300-byte records into an indexed file, like the IDCAMS REPRO in `app/jcl/ACCTFILE.jcl`.
- `compare.py`: field-by-field comparison. Java text and zoned bytes are converted from EBCDIC (cp037) before comparing; COMP-3 bytes are compared as they are.
- `output/`: GnuCOBOL's `OUTFILE.dat`, `ARRYFILE.dat`, `VBRCFILE.dat`, their hex dumps, `console.log`, and `comparison.txt`.

GnuCOBOL runs in ASCII, so `run.sh` converts ACCTFILE from EBCDIC first. It compiles with `-std=ibm -fsign=EBCDIC` so that signed zoned fields use the same overpunch characters (`{`, `A`–`R`) as on z/OS. Files are assigned through `DD_<name>` environment variables, the same way JCL DD statements assign them.

## Result

113 fields and console lines are identical, there are 30 known differences, and nothing differs unexpectedly. COMP-3 debits match byte for byte. `OUT-ACCT-CURR-CYC-DEBIT` also matches, including record 3 carrying over record 2's 2525.00.

The known differences come from GnuCOBOL vs z/OS conventions or from storage that COBOL leaves undefined:

| Where | Java (z/OS style) | GnuCOBOL | Why |
|---|---|---|---|
| VBRCFILE RDW | `0010 0000` / `002B 0000` | `000C 0000` / `0027 0000` | A z/OS RDW length counts the 4-byte prefix; GnuCOBOL's default `COB_VARSEQ_FORMAT=0` does not. The record data is identical. |
| OUTFILE reissue date, last 2 bytes | `X'00 00'` | spaces | COBDATFT fills only 8 of the 10 bytes, so these 2 bytes are uninitialised storage. |
| ARRYFILE `ARR-ACCT-CURR-BAL(4)` and `(5)` | `...F0 C0` (+0, sign C) | ASCII `0` (+0 with no sign zone) | Both are zero. GnuCOBOL's `INITIALIZE` writes plain zero digits. |
| console, signed numbers | `00000019400{` | `000000194000+` | Same values: IBM `DISPLAY` folds the sign into the last digit, while GnuCOBOL 3.1 prints a trailing sign. |

GnuCOBOL also warns `implicit CLOSE` for OUTFILE, ARRYFILE and VBRCFILE because CBACT01C only closes ACCTFILE.
