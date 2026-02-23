/**
 * This class is compiled separately and loaded at runtime via Crema.
 * It calls Class.forName, which fails because GraalVM inlines the
 * substitution at each call site and never compiles the original method.
 */
public class CallsForName {
    public static void run() throws Exception {
        Class<?> c = Class.forName("java.util.ArrayList");
        System.out.println("Class: " + c.getName());
    }
}
