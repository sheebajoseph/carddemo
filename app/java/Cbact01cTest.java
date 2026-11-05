/*
 * Dependency-free tests for Cbact01c. Run from app/java after compiling:
 *   java -cp build Cbact01cTest
 * The golden test regenerates ACCTFILE, runs Cbact01c in a temp directory and
 * compares every output byte-for-byte with app/java/output/.
 */

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Cbact01cTest {

    private static final Path GOLDEN_DIR = Paths.get("output");
    private static final String[] GOLDEN_FILES =
            {"ACCTFILE.dat", "OUTFILE.dat", "ARRYFILE.dat", "VBRCFILE.dat", "console.log"};

    private static int failures;

    public static void main(String[] args) throws Exception {
        comp3Encoding();
        zonedEncoding();
        roundTrips();
        layoutsAreContiguous();
        cobdatftConversions();
        goldenOutputs();
        missingInputAbends();
        truncatedInputAbends();

        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("All checks passed");
    }

    // ---- codec ----------------------------------------------------------

    static void comp3Encoding() {
        checkHex("COMP-3 +2525.00", "0000000252500c",
                Cbact01c.CobolCodec.toComp3(new BigDecimal("2525.00"), 12, 2));
        checkHex("COMP-3 -2500.00", "0000000250000d",
                Cbact01c.CobolCodec.toComp3(new BigDecimal("-2500.00"), 12, 2));
        checkHex("COMP-3 zero", "0000000000000c",
                Cbact01c.CobolCodec.toComp3(BigDecimal.ZERO, 12, 2));
        checkHex("COMP-3 odd digits", "12345c",
                Cbact01c.CobolCodec.toComp3(new BigDecimal("123.45"), 5, 2));
    }

    static void zonedEncoding() {
        checkHex("zoned -1025.00", "f0f0f0f0f0f0f1f0f2f5f0d0",
                Cbact01c.CobolCodec.toZoned(new BigDecimal("-1025.00"), 12, 2, true));
        checkHex("zoned +1940.00", "f0f0f0f0f0f0f1f9f4f0f0c0",
                Cbact01c.CobolCodec.toZoned(new BigDecimal("1940.00"), 12, 2, true));
        checkHex("zoned unsigned", "f0f0f0f0f0f0f0f0f0f0f1",
                Cbact01c.CobolCodec.toZoned(BigDecimal.ONE, 11, 0, false));
        checkHex("zoned -0 is positive", "f0f0c0",
                Cbact01c.CobolCodec.toZoned(new BigDecimal("-0.001"), 3, 2, true));
        checkHex("zoned truncates high-order digits", "f2f3c4",
                Cbact01c.CobolCodec.toZoned(new BigDecimal("12.34"), 3, 2, true));
    }

    static void roundTrips() {
        for (String v : new String[] {"0.00", "1940.00", "-275.75", "9999999999.99", "-0.01"}) {
            BigDecimal d = new BigDecimal(v);
            byte[] z = Cbact01c.CobolCodec.toZoned(d, 12, 2, true);
            byte[] p = Cbact01c.CobolCodec.toComp3(d, 12, 2);
            check("zoned round trip " + v, d.equals(Cbact01c.CobolCodec.fromZoned(z, 0, z.length, 2)));
            check("COMP-3 round trip " + v, d.equals(Cbact01c.CobolCodec.fromComp3(p, 0, p.length, 2)));
        }
        try {
            Cbact01c.CobolCodec.fromComp3(new byte[7], 0, 7, 2);
            check("COMP-3 X'00' sign rejected", false);
        } catch (NumberFormatException expected) {
            check("COMP-3 X'00' sign rejected", true);
        }
    }

    // ---- layouts --------------------------------------------------------

    static void layoutsAreContiguous() {
        checkLayout("ACCOUNT-RECORD", Cbact01c.AccountRecord.FIELDS, Cbact01c.AccountRecord.LENGTH);
        checkLayout("OUT-ACCT-REC", Cbact01c.OutAcctRec.FIELDS, Cbact01c.OutAcctRec.LENGTH);

        List<Cbact01c.Field> arr = new ArrayList<>();
        arr.add(Cbact01c.ArrArrayRec.ARR_ACCT_ID);
        for (int i = 1; i <= Cbact01c.ArrArrayRec.OCCURS; i++) {
            arr.add(Cbact01c.ArrArrayRec.currBal(i));
            arr.add(Cbact01c.ArrArrayRec.currCycDebit(i));
        }
        arr.add(Cbact01c.ArrArrayRec.ARR_FILLER);
        checkLayout("ARR-ARRAY-REC", arr, Cbact01c.ArrArrayRec.LENGTH);

        checkLayout("VBRC-REC1", List.of(Cbact01c.VbrcRec1.VB1_ACCT_ID,
                Cbact01c.VbrcRec1.VB1_ACCT_ACTIVE_STATUS), Cbact01c.VbrcRec1.LENGTH);
        checkLayout("VBRC-REC2", List.of(Cbact01c.VbrcRec2.VB2_ACCT_ID,
                Cbact01c.VbrcRec2.VB2_ACCT_CURR_BAL, Cbact01c.VbrcRec2.VB2_ACCT_CREDIT_LIMIT,
                Cbact01c.VbrcRec2.VB2_ACCT_REISSUE_YYYY), Cbact01c.VbrcRec2.LENGTH);
    }

    static void checkLayout(String name, List<Cbact01c.Field> fields, int length) {
        int expected = 0;
        for (Cbact01c.Field f : fields) {
            check(name + " " + f.name + " at offset " + expected, f.offset == expected);
            expected = f.end();
        }
        check(name + " length " + length, expected == length);
    }

    // ---- COBDATFT -------------------------------------------------------

    static void cobdatftConversions() {
        Cbact01c.Cobdatft c = new Cbact01c.Cobdatft();
        String out = c.convert("2025-05-20", Cbact01c.Cobdatft.IN_YYYY_MM_DD,
                Cbact01c.Cobdatft.OUT_YYYYMMDD);
        check("COBDATFT 2->2", out.startsWith("20250520\0\0"));

        out = c.convert("20240811", Cbact01c.Cobdatft.IN_YYYYMMDD, Cbact01c.Cobdatft.OUT_YYYY_MM_DD);
        check("COBDATFT 1->1", out.startsWith("2024-08-11"));

        Cbact01c.Cobdatft bad = new Cbact01c.Cobdatft();
        bad.convert("2024-08-11", Cbact01c.Cobdatft.IN_YYYYMMDD, Cbact01c.Cobdatft.OUT_YYYY_MM_DD);
        check("COBDATFT rejects dashed input for type 1", bad.errorMsg().startsWith("INVALID INPUT"));
        Cbact01c.Cobdatft bad2 = new Cbact01c.Cobdatft();
        bad2.convert("2024-08-11", '3', '2');
        check("COBDATFT rejects unknown type", bad2.errorMsg().startsWith("INVALID INPUT"));
    }

    // ---- end to end -----------------------------------------------------

    static void goldenOutputs() throws Exception {
        Path dir = Files.createTempDirectory("cbact01c");
        check("MakeTestData exit 0", runJava(dir, "MakeTestData") == 0);
        check("Cbact01c exit 0", runJava(dir, "Cbact01c", "ACCTFILE.dat") == 0);
        for (String f : GOLDEN_FILES) {
            byte[] actual = Files.readAllBytes(dir.resolve(f));
            byte[] expected = Files.readAllBytes(GOLDEN_DIR.resolve(f));
            check("golden " + f, Arrays.equals(actual, expected));
        }
    }

    static void missingInputAbends() throws Exception {
        Path dir = Files.createTempDirectory("cbact01c");
        check("missing ACCTFILE exits 12", runJava(dir, "Cbact01c", "missing.dat") == 12);
        check("missing ACCTFILE status 35",
                Files.readString(dir.resolve("console.log")).contains("FILE STATUS IS: NNNN0035"));
    }

    static void truncatedInputAbends() throws Exception {
        Path dir = Files.createTempDirectory("cbact01c");
        byte[] acct = Files.readAllBytes(GOLDEN_DIR.resolve("ACCTFILE.dat"));
        Files.write(dir.resolve("short.dat"), Arrays.copyOf(acct, 450));
        check("short record exits 12", runJava(dir, "Cbact01c", "short.dat") == 12);
        check("short record status 04",
                Files.readString(dir.resolve("console.log")).contains("FILE STATUS IS: NNNN0004"));
    }

    /** Runs a class from this classpath in {@code dir}, stdout+stderr to console.log. */
    static int runJava(Path dir, String mainClass, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(new File(System.getProperty("java.class.path")).getAbsolutePath());
        cmd.add(mainClass);
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("console.log").toFile())
                .start();
        return p.waitFor();
    }

    // ---- assertions -----------------------------------------------------

    static void checkHex(String name, String expectedHex, byte[] actual) {
        StringBuilder sb = new StringBuilder();
        for (byte b : actual) {
            sb.append(String.format("%02x", b));
        }
        check(name + " (got " + sb + ")", expectedHex.equals(sb.toString()));
    }

    static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}
