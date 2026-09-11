package calc;

/** Run by check.sh: one line per failed check, exit code 1 when any failed. */
public final class CalculatorCheck {

    private static int failures;

    public static void main(String[] args) {
        check("add(2, 3)", 5, Calculator.add(2, 3));
        check("subtract(7, 3)", 4, Calculator.subtract(7, 3));
        check("divide(9, 3)", 3, Calculator.divide(9, 3));
        if (failures > 0) {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    private static void check(String what, int expected, int actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }
}
