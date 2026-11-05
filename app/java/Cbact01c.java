/*
 * Java port of batch COBOL program CBACT01C (app/cbl/CBACT01C.cbl).
 *
 * Reads the account master file ACCTFILE (fixed 300-byte EBCDIC records laid
 * out per copybook CVACT01Y) sequentially and writes:
 *   OUTFILE  - 107-byte flattened account record (zoned decimal + COMP-3 debit)
 *   ARRYFILE - 110-byte record with OCCURS 5 (zoned balance, COMP-3 debit)
 *   VBRCFILE - two variable-length records per account (12 and 39 bytes),
 *              each prefixed by a 4-byte RDW.
 *
 * Usage: java Cbact01c [ACCTFILE [OUTFILE ARRYFILE VBRCFILE]]
 *
 * Behaviour intentionally preserved from the COBOL source:
 *   - OUT-ACCT-CURR-CYC-DEBIT is only moved when ACCT-CURR-CYC-DEBIT is zero
 *     (2525.00 is substituted). Otherwise the field keeps whatever the OUTFILE
 *     record area held before; this port keeps a persistent record area that
 *     starts as low-values (X'00').
 *   - OUT-ACCT-REISSUE-DATE receives the first 10 bytes of the 20-byte
 *     CODATECN-0UT-DATE; COBDATFT only sets the first 8, so the last 2 are the
 *     uninitialised WORKING-STORAGE contents (low-values in this port).
 *   - DISPLAY of signed zoned fields shows the raw EBCDIC characters (sign
 *     overpunch on the last digit), as IBM Enterprise COBOL does.
 */

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.Arrays;

public class Cbact01c {

    static final int ACCT_REC_LEN = 300;
    static final int OUT_REC_LEN = 107;
    static final int ARR_REC_LEN = 110;
    static final int VB1_LEN = 12;
    static final int VB2_LEN = 39;

    static final byte LOW_VALUE = 0x00;
    static final byte EBCDIC_SPACE = 0x40;

    /** COBOL data-representation helpers (EBCDIC / IBM037). */
    static final class CobolCodec {
        static final Charset EBCDIC = Charset.forName("IBM037");

        private CobolCodec() {
        }

        static byte[] ebcdic(String s) {
            return s.getBytes(EBCDIC);
        }

        /** Alphanumeric PIC X(len): left-justified, space padded / truncated. */
        static byte[] ebcdic(String s, int len) {
            byte[] out = new byte[len];
            Arrays.fill(out, EBCDIC_SPACE);
            byte[] src = ebcdic(s);
            System.arraycopy(src, 0, out, 0, Math.min(len, src.length));
            return out;
        }

        static String fromEbcdic(byte[] b, int off, int len) {
            return new String(b, off, len, EBCDIC);
        }

        /** Unscaled absolute digits of v for PIC 9(digits)V9(scale), COBOL MOVE truncation. */
        private static String digitsOf(BigDecimal v, int digits, int scale) {
            BigInteger unscaled = v.setScale(scale, RoundingMode.DOWN).unscaledValue().abs();
            unscaled = unscaled.mod(BigInteger.TEN.pow(digits));
            String s = unscaled.toString();
            StringBuilder sb = new StringBuilder(digits);
            for (int i = s.length(); i < digits; i++) {
                sb.append('0');
            }
            return sb.append(s).toString();
        }

        /** Zoned decimal (USAGE DISPLAY). Signed: last zone C (+) / D (-); unsigned: F. */
        static byte[] toZoned(BigDecimal v, int digits, int scale, boolean signed) {
            String d = digitsOf(v, digits, scale);
            byte[] out = new byte[digits];
            for (int i = 0; i < digits; i++) {
                out[i] = (byte) (0xF0 | (d.charAt(i) - '0'));
            }
            if (signed) {
                boolean negative = v.signum() < 0 && !d.chars().allMatch(c -> c == '0');
                int zone = negative ? 0xD0 : 0xC0;
                out[digits - 1] = (byte) (zone | (d.charAt(digits - 1) - '0'));
            }
            return out;
        }

        static BigDecimal fromZoned(byte[] b, int off, int len, int scale) {
            BigInteger value = BigInteger.ZERO;
            for (int i = 0; i < len; i++) {
                int digit = b[off + i] & 0x0F;
                if (digit > 9) {
                    throw new NumberFormatException("Invalid zoned digit at offset " + (off + i));
                }
                value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf(digit));
            }
            int zone = (b[off + len - 1] & 0xF0) >>> 4;
            if (zone == 0xD || zone == 0xB) {
                value = value.negate();
            }
            return new BigDecimal(value, scale);
        }

        /** Packed decimal (COMP-3): (digits+2)/2 bytes, sign nibble C (+) / D (-). */
        static byte[] toComp3(BigDecimal v, int digits, int scale) {
            String d = digitsOf(v, digits, scale);
            if (digits % 2 == 0) {
                d = "0" + d;
            }
            boolean negative = v.signum() < 0 && !d.chars().allMatch(c -> c == '0');
            int sign = negative ? 0xD : 0xC;
            byte[] out = new byte[(digits + 2) / 2];
            for (int i = 0; i < out.length; i++) {
                int hi = d.charAt(2 * i) - '0';
                int lo = (2 * i + 1 < d.length()) ? d.charAt(2 * i + 1) - '0' : sign;
                out[i] = (byte) ((hi << 4) | lo);
            }
            return out;
        }

        static BigDecimal fromComp3(byte[] b, int off, int len, int scale) {
            BigInteger value = BigInteger.ZERO;
            for (int i = 0; i < len; i++) {
                int hi = (b[off + i] & 0xF0) >>> 4;
                int lo = b[off + i] & 0x0F;
                if (hi > 9 || (i < len - 1 && lo > 9)) {
                    throw new NumberFormatException("Invalid packed digit at offset " + (off + i));
                }
                value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf(hi));
                if (i < len - 1) {
                    value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf(lo));
                } else if (lo < 0xA) {
                    throw new NumberFormatException("Invalid packed sign at offset " + (off + i));
                } else if (lo == 0xD || lo == 0xB) {
                    value = value.negate();
                }
            }
            return new BigDecimal(value, scale);
        }
    }

    /** ACCOUNT-RECORD per copybook CVACT01Y (RECLN 300). */
    static final class AccountRecord {
        static final int ACCT_ID = 0;                 // 9(11)
        static final int ACCT_ACTIVE_STATUS = 11;     // X(01)
        static final int ACCT_CURR_BAL = 12;          // S9(10)V99
        static final int ACCT_CREDIT_LIMIT = 24;      // S9(10)V99
        static final int ACCT_CASH_CREDIT_LIMIT = 36; // S9(10)V99
        static final int ACCT_OPEN_DATE = 48;         // X(10)
        static final int ACCT_EXPIRAION_DATE = 58;    // X(10)
        static final int ACCT_REISSUE_DATE = 68;      // X(10)
        static final int ACCT_CURR_CYC_CREDIT = 78;   // S9(10)V99
        static final int ACCT_CURR_CYC_DEBIT = 90;    // S9(10)V99
        static final int ACCT_ADDR_ZIP = 102;         // X(10)
        static final int ACCT_GROUP_ID = 112;         // X(10)
        static final int FILLER = 122;                // X(178)

        static final int ZONED_LEN = 12;
        static final int DATE_LEN = 10;

        final byte[] raw;

        AccountRecord(byte[] record) {
            if (record.length != ACCT_REC_LEN) {
                throw new IllegalArgumentException("ACCOUNT-RECORD must be " + ACCT_REC_LEN + " bytes");
            }
            this.raw = record.clone();
        }

        String text(int off, int len) {
            return CobolCodec.fromEbcdic(raw, off, len);
        }

        byte[] bytes(int off, int len) {
            return Arrays.copyOfRange(raw, off, off + len);
        }

        private BigDecimal money(int off) {
            return CobolCodec.fromZoned(raw, off, ZONED_LEN, 2);
        }

        BigDecimal acctId() {
            return CobolCodec.fromZoned(raw, ACCT_ID, 11, 0);
        }

        String activeStatus() {
            return text(ACCT_ACTIVE_STATUS, 1);
        }

        BigDecimal currBal() {
            return money(ACCT_CURR_BAL);
        }

        BigDecimal creditLimit() {
            return money(ACCT_CREDIT_LIMIT);
        }

        BigDecimal cashCreditLimit() {
            return money(ACCT_CASH_CREDIT_LIMIT);
        }

        String openDate() {
            return text(ACCT_OPEN_DATE, DATE_LEN);
        }

        String expirationDate() {
            return text(ACCT_EXPIRAION_DATE, DATE_LEN);
        }

        String reissueDate() {
            return text(ACCT_REISSUE_DATE, DATE_LEN);
        }

        BigDecimal currCycCredit() {
            return money(ACCT_CURR_CYC_CREDIT);
        }

        BigDecimal currCycDebit() {
            return money(ACCT_CURR_CYC_DEBIT);
        }

        String addrZip() {
            return text(ACCT_ADDR_ZIP, 10);
        }

        String groupId() {
            return text(ACCT_GROUP_ID, 10);
        }
    }

    // ---- File status handling -------------------------------------------

    static final class CobolFileException extends Exception {
        private static final long serialVersionUID = 1L;

        final String status;

        CobolFileException(String status, Throwable cause) {
            super("FILE STATUS " + status, cause);
            this.status = status;
        }
    }

    private InputStream acctFile;
    private OutputStream outFile;
    private OutputStream arryFile;
    private OutputStream vbrcFile;

    private String acctFileStatus = "00";
    private String outFileStatus = "00";
    private String arryFileStatus = "00";
    private String vbrcFileStatus = "00";

    private boolean endOfFile = false;
    private int applResult;

    // FD record areas / WORKING-STORAGE that persist across records.
    private final byte[] outAcctRec = new byte[OUT_REC_LEN];
    private AccountRecord accountRecord;

    // CODATECN-REC output area (COBDATFT only ever writes part of it).
    private static final char[] codatecnOutDate = new char[20];
    private static final char[] codatecnErrorMsg = new char[38];

    static {
        Arrays.fill(codatecnOutDate, (char) LOW_VALUE);
        Arrays.fill(codatecnErrorMsg, (char) LOW_VALUE);
    }

    Cbact01c() {
        Arrays.fill(outAcctRec, LOW_VALUE);
    }

    public static void main(String[] args) {
        String acct = args.length > 0 ? args[0] : "ACCTFILE.dat";
        String out = args.length > 1 ? args[1] : "OUTFILE.dat";
        String arry = args.length > 2 ? args[2] : "ARRYFILE.dat";
        String vbrc = args.length > 3 ? args[3] : "VBRCFILE.dat";
        new Cbact01c().run(acct, out, arry, vbrc);
    }

    void run(String acct, String out, String arry, String vbrc) {
        display("START OF EXECUTION OF PROGRAM CBACT01C");
        acctFileOpen(acct);
        outFileOpen(out);
        arrFileOpen(arry);
        vbrFileOpen(vbrc);

        while (!endOfFile) {
            acctFileGetNext();
            if (!endOfFile) {
                display(accountRecord.text(0, ACCT_REC_LEN));
            }
        }

        acctFileClose();
        closeOutputsAtGoback();

        display("END OF EXECUTION OF PROGRAM CBACT01C");
    }

    // ---- 1000-ACCTFILE-GET-NEXT -----------------------------------------

    void acctFileGetNext() {
        byte[] buf = new byte[ACCT_REC_LEN];
        try {
            int n = acctFile.readNBytes(buf, 0, ACCT_REC_LEN);
            if (n == 0) {
                acctFileStatus = "10";
            } else if (n < ACCT_REC_LEN) {
                acctFileStatus = "04";
            } else {
                acctFileStatus = "00";
            }
        } catch (IOException e) {
            acctFileStatus = "30";
        }

        if ("00".equals(acctFileStatus)) {
            applResult = 0;
            accountRecord = new AccountRecord(buf);
            try {
                displayAcctRecord();
                writeAcctRecord(populAcctRecord());
                writeArryRecord(populArrayRecord());
                byte[][] vb = populVbrcRecord();
                writeVbrcRecord(vb[0]);
                writeVbrcRecord(vb[1]);
            } catch (NumberFormatException e) {
                display("DATA EXCEPTION: " + e.getMessage());
                abend();
            }
        } else if ("10".equals(acctFileStatus)) {
            applResult = 16;
        } else {
            applResult = 12;
        }

        if (applResult == 16) {
            endOfFile = true;
        } else if (applResult != 0) {
            display("ERROR READING ACCOUNT FILE");
            displayIoStatus(acctFileStatus);
            abend();
        }
    }

    // ---- 1100-DISPLAY-ACCT-RECORD ---------------------------------------

    void displayAcctRecord() {
        AccountRecord r = accountRecord;
        display("ACCT-ID                 :" + r.text(AccountRecord.ACCT_ID, 11));
        display("ACCT-ACTIVE-STATUS      :" + r.text(AccountRecord.ACCT_ACTIVE_STATUS, 1));
        display("ACCT-CURR-BAL           :" + r.text(AccountRecord.ACCT_CURR_BAL, 12));
        display("ACCT-CREDIT-LIMIT       :" + r.text(AccountRecord.ACCT_CREDIT_LIMIT, 12));
        display("ACCT-CASH-CREDIT-LIMIT  :" + r.text(AccountRecord.ACCT_CASH_CREDIT_LIMIT, 12));
        display("ACCT-OPEN-DATE          :" + r.text(AccountRecord.ACCT_OPEN_DATE, 10));
        display("ACCT-EXPIRAION-DATE     :" + r.text(AccountRecord.ACCT_EXPIRAION_DATE, 10));
        display("ACCT-REISSUE-DATE       :" + r.text(AccountRecord.ACCT_REISSUE_DATE, 10));
        display("ACCT-CURR-CYC-CREDIT    :" + r.text(AccountRecord.ACCT_CURR_CYC_CREDIT, 12));
        display("ACCT-CURR-CYC-DEBIT     :" + r.text(AccountRecord.ACCT_CURR_CYC_DEBIT, 12));
        display("ACCT-GROUP-ID           :" + r.text(AccountRecord.ACCT_GROUP_ID, 10));
        display("-------------------------------------------------");
    }

    // ---- 1300-POPUL-ACCT-RECORD -----------------------------------------

    byte[] populAcctRecord() {
        AccountRecord r = accountRecord;
        byte[] o = outAcctRec;
        put(o, 0, CobolCodec.toZoned(r.acctId(), 11, 0, false));
        put(o, 11, r.bytes(AccountRecord.ACCT_ACTIVE_STATUS, 1));
        put(o, 12, CobolCodec.toZoned(r.currBal(), 10 + 2, 2, true));
        put(o, 24, CobolCodec.toZoned(r.creditLimit(), 12, 2, true));
        put(o, 36, CobolCodec.toZoned(r.cashCreditLimit(), 12, 2, true));
        put(o, 48, r.bytes(AccountRecord.ACCT_OPEN_DATE, 10));
        put(o, 58, r.bytes(AccountRecord.ACCT_EXPIRAION_DATE, 10));

        String outDate = cobdatft(r.reissueDate(), '2', '2');
        put(o, 68, CobolCodec.ebcdic(outDate.substring(0, 10)));

        put(o, 78, CobolCodec.toZoned(r.currCycCredit(), 12, 2, true));
        if (r.currCycDebit().signum() == 0) {
            put(o, 90, CobolCodec.toComp3(new BigDecimal("2525.00"), 12, 2));
        }
        put(o, 97, r.bytes(AccountRecord.ACCT_GROUP_ID, 10));
        return o.clone();
    }

    void writeAcctRecord(byte[] rec) {
        outFileStatus = write(outFile, rec);
        if (!"00".equals(outFileStatus) && !"10".equals(outFileStatus)) {
            display("ACCOUNT FILE WRITE STATUS IS:" + outFileStatus);
            displayIoStatus(outFileStatus);
            abend();
        }
    }

    // ---- 1400-POPUL-ARRAY-RECORD ----------------------------------------

    byte[] populArrayRecord() {
        BigDecimal[] bal = new BigDecimal[5];
        BigDecimal[] debit = new BigDecimal[5];
        Arrays.fill(bal, BigDecimal.ZERO);   // INITIALIZE ARR-ARRAY-REC
        Arrays.fill(debit, BigDecimal.ZERO);

        AccountRecord r = accountRecord;
        bal[0] = r.currBal();
        debit[0] = new BigDecimal("1005.00");
        bal[1] = r.currBal();
        debit[1] = new BigDecimal("1525.00");
        bal[2] = new BigDecimal("-1025.00");
        debit[2] = new BigDecimal("-2500.00");

        byte[] a = new byte[ARR_REC_LEN];
        put(a, 0, CobolCodec.toZoned(r.acctId(), 11, 0, false));
        for (int i = 0; i < 5; i++) {
            int off = 11 + i * 19;
            put(a, off, CobolCodec.toZoned(bal[i], 12, 2, true));
            put(a, off + 12, CobolCodec.toComp3(debit[i], 12, 2));
        }
        put(a, 106, CobolCodec.ebcdic("", 4));
        return a;
    }

    void writeArryRecord(byte[] rec) {
        arryFileStatus = write(arryFile, rec);
        if (!"00".equals(arryFileStatus) && !"10".equals(arryFileStatus)) {
            display("ACCOUNT FILE WRITE STATUS IS:" + arryFileStatus);
            displayIoStatus(arryFileStatus);
            abend();
        }
    }

    // ---- 1500-POPUL-VBRC-RECORD -----------------------------------------

    byte[][] populVbrcRecord() {
        AccountRecord r = accountRecord;
        byte[] id = CobolCodec.toZoned(r.acctId(), 11, 0, false);

        byte[] rec1 = new byte[VB1_LEN];
        put(rec1, 0, id);
        put(rec1, 11, r.bytes(AccountRecord.ACCT_ACTIVE_STATUS, 1));

        byte[] rec2 = new byte[VB2_LEN];
        put(rec2, 0, id);
        put(rec2, 11, CobolCodec.toZoned(r.currBal(), 12, 2, true));
        put(rec2, 23, CobolCodec.toZoned(r.creditLimit(), 12, 2, true));
        put(rec2, 35, r.bytes(AccountRecord.ACCT_REISSUE_DATE, 4)); // WS-ACCT-REISSUE-YYYY

        display("VBRC-REC1:" + CobolCodec.fromEbcdic(rec1, 0, VB1_LEN));
        display("VBRC-REC2:" + CobolCodec.fromEbcdic(rec2, 0, VB2_LEN));
        return new byte[][] {rec1, rec2};
    }

    /** 1550/1575: RECFM=V record = RDW (2-byte BE length incl. RDW, 2 reserved) + data. */
    void writeVbrcRecord(byte[] data) {
        int len = data.length + 4;
        byte[] rec = new byte[len];
        rec[0] = (byte) (len >>> 8);
        rec[1] = (byte) len;
        System.arraycopy(data, 0, rec, 4, data.length);
        vbrcFileStatus = write(vbrcFile, rec);
        if (!"00".equals(vbrcFileStatus) && !"10".equals(vbrcFileStatus)) {
            display("ACCOUNT FILE WRITE STATUS IS:" + vbrcFileStatus);
            displayIoStatus(vbrcFileStatus);
            abend();
        }
    }

    // ---- COBDATFT (app/asm/COBDATFT.asm), CODATECN contract -------------

    /**
     * inType  '1' = input YYYYMMDD,   '2' = input YYYY-MM-DD.
     * outType '1' = output YYYY-MM-DD, '2' = output YYYYMMDD.
     * Returns the full 20-byte CODATECN-0UT-DATE; bytes not set by the
     * conversion keep their previous contents.
     */
    static String cobdatft(String inDate, char inType, char outType) {
        char[] in = new char[20];
        Arrays.fill(in, ' ');
        inDate.getChars(0, Math.min(20, inDate.length()), in, 0);
        char[] out = codatecnOutDate;

        if (inType == '1') {
            if (in[4] == '-' || outType == '2') {
                setError();
            } else {
                System.arraycopy(in, 0, out, 0, 4);
                out[4] = '-';
                System.arraycopy(in, 4, out, 5, 2);
                out[7] = '-';
                System.arraycopy(in, 6, out, 8, 2);
            }
        } else if (inType == '2') {
            if (outType == '1') {
                setError();
            } else {
                System.arraycopy(in, 0, out, 0, 4);
                System.arraycopy(in, 5, out, 4, 2);
                System.arraycopy(in, 8, out, 6, 2);
            }
        } else {
            setError();
        }
        return new String(out);
    }

    private static void setError() {
        Arrays.fill(codatecnErrorMsg, ' ');
        "INVALID INPUT".getChars(0, 13, codatecnErrorMsg, 0);
    }

    static String codatecnErrorMsg() {
        return new String(codatecnErrorMsg);
    }

    // ---- OPEN / CLOSE ---------------------------------------------------

    void acctFileOpen(String path) {
        try {
            acctFile = new java.io.BufferedInputStream(new FileInputStream(path));
            acctFileStatus = "00";
        } catch (IOException e) {
            acctFileStatus = "35";
        }
        if (!"00".equals(acctFileStatus)) {
            display("ERROR OPENING ACCTFILE");
            displayIoStatus(acctFileStatus);
            abend();
        }
    }

    private OutputStream openOutput(String path) throws CobolFileException {
        try {
            return new BufferedOutputStream(new FileOutputStream(path));
        } catch (IOException e) {
            throw new CobolFileException("30", e);
        }
    }

    void outFileOpen(String path) {
        try {
            outFile = openOutput(path);
            outFileStatus = "00";
        } catch (CobolFileException e) {
            outFileStatus = e.status;
            display("ERROR OPENING OUTFILE" + outFileStatus);
            displayIoStatus(outFileStatus);
            abend();
        }
    }

    void arrFileOpen(String path) {
        try {
            arryFile = openOutput(path);
            arryFileStatus = "00";
        } catch (CobolFileException e) {
            arryFileStatus = e.status;
            display("ERROR OPENING ARRAYFILE" + arryFileStatus);
            displayIoStatus(arryFileStatus);
            abend();
        }
    }

    void vbrFileOpen(String path) {
        try {
            vbrcFile = openOutput(path);
            vbrcFileStatus = "00";
        } catch (CobolFileException e) {
            vbrcFileStatus = e.status;
            display("ERROR OPENING VBRC FILE" + vbrcFileStatus);
            displayIoStatus(vbrcFileStatus);
            abend();
        }
    }

    void acctFileClose() {
        try {
            acctFile.close();
            acctFileStatus = "00";
        } catch (IOException e) {
            acctFileStatus = "30";
        }
        if (!"00".equals(acctFileStatus)) {
            display("ERROR CLOSING ACCOUNT FILE");
            displayIoStatus(acctFileStatus);
            abend();
        }
    }

    /** COBOL closes still-open files implicitly at GOBACK. */
    void closeOutputsAtGoback() {
        for (OutputStream os : new OutputStream[] {outFile, arryFile, vbrcFile}) {
            try {
                os.close();
            } catch (IOException e) {
                display("ERROR CLOSING OUTPUT FILE");
                displayIoStatus("30");
                abend();
            }
        }
    }

    // ---- 9910-DISPLAY-IO-STATUS / 9999-ABEND-PROGRAM --------------------

    static void displayIoStatus(String ioStatus) {
        char stat1 = ioStatus.charAt(0);
        char stat2 = ioStatus.charAt(1);
        boolean numeric = Character.isDigit(stat1) && Character.isDigit(stat2);
        String ioStatus04;
        if (!numeric || stat1 == '9') {
            int binary = CobolCodec.ebcdic(String.valueOf(stat2))[0] & 0xFF;
            ioStatus04 = stat1 + String.format("%03d", binary % 1000);
        } else {
            ioStatus04 = "00" + ioStatus;
        }
        display("FILE STATUS IS: NNNN" + ioStatus04);
    }

    static void abend() {
        display("ABENDING PROGRAM");
        System.out.flush();
        System.exit(12);
    }

    // ---- helpers --------------------------------------------------------

    private static String write(OutputStream os, byte[] rec) {
        try {
            os.write(rec);
            return "00";
        } catch (IOException e) {
            return "30";
        }
    }

    private static void put(byte[] dst, int off, byte[] src) {
        System.arraycopy(src, 0, dst, off, src.length);
    }

    private static void display(String s) {
        System.out.println(s);
    }
}
