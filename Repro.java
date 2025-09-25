import java.io.OutputStreamWriter;

public class Repro {

    static OutputStreamWriter out = null;

    static void assignOutputWriter() {
        out = new OutputStreamWriter(System.out);
    }

    static {
        assignOutputWriter();
    }

    public static void main(String [] args) {
        System.out.println(System.getProperty("file.encoding"));
        System.out.println(out.getEncoding());
        System.out.println("λ");
        assignOutputWriter();
        System.out.println(out.getEncoding());
        System.out.println("λ");
    }
}
