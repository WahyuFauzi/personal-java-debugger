package eval;

public class EvalTarget {
    private int count = 3;
    private String label = "n";

    public static int STATIC = 7;

    public int add(int a, int b) {
        return a + b;
    }

    public String shout(String s) {
        return s + "!";
    }

    public static void main(String[] args) {
        EvalTarget target = new EvalTarget();
        int a = 10;
        int b = 4;
        double d = 2.5;
        boolean flag = true;
        String text = "hello";
        long big = 9000000000L;
        target.run(a, b, d, flag, text, big);
    }

    private void run(int a, int b, double d, boolean flag, String text, long big) {
        int local = a + b;
        System.out.println("local=" + local);
    }
}
