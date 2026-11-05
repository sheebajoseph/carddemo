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

import java.io.BufferedInputStream;
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
import java.util.List;

public class Cbact01c {

    static final byte LOW_VALUE = 0x00;
    static final byte EBCDIC_SPACE = 0x40;
    static final int ABEND_EXIT_CODE = 12;

    // Hardcoded values from 1300-POPUL-ACCT-RECORD and 1400-POPUL-ARRAY-RECORD.
    static final BigDecimal ZERO_DEBIT_SUBSTITUTE = new BigDecimal("2525.00");
    static final BigDecimal ARR_DEBIT_1 = new BigDecimal("1005.00");
    static final BigDecimal ARR_DEBIT_2 = new BigDecimal("1525.00");
    static final BigDecimal ARR_BAL_3 = new BigDecimal("-1025.00");
    static final BigDecimal ARR_DEBIT_3 = new BigDecimal("-2500.00");

    // =====================================================================
    // Data representation
    // =====================================================================

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
            return padRight(ebcdic(s), len);
        }

        static byte[] padRight(byte[] src, int len) {
            byte[] out = new byte[len];
            Arrays.fill(out, EBCDIC_SPACE);
            System.arraycopy(src, 0, out, 0, Math.min(len, src.length));
            return out;
        }

        static String fromEbcdic(byte[] b, int off, int len) {
            return new String(b, off, len, EBCDIC);
        }

        /** Unscaled absolute digits of v for PIC 9(digits)V9(scale), COBOL MOVE truncation. */
        private static String digitsOf(BigDecimal v, int digits, int scale) {
            BigInteger unscaled = v.setScale(scale, RoundingMode.DOWN).unscaledValue().abs();
            String s = unscaled.mod(BigInteger.TEN.pow(digits)).toString();
            return "0".repeat(digits - s.length()) + s;
        }

        private static boolean isNegative(BigDecimal v, String digits) {
            return v.signum() < 0 && digits.chars().anyMatch(c -> c != '0');
        }

        /** Zoned decimal (USAGE DISPLAY). Signed: last zone C (+) / D (-); unsigned: F. */
        static byte[] toZoned(BigDecimal v, int digits, int scale, boolean signed) {
            String d = digitsOf(v, digits, scale);
            byte[] out = new byte[digits];
            for (int i = 0; i < digits; i++) {
                out[i] = (byte) (0xF0 | (d.charAt(i) - '0'));
            }
            if (signed) {
                int zone = isNegative(v, d) ? 0xD0 : 0xC0;
                out[digits - 1] = (byte) (zone | (d.charAt(digits - 1) - '0'));
            }
            return out;
        }

        static BigDecimal fromZoned(byte[] b, int off, int len, int scale) {
            BigInteger value = BigInteger.ZERO;
            for (int i = 0; i < len; i++) {
                value = appendDigit(value, b[off + i] & 0x0F, off + i);
            }
            int zone = (b[off + len - 1] & 0xF0) >>> 4;
            return new BigDecimal(isNegativeSign(zone) ? value.negate() : value, scale);
        }

        /** Packed decimal (COMP-3): (digits+2)/2 bytes, sign nibble C (+) / D (-). */
        static byte[] toComp3(BigDecimal v, int digits, int scale) {
            String d = digitsOf(v, digits, scale);
            int sign = isNegative(v, d) ? 0xD : 0xC;
            if (digits % 2 == 0) {
                d = "0" + d;
            }
            byte[] out = new byte[comp3Length(digits)];
            for (int i = 0; i < out.length; i++) {
                int hi = d.charAt(2 * i) - '0';
                int lo = (2 * i + 1 < d.length()) ? d.charAt(2 * i + 1) - '0' : sign;
                out[i] = (byte) ((hi << 4) | lo);
            }
            return out;
        }

        static BigDecimal fromComp3(byte[] b, int off, int len, int scale) {
            BigInteger value = BigInteger.ZERO;
            for (int i = 0; i < len - 1; i++) {
                value = appendDigit(value, (b[off + i] & 0xF0) >>> 4, off + i);
                value = appendDigit(value, b[off + i] & 0x0F, off + i);
            }
            int last = off + len - 1;
            value = appendDigit(value, (b[last] & 0xF0) >>> 4, last);
            int sign = b[last] & 0x0F;
            if (sign < 0xA) {
                throw new NumberFormatException("Invalid packed sign at offset " + last);
            }
            return new BigDecimal(isNegativeSign(sign) ? value.negate() : value, scale);
        }

        static int comp3Length(int digits) {
            return (digits + 2) / 2;
        }

        private static BigInteger appendDigit(BigInteger value, int digit, int offset) {
            if (digit > 9) {
                throw new NumberFormatException("Invalid decimal digit at offset " + offset);
            }
            return value.multiply(BigInteger.TEN).add(BigInteger.valueOf(digit));
        }

        private static boolean isNegativeSign(int nibble) {
            return nibble == 0xD || nibble == 0xB;
        }
    }

    /** One elementary item of a record layout (offset/length/PIC/USAGE). */
    static final class Field {
        enum Usage { ALPHANUMERIC, ZONED_UNSIGNED, ZONED_SIGNED, COMP3 }

        final String name;
        final int offset;
        final int length;
        final int digits;
        final int scale;
        final Usage usage;

        private Field(String name, int offset, int length, int digits, int scale, Usage usage) {
            this.name = name;
            this.offset = offset;
            this.length = length;
            this.digits = digits;
            this.scale = scale;
            this.usage = usage;
        }

        /** PIC X(len). */
        static Field alpha(String name, int offset, int len) {
            return new Field(name, offset, len, 0, 0, Usage.ALPHANUMERIC);
        }

        /** PIC 9(digits). */
        static Field unsigned(String name, int offset, int digits) {
            return new Field(name, offset, digits, digits, 0, Usage.ZONED_UNSIGNED);
        }

        /** PIC S9(intDigits)V9(scale) USAGE DISPLAY. */
        static Field zoned(String name, int offset, int intDigits, int scale) {
            int digits = intDigits + scale;
            return new Field(name, offset, digits, digits, scale, Usage.ZONED_SIGNED);
        }

        /** PIC S9(intDigits)V9(scale) USAGE COMP-3. */
        static Field comp3(String name, int offset, int intDigits, int scale) {
            int digits = intDigits + scale;
            return new Field(name, offset, CobolCodec.comp3Length(digits), digits, scale, Usage.COMP3);
        }

        /** Same PIC/USAGE at a different offset (used for OCCURS entries). */
        Field at(int newOffset) {
            return new Field(name, newOffset, length, digits, scale, usage);
        }

        int end() {
            return offset + length;
        }

        boolean isNumeric() {
            return usage != Usage.ALPHANUMERIC;
        }

        byte[] bytes(byte[] rec) {
            return Arrays.copyOfRange(rec, offset, end());
        }

        /** Raw EBCDIC contents decoded as text, as COBOL DISPLAY shows them. */
        String text(byte[] rec) {
            return CobolCodec.fromEbcdic(rec, offset, length);
        }

        BigDecimal number(byte[] rec) {
            switch (usage) {
                case ZONED_UNSIGNED:
                case ZONED_SIGNED:
                    return CobolCodec.fromZoned(rec, offset, length, scale);
                case COMP3:
                    return CobolCodec.fromComp3(rec, offset, length, scale);
                default:
                    throw new IllegalStateException(name + " is not numeric");
            }
        }

        void setNumber(byte[] rec, BigDecimal v) {
            switch (usage) {
                case ZONED_UNSIGNED:
                    put(rec, CobolCodec.toZoned(v, digits, scale, false));
                    break;
                case ZONED_SIGNED:
                    put(rec, CobolCodec.toZoned(v, digits, scale, true));
                    break;
                case COMP3:
                    put(rec, CobolCodec.toComp3(v, digits, scale));
                    break;
                default:
                    throw new IllegalStateException(name + " is not numeric");
            }
        }

        void setText(byte[] rec, String s) {
            put(rec, CobolCodec.ebcdic(s, length));
        }

        void setBytes(byte[] rec, byte[] src) {
            put(rec, CobolCodec.padRight(src, length));
        }

        private void put(byte[] rec, byte[] value) {
            System.arraycopy(value, 0, rec, offset, length);
        }
    }

    /** COBOL MOVE between elementary items: numeric by value, alphanumeric by bytes. */
    static void move(Field src, byte[] srcRec, Field dst, byte[] dstRec) {
        if (src.isNumeric() && dst.isNumeric()) {
            dst.setNumber(dstRec, src.number(srcRec));
        } else {
            dst.setBytes(dstRec, src.bytes(srcRec));
        }
    }

    // =====================================================================
    // Record layouts
    // =====================================================================

    /** ACCOUNT-RECORD per copybook CVACT01Y (RECLN 300). */
    static final class AccountRecord {
        static final Field ACCT_ID = Field.unsigned("ACCT-ID", 0, 11);
        static final Field ACCT_ACTIVE_STATUS = Field.alpha("ACCT-ACTIVE-STATUS", 11, 1);
        static final Field ACCT_CURR_BAL = Field.zoned("ACCT-CURR-BAL", 12, 10, 2);
        static final Field ACCT_CREDIT_LIMIT = Field.zoned("ACCT-CREDIT-LIMIT", 24, 10, 2);
        static final Field ACCT_CASH_CREDIT_LIMIT = Field.zoned("ACCT-CASH-CREDIT-LIMIT", 36, 10, 2);
        static final Field ACCT_OPEN_DATE = Field.alpha("ACCT-OPEN-DATE", 48, 10);
        static final Field ACCT_EXPIRAION_DATE = Field.alpha("ACCT-EXPIRAION-DATE", 58, 10);
        static final Field ACCT_REISSUE_DATE = Field.alpha("ACCT-REISSUE-DATE", 68, 10);
        static final Field ACCT_CURR_CYC_CREDIT = Field.zoned("ACCT-CURR-CYC-CREDIT", 78, 10, 2);
        static final Field ACCT_CURR_CYC_DEBIT = Field.zoned("ACCT-CURR-CYC-DEBIT", 90, 10, 2);
        static final Field ACCT_ADDR_ZIP = Field.alpha("ACCT-ADDR-ZIP", 102, 10);
        static final Field ACCT_GROUP_ID = Field.alpha("ACCT-GROUP-ID", 112, 10);
        static final Field FILLER = Field.alpha("FILLER", 122, 178);

        static final List<Field> FIELDS = List.of(ACCT_ID, ACCT_ACTIVE_STATUS, ACCT_CURR_BAL,
                ACCT_CREDIT_LIMIT, ACCT_CASH_CREDIT_LIMIT, ACCT_OPEN_DATE, ACCT_EXPIRAION_DATE,
                ACCT_REISSUE_DATE, ACCT_CURR_CYC_CREDIT, ACCT_CURR_CYC_DEBIT, ACCT_ADDR_ZIP,
                ACCT_GROUP_ID, FILLER);

        /** Fields shown by 1100-DISPLAY-ACCT-RECORD, in order. */
        static final List<Field> DISPLAYED = List.of(ACCT_ID, ACCT_ACTIVE_STATUS, ACCT_CURR_BAL,
                ACCT_CREDIT_LIMIT, ACCT_CASH_CREDIT_LIMIT, ACCT_OPEN_DATE, ACCT_EXPIRAION_DATE,
                ACCT_REISSUE_DATE, ACCT_CURR_CYC_CREDIT, ACCT_CURR_CYC_DEBIT, ACCT_GROUP_ID);

        static final int LENGTH = 300;

        final byte[] raw;

        AccountRecord(byte[] record) {
            if (record.length != LENGTH) {
                throw new IllegalArgumentException("ACCOUNT-RECORD must be " + LENGTH + " bytes");
            }
            this.raw = record.clone();
        }

        static AccountRecord blank() {
            byte[] r = new byte[LENGTH];
            Arrays.fill(r, EBCDIC_SPACE);
            return new AccountRecord(r);
        }

        String text() {
            return CobolCodec.fromEbcdic(raw, 0, LENGTH);
        }

        BigDecimal currCycDebit() {
            return ACCT_CURR_CYC_DEBIT.number(raw);
        }

        String reissueDate() {
            return ACCT_REISSUE_DATE.text(raw);
        }
    }

    /** OUT-ACCT-REC (FD OUT-FILE). */
    static final class OutAcctRec {
        static final Field OUT_ACCT_ID = Field.unsigned("OUT-ACCT-ID", 0, 11);
        static final Field OUT_ACCT_ACTIVE_STATUS = Field.alpha("OUT-ACCT-ACTIVE-STATUS", 11, 1);
        static final Field OUT_ACCT_CURR_BAL = Field.zoned("OUT-ACCT-CURR-BAL", 12, 10, 2);
        static final Field OUT_ACCT_CREDIT_LIMIT = Field.zoned("OUT-ACCT-CREDIT-LIMIT", 24, 10, 2);
        static final Field OUT_ACCT_CASH_CREDIT_LIMIT =
                Field.zoned("OUT-ACCT-CASH-CREDIT-LIMIT", 36, 10, 2);
        static final Field OUT_ACCT_OPEN_DATE = Field.alpha("OUT-ACCT-OPEN-DATE", 48, 10);
        static final Field OUT_ACCT_EXPIRAION_DATE = Field.alpha("OUT-ACCT-EXPIRAION-DATE", 58, 10);
        static final Field OUT_ACCT_REISSUE_DATE = Field.alpha("OUT-ACCT-REISSUE-DATE", 68, 10);
        static final Field OUT_ACCT_CURR_CYC_CREDIT = Field.zoned("OUT-ACCT-CURR-CYC-CREDIT", 78, 10, 2);
        static final Field OUT_ACCT_CURR_CYC_DEBIT = Field.comp3("OUT-ACCT-CURR-CYC-DEBIT", 90, 10, 2);
        static final Field OUT_ACCT_GROUP_ID = Field.alpha("OUT-ACCT-GROUP-ID", 97, 10);

        static final List<Field> FIELDS = List.of(OUT_ACCT_ID, OUT_ACCT_ACTIVE_STATUS,
                OUT_ACCT_CURR_BAL, OUT_ACCT_CREDIT_LIMIT, OUT_ACCT_CASH_CREDIT_LIMIT,
                OUT_ACCT_OPEN_DATE, OUT_ACCT_EXPIRAION_DATE, OUT_ACCT_REISSUE_DATE,
                OUT_ACCT_CURR_CYC_CREDIT, OUT_ACCT_CURR_CYC_DEBIT, OUT_ACCT_GROUP_ID);

        static final int LENGTH = 107;
    }

    /** ARR-ARRAY-REC (FD ARRY-FILE) with ARR-ACCT-BAL OCCURS 5 TIMES. */
    static final class ArrArrayRec {
        static final int OCCURS = 5;
        static final int ENTRY_OFFSET = 11;
        static final int ENTRY_LENGTH = 19;

        static final Field ARR_ACCT_ID = Field.unsigned("ARR-ACCT-ID", 0, 11);
        private static final Field CURR_BAL = Field.zoned("ARR-ACCT-CURR-BAL", 0, 10, 2);
        private static final Field CURR_CYC_DEBIT = Field.comp3("ARR-ACCT-CURR-CYC-DEBIT", 12, 10, 2);
        static final Field ARR_FILLER = Field.alpha("ARR-FILLER", 106, 4);

        static final int LENGTH = 110;

        /** ARR-ACCT-CURR-BAL(index), 1-based like COBOL. */
        static Field currBal(int index) {
            return CURR_BAL.at(entryOffset(index) + CURR_BAL.offset);
        }

        /** ARR-ACCT-CURR-CYC-DEBIT(index), 1-based like COBOL. */
        static Field currCycDebit(int index) {
            return CURR_CYC_DEBIT.at(entryOffset(index) + CURR_CYC_DEBIT.offset);
        }

        private static int entryOffset(int index) {
            if (index < 1 || index > OCCURS) {
                throw new IndexOutOfBoundsException("ARR-ACCT-BAL subscript " + index);
            }
            return ENTRY_OFFSET + (index - 1) * ENTRY_LENGTH;
        }

        /** INITIALIZE ARR-ARRAY-REC: numerics to zero, alphanumerics to spaces. */
        static byte[] initialized() {
            byte[] rec = new byte[LENGTH];
            ARR_ACCT_ID.setNumber(rec, BigDecimal.ZERO);
            for (int i = 1; i <= OCCURS; i++) {
                currBal(i).setNumber(rec, BigDecimal.ZERO);
                currCycDebit(i).setNumber(rec, BigDecimal.ZERO);
            }
            ARR_FILLER.setText(rec, "");
            return rec;
        }
    }

    /** VBRC-REC1 (written with WS-RECD-LEN = 12). */
    static final class VbrcRec1 {
        static final Field VB1_ACCT_ID = Field.unsigned("VB1-ACCT-ID", 0, 11);
        static final Field VB1_ACCT_ACTIVE_STATUS = Field.alpha("VB1-ACCT-ACTIVE-STATUS", 11, 1);
        static final int LENGTH = 12;
    }

    /** VBRC-REC2 (written with WS-RECD-LEN = 39). */
    static final class VbrcRec2 {
        static final Field VB2_ACCT_ID = Field.unsigned("VB2-ACCT-ID", 0, 11);
        static final Field VB2_ACCT_CURR_BAL = Field.zoned("VB2-ACCT-CURR-BAL", 11, 10, 2);
        static final Field VB2_ACCT_CREDIT_LIMIT = Field.zoned("VB2-ACCT-CREDIT-LIMIT", 23, 10, 2);
        static final Field VB2_ACCT_REISSUE_YYYY = Field.alpha("VB2-ACCT-REISSUE-YYYY", 35, 4);
        static final int LENGTH = 39;
    }

    // =====================================================================
    // COBDATFT (app/asm/COBDATFT.asm), CODATECN contract
    // =====================================================================

    /** Emulates COBDATFT operating on a persistent CODATECN-REC. */
    static final class Cobdatft {
        static final char IN_YYYYMMDD = '1';
        static final char IN_YYYY_MM_DD = '2';
        static final char OUT_YYYY_MM_DD = '1';
        static final char OUT_YYYYMMDD = '2';
        static final String INVALID_INPUT = "INVALID INPUT";

        private final char[] outDate = new char[20];
        private final char[] errorMsg = new char[38];

        Cobdatft() {
            Arrays.fill(outDate, (char) LOW_VALUE);
            Arrays.fill(errorMsg, (char) LOW_VALUE);
        }

        /**
         * Returns the full 20-byte CODATECN-0UT-DATE; bytes not set by the
         * conversion keep their previous contents.
         */
        String convert(String inDate, char inType, char outType) {
            char[] in = new char[20];
            Arrays.fill(in, ' ');
            inDate.getChars(0, Math.min(in.length, inDate.length()), in, 0);

            if (inType == IN_YYYYMMDD && in[4] != '-' && outType != OUT_YYYYMMDD) {
                System.arraycopy(in, 0, outDate, 0, 4);
                outDate[4] = '-';
                System.arraycopy(in, 4, outDate, 5, 2);
                outDate[7] = '-';
                System.arraycopy(in, 6, outDate, 8, 2);
            } else if (inType == IN_YYYY_MM_DD && outType != OUT_YYYY_MM_DD) {
                System.arraycopy(in, 0, outDate, 0, 4);
                System.arraycopy(in, 5, outDate, 4, 2);
                System.arraycopy(in, 8, outDate, 6, 2);
            } else {
                Arrays.fill(errorMsg, ' ');
                INVALID_INPUT.getChars(0, INVALID_INPUT.length(), errorMsg, 0);
            }
            return new String(outDate);
        }

        String errorMsg() {
            return new String(errorMsg);
        }
    }

    // =====================================================================
    // File handling
    // =====================================================================

    /** COBOL FILE STATUS values produced by this port. */
    static final class FileStatus {
        static final String OK = "00";
        static final String WRONG_LENGTH = "04";
        static final String EOF = "10";
        static final String IO_ERROR = "30";
        static final String NOT_FOUND = "35";

        private FileStatus() {
        }

        static boolean isWriteOk(String status) {
            return OK.equals(status) || EOF.equals(status);
        }
    }

    /** ACCTFILE: KSDS read sequentially (unloaded as fixed-length records in key order). */
    static final class AccountFile {
        private InputStream in;
        String status = FileStatus.OK;
        byte[] record;

        void open(String path) {
            try {
                in = new BufferedInputStream(new FileInputStream(path));
                status = FileStatus.OK;
            } catch (IOException e) {
                status = FileStatus.NOT_FOUND;
            }
            if (!FileStatus.OK.equals(status)) {
                display("ERROR OPENING ACCTFILE");
                failWith(status);
            }
        }

        /** READ ... sets status to 00 (record available), 10 (EOF) or an error code. */
        void read() {
            byte[] buf = new byte[AccountRecord.LENGTH];
            try {
                int n = in.readNBytes(buf, 0, buf.length);
                if (n == 0) {
                    status = FileStatus.EOF;
                } else if (n < buf.length) {
                    status = FileStatus.WRONG_LENGTH;
                } else {
                    status = FileStatus.OK;
                }
            } catch (IOException e) {
                status = FileStatus.IO_ERROR;
            }
            record = FileStatus.OK.equals(status) ? buf : null;
        }

        void close() {
            try {
                in.close();
                status = FileStatus.OK;
            } catch (IOException e) {
                status = FileStatus.IO_ERROR;
            }
            if (!FileStatus.OK.equals(status)) {
                display("ERROR CLOSING ACCOUNT FILE");
                failWith(status);
            }
        }
    }

    /** Sequential output file (QSAM). Variable records get a 4-byte RDW. */
    static final class OutputFile {
        private static final int RDW_LENGTH = 4;

        private final String openErrorLabel;
        private OutputStream out;
        String status = FileStatus.OK;

        OutputFile(String openErrorLabel) {
            this.openErrorLabel = openErrorLabel;
        }

        void open(String path) {
            try {
                out = new BufferedOutputStream(new FileOutputStream(path));
                status = FileStatus.OK;
            } catch (IOException e) {
                status = FileStatus.IO_ERROR;
                display("ERROR OPENING " + openErrorLabel + status);
                failWith(status);
            }
        }

        void write(byte[] rec) {
            try {
                out.write(rec);
                status = FileStatus.OK;
            } catch (IOException e) {
                status = FileStatus.IO_ERROR;
            }
            if (!FileStatus.isWriteOk(status)) {
                display("ACCOUNT FILE WRITE STATUS IS:" + status);
                failWith(status);
            }
        }

        /** RECFM=V record: RDW (2-byte big-endian length incl. RDW, 2 reserved) + data. */
        void writeVariable(byte[] data, int recdLen) {
            int len = recdLen + RDW_LENGTH;
            byte[] rec = new byte[len];
            rec[0] = (byte) (len >>> 8);
            rec[1] = (byte) len;
            System.arraycopy(data, 0, rec, RDW_LENGTH, recdLen);
            write(rec);
        }

        /** COBOL closes still-open files implicitly at GOBACK. */
        void closeAtGoback() {
            try {
                out.close();
            } catch (IOException e) {
                display("ERROR CLOSING " + openErrorLabel);
                failWith(FileStatus.IO_ERROR);
            }
        }
    }

    // =====================================================================
    // Program
    // =====================================================================

    private final AccountFile acctFile = new AccountFile();
    private final OutputFile outFile = new OutputFile("OUTFILE");
    private final OutputFile arryFile = new OutputFile("ARRAYFILE");
    private final OutputFile vbrcFile = new OutputFile("VBRC FILE");
    private final Cobdatft cobdatft = new Cobdatft();

    // FD OUT-FILE record area: persists across writes (see header comment).
    private final byte[] outAcctRec = new byte[OutAcctRec.LENGTH];
    private AccountRecord accountRecord;
    private boolean endOfFile;

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
        acctFile.open(acct);
        outFile.open(out);
        arryFile.open(arry);
        vbrcFile.open(vbrc);

        while (!endOfFile) {
            acctFileGetNext();
            if (!endOfFile) {
                display(accountRecord.text());
            }
        }

        acctFile.close();
        outFile.closeAtGoback();
        arryFile.closeAtGoback();
        vbrcFile.closeAtGoback();

        display("END OF EXECUTION OF PROGRAM CBACT01C");
    }

    /** 1000-ACCTFILE-GET-NEXT. */
    void acctFileGetNext() {
        acctFile.read();
        if (FileStatus.EOF.equals(acctFile.status)) {
            endOfFile = true;
            return;
        }
        if (!FileStatus.OK.equals(acctFile.status)) {
            display("ERROR READING ACCOUNT FILE");
            failWith(acctFile.status);
        }

        accountRecord = new AccountRecord(acctFile.record);
        try {
            displayAcctRecord();
            outFile.write(populAcctRecord());
            arryFile.write(populArrayRecord());
            byte[][] vbrc = populVbrcRecords();
            vbrcFile.writeVariable(vbrc[0], VbrcRec1.LENGTH);
            vbrcFile.writeVariable(vbrc[1], VbrcRec2.LENGTH);
        } catch (NumberFormatException e) {
            display("DATA EXCEPTION: " + e.getMessage());
            abend();
        }
    }

    /** 1100-DISPLAY-ACCT-RECORD. */
    void displayAcctRecord() {
        for (Field f : AccountRecord.DISPLAYED) {
            display(String.format("%-24s:%s", f.name, f.text(accountRecord.raw)));
        }
        display("-------------------------------------------------");
    }

    /** 1300-POPUL-ACCT-RECORD. */
    byte[] populAcctRecord() {
        byte[] in = accountRecord.raw;
        byte[] o = outAcctRec;
        move(AccountRecord.ACCT_ID, in, OutAcctRec.OUT_ACCT_ID, o);
        move(AccountRecord.ACCT_ACTIVE_STATUS, in, OutAcctRec.OUT_ACCT_ACTIVE_STATUS, o);
        move(AccountRecord.ACCT_CURR_BAL, in, OutAcctRec.OUT_ACCT_CURR_BAL, o);
        move(AccountRecord.ACCT_CREDIT_LIMIT, in, OutAcctRec.OUT_ACCT_CREDIT_LIMIT, o);
        move(AccountRecord.ACCT_CASH_CREDIT_LIMIT, in, OutAcctRec.OUT_ACCT_CASH_CREDIT_LIMIT, o);
        move(AccountRecord.ACCT_OPEN_DATE, in, OutAcctRec.OUT_ACCT_OPEN_DATE, o);
        move(AccountRecord.ACCT_EXPIRAION_DATE, in, OutAcctRec.OUT_ACCT_EXPIRAION_DATE, o);

        String outDate = cobdatft(accountRecord.reissueDate(),
                Cobdatft.IN_YYYY_MM_DD, Cobdatft.OUT_YYYYMMDD);
        OutAcctRec.OUT_ACCT_REISSUE_DATE.setText(o, outDate);

        move(AccountRecord.ACCT_CURR_CYC_CREDIT, in, OutAcctRec.OUT_ACCT_CURR_CYC_CREDIT, o);
        if (accountRecord.currCycDebit().signum() == 0) {
            OutAcctRec.OUT_ACCT_CURR_CYC_DEBIT.setNumber(o, ZERO_DEBIT_SUBSTITUTE);
        }
        move(AccountRecord.ACCT_GROUP_ID, in, OutAcctRec.OUT_ACCT_GROUP_ID, o);
        return o.clone();
    }

    /** 1400-POPUL-ARRAY-RECORD (after INITIALIZE ARR-ARRAY-REC). */
    byte[] populArrayRecord() {
        byte[] in = accountRecord.raw;
        byte[] a = ArrArrayRec.initialized();
        move(AccountRecord.ACCT_ID, in, ArrArrayRec.ARR_ACCT_ID, a);
        move(AccountRecord.ACCT_CURR_BAL, in, ArrArrayRec.currBal(1), a);
        ArrArrayRec.currCycDebit(1).setNumber(a, ARR_DEBIT_1);
        move(AccountRecord.ACCT_CURR_BAL, in, ArrArrayRec.currBal(2), a);
        ArrArrayRec.currCycDebit(2).setNumber(a, ARR_DEBIT_2);
        ArrArrayRec.currBal(3).setNumber(a, ARR_BAL_3);
        ArrArrayRec.currCycDebit(3).setNumber(a, ARR_DEBIT_3);
        return a;
    }

    /** 1500-POPUL-VBRC-RECORD: returns {VBRC-REC1, VBRC-REC2}. */
    byte[][] populVbrcRecords() {
        byte[] in = accountRecord.raw;

        byte[] rec1 = new byte[VbrcRec1.LENGTH];
        move(AccountRecord.ACCT_ID, in, VbrcRec1.VB1_ACCT_ID, rec1);
        move(AccountRecord.ACCT_ACTIVE_STATUS, in, VbrcRec1.VB1_ACCT_ACTIVE_STATUS, rec1);

        byte[] rec2 = new byte[VbrcRec2.LENGTH];
        move(AccountRecord.ACCT_ID, in, VbrcRec2.VB2_ACCT_ID, rec2);
        move(AccountRecord.ACCT_CURR_BAL, in, VbrcRec2.VB2_ACCT_CURR_BAL, rec2);
        move(AccountRecord.ACCT_CREDIT_LIMIT, in, VbrcRec2.VB2_ACCT_CREDIT_LIMIT, rec2);
        // WS-ACCT-REISSUE-YYYY: first 4 bytes of ACCT-REISSUE-DATE.
        VbrcRec2.VB2_ACCT_REISSUE_YYYY.setBytes(rec2,
                Arrays.copyOf(AccountRecord.ACCT_REISSUE_DATE.bytes(in), 4));

        display("VBRC-REC1:" + CobolCodec.fromEbcdic(rec1, 0, rec1.length));
        display("VBRC-REC2:" + CobolCodec.fromEbcdic(rec2, 0, rec2.length));
        return new byte[][] {rec1, rec2};
    }

    /** CALL 'COBDATFT' USING CODATECN-REC. */
    String cobdatft(String inDate, char inType, char outType) {
        return cobdatft.convert(inDate, inType, outType);
    }

    // =====================================================================
    // 9910-DISPLAY-IO-STATUS / 9999-ABEND-PROGRAM
    // =====================================================================

    static void failWith(String ioStatus) {
        displayIoStatus(ioStatus);
        abend();
    }

    static void displayIoStatus(String ioStatus) {
        char stat1 = ioStatus.charAt(0);
        char stat2 = ioStatus.charAt(1);
        boolean numeric = Character.isDigit(stat1) && Character.isDigit(stat2);
        String ioStatus04;
        if (!numeric || stat1 == '9') {
            int binary = CobolCodec.ebcdic(String.valueOf(stat2))[0] & 0xFF;
            ioStatus04 = stat1 + String.format("%03d", binary);
        } else {
            ioStatus04 = "00" + ioStatus;
        }
        display("FILE STATUS IS: NNNN" + ioStatus04);
    }

    static void abend() {
        display("ABENDING PROGRAM");
        System.out.flush();
        System.exit(ABEND_EXIT_CODE);
    }

    static void display(String s) {
        System.out.println(s);
    }
}
