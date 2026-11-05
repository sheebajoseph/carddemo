      ******************************************************************
      * Stand-in for the LE service CEE3ABD under GnuCOBOL: ends the
      * run with return code 12 (same as the Java port's abend()).
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEE3ABD.
       DATA DIVISION.
       LINKAGE SECTION.
       01  ABCODE                  PIC S9(9) BINARY.
       01  TIMING                  PIC S9(9) BINARY.
       PROCEDURE DIVISION USING ABCODE TIMING.
           DISPLAY 'CEE3ABD ABEND CODE: ' ABCODE
           MOVE 12 TO RETURN-CODE
           STOP RUN.
