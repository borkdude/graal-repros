public class Repro {
    public static void main(String [] args) throws Exception {
        System.out.println("env " + System.getenv("TMPDIR"));
        System.out.println("prop " + System.getProperty("java.io.tmpdir"));
        System.out.println("tmpfile " + java.io.File.createTempFile("clojure-lsp.", ".out"));
    }
}
