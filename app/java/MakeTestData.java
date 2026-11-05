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
import java.util.List;

public class MakeTestData {

    /** One account's values, in CVACT01Y field order. */
    static final class Account {
        final long id;
        final String status;
        final String currBal;
        final String creditLimit;
        final String cashCreditLimit;
        final String openDate;
        final String expirationDate;
        final String reissueDate;
        final String currCycCredit;
        final String currCycDebit;
        final String addrZip;
        final String groupId;

        Account(long id, String status, String currBal, String creditLimit, String cashCreditLimit,
                String openDate, String expirationDate, String reissueDate,
                String currCycCredit, String currCycDebit, String addrZip, String groupId) {
            this.id = id;
            this.status = status;
            this.currBal = currBal;
            this.creditLimit = creditLimit;
            this.cashCreditLimit = cashCreditLimit;
            this.openDate = openDate;
            this.expirationDate = expirationDate;
            this.reissueDate = reissueDate;
            this.currCycCredit = currCycCredit;
            this.currCycDebit = currCycDebit;
            this.addrZip = addrZip;
            this.groupId = groupId;
        }
    }

    static final List<Account> ACCOUNTS = List.of(
            new Account(1L, "Y", "1940.00", "20200.00", "10200.00",
                    "2014-11-20", "2025-05-20", "2025-05-20", "125.50", "310.25",
                    "10001", "A000000000"),
            new Account(2L, "Y", "1580.00", "61300.00", "54480.00",
                    "2013-06-19", "2024-08-11", "2024-08-11", "0.00", "0.00",
                    "20002", "B000000000"),
            new Account(3L, "N", "-275.75", "5000.00", "1500.00",
                    "2019-03-02", "2027-03-31", "2023-12-01", "40.00", "120.00",
                    "30003", "C000000000"));

    public static void main(String[] args) throws IOException {
        String path = args.length > 0 ? args[0] : "ACCTFILE.dat";
        try (OutputStream os = new FileOutputStream(path)) {
            for (Account a : ACCOUNTS) {
                os.write(encode(a));
            }
        }
        System.out.println("Wrote " + ACCOUNTS.size() + " records ("
                + ACCOUNTS.size() * Cbact01c.AccountRecord.LENGTH + " bytes) to " + path);
    }

    static byte[] encode(Account a) {
        byte[] r = Cbact01c.AccountRecord.blank().raw;
        Cbact01c.AccountRecord.ACCT_ID.setNumber(r, BigDecimal.valueOf(a.id));
        Cbact01c.AccountRecord.ACCT_ACTIVE_STATUS.setText(r, a.status);
        Cbact01c.AccountRecord.ACCT_CURR_BAL.setNumber(r, new BigDecimal(a.currBal));
        Cbact01c.AccountRecord.ACCT_CREDIT_LIMIT.setNumber(r, new BigDecimal(a.creditLimit));
        Cbact01c.AccountRecord.ACCT_CASH_CREDIT_LIMIT.setNumber(r, new BigDecimal(a.cashCreditLimit));
        Cbact01c.AccountRecord.ACCT_OPEN_DATE.setText(r, a.openDate);
        Cbact01c.AccountRecord.ACCT_EXPIRAION_DATE.setText(r, a.expirationDate);
        Cbact01c.AccountRecord.ACCT_REISSUE_DATE.setText(r, a.reissueDate);
        Cbact01c.AccountRecord.ACCT_CURR_CYC_CREDIT.setNumber(r, new BigDecimal(a.currCycCredit));
        Cbact01c.AccountRecord.ACCT_CURR_CYC_DEBIT.setNumber(r, new BigDecimal(a.currCycDebit));
        Cbact01c.AccountRecord.ACCT_ADDR_ZIP.setText(r, a.addrZip);
        Cbact01c.AccountRecord.ACCT_GROUP_ID.setText(r, a.groupId);
        return r;
    }
}
