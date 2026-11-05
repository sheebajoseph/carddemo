      ******************************************************************
      * COBOL stand-in for the assembler routine app/asm/COBDATFT.asm,
      * used only to run CBACT01C under GnuCOBOL. Same CODATECN
      * contract and the same branches as the assembler code.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COBDATFT.
       DATA DIVISION.
       LINKAGE SECTION.
       COPY CODATECN.
       PROCEDURE DIVISION USING CODATECN-REC.
           EVALUATE TRUE
             WHEN CODATECN-TYPE = '1'
               IF CODATECN-INP-DATE(5:1) = '-'
               OR CODATECN-OUTTYPE = '2'
                 MOVE 'INVALID INPUT' TO CODATECN-ERROR-MSG
               ELSE
                 MOVE CODATECN-INP-DATE(1:4) TO CODATECN-0UT-DATE(1:4)
                 MOVE '-'                    TO CODATECN-0UT-DATE(5:1)
                 MOVE CODATECN-INP-DATE(5:2) TO CODATECN-0UT-DATE(6:2)
                 MOVE '-'                    TO CODATECN-0UT-DATE(8:1)
                 MOVE CODATECN-INP-DATE(7:2) TO CODATECN-0UT-DATE(9:2)
               END-IF
             WHEN CODATECN-TYPE = '2'
               IF CODATECN-OUTTYPE = '1'
                 MOVE 'INVALID INPUT' TO CODATECN-ERROR-MSG
               ELSE
                 MOVE CODATECN-INP-DATE(1:4) TO CODATECN-0UT-DATE(1:4)
                 MOVE CODATECN-INP-DATE(6:2) TO CODATECN-0UT-DATE(5:2)
                 MOVE CODATECN-INP-DATE(9:2) TO CODATECN-0UT-DATE(7:2)
               END-IF
             WHEN OTHER
               MOVE 'INVALID INPUT' TO CODATECN-ERROR-MSG
           END-EVALUATE
           GOBACK.
