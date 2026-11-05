      ******************************************************************
      * Loads fixed 300-byte account records (ACCTSEQ) into the indexed
      * file ACCTFILE, like the IDCAMS REPRO step in app/jcl/ACCTFILE.jcl.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LOADACCT.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT ACCTSEQ-FILE ASSIGN TO ACCTSEQ
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS  IS SEQ-STATUS.
           SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE
                  ORGANIZATION IS INDEXED
                  ACCESS MODE  IS SEQUENTIAL
                  RECORD KEY   IS KS-ACCT-ID
                  FILE STATUS  IS KS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  ACCTSEQ-FILE.
       01  SEQ-REC                       PIC X(300).
       FD  ACCTFILE-FILE.
       01  KS-REC.
           05 KS-ACCT-ID                 PIC 9(11).
           05 KS-ACCT-DATA               PIC X(289).
       WORKING-STORAGE SECTION.
       01  SEQ-STATUS                    PIC XX.
       01  KS-STATUS                     PIC XX.
       01  WS-COUNT                      PIC 9(5) VALUE 0.
       PROCEDURE DIVISION.
           OPEN INPUT ACCTSEQ-FILE
           OPEN OUTPUT ACCTFILE-FILE
           PERFORM UNTIL SEQ-STATUS NOT = '00'
               READ ACCTSEQ-FILE
               IF SEQ-STATUS = '00'
                   WRITE KS-REC FROM SEQ-REC
                   IF KS-STATUS NOT = '00'
                       DISPLAY 'LOADACCT WRITE STATUS ' KS-STATUS
                       MOVE 12 TO RETURN-CODE
                       STOP RUN
                   END-IF
                   ADD 1 TO WS-COUNT
               END-IF
           END-PERFORM
           CLOSE ACCTSEQ-FILE ACCTFILE-FILE
           DISPLAY 'LOADACCT RECORDS LOADED: ' WS-COUNT
           STOP RUN.
