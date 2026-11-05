/*
 * Generates ACCTFILE.dat: 3 fixed 300-byte EBCDIC records laid out per
 * copybook CVACT01Y, in ascending ACCT-ID order (as unloaded from the KSDS).
 *   1. normal account
 *   2. ACCT-CURR-CYC-DEBIT = 0 (exercises the 2525.00 substitution)
 *   3. negative ACCT-CURR-BAL
 *
 * Usage: java MakeTestData [output-file]
 */

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;

public class MakeTestData {

    public static void main(String[] args) throws IOException {
        String path = args.length > 0 ? args[0] : "ACCTFILE.dat";
        try (OutputStream os = new FileOutputStream(path)) {
            os.write(record(1L, "Y", "1940.00", "20200.00", "10200.00",
                    "2014-11-20", "2025-05-20", "2025-05-20", "125.50", "310.25",
                    "10001", "A000000000"));
            os.write(record(2L, "Y", "1580.00", "61300.00", "54480.00",
                    "2013-06-19", "2024-08-11", "2024-08-11", "0.00", "0.00",
                    "20002", "B000000000"));
            os.write(record(3L, "N", "-275.75", "5000.00", "1500.00",
                    "2019-03-02", "2027-03-31", "2023-12-01", "40.00", "120.00",
                    "30003", "C000000000"));
        }
        System.out.println("Wrote 3 records (900 bytes) to " + path);
    }

    static byte[] record(long id, String status, String bal, String limit, String cashLimit,
                         String openDate, String expDate, String reissueDate,
                         String cycCredit, String cycDebit, String zip, String groupId) {
        byte[] r = new byte[Cbact01c.ACCT_REC_LEN];
        put(r, 0, Cbact01c.CobolCodec.toZoned(BigDecimal.valueOf(id), 11, 0, false));
        put(r, 11, Cbact01c.CobolCodec.ebcdic(status, 1));
        put(r, 12, money(bal));
        put(r, 24, money(limit));
        put(r, 36, money(cashLimit));
        put(r, 48, Cbact01c.CobolCodec.ebcdic(openDate, 10));
        put(r, 58, Cbact01c.CobolCodec.ebcdic(expDate, 10));
        put(r, 68, Cbact01c.CobolCodec.ebcdic(reissueDate, 10));
        put(r, 78, money(cycCredit));
        put(r, 90, money(cycDebit));
        put(r, 102, Cbact01c.CobolCodec.ebcdic(zip, 10));
        put(r, 112, Cbact01c.CobolCodec.ebcdic(groupId, 10));
        put(r, 122, Cbact01c.CobolCodec.ebcdic("", 178));
        return r;
    }

    private static byte[] money(String v) {
        return Cbact01c.CobolCodec.toZoned(new BigDecimal(v), 12, 2, true);
    }

    private static void put(byte[] dst, int off, byte[] src) {
        System.arraycopy(src, 0, dst, off, src.length);
    }
}
