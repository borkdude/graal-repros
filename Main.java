import java.io.*;
import java.lang.reflect.*;
import java.net.*;

public class Main {
    public static void main(String[] args) throws Exception {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && System.getProperty("java.home") == null) {
            System.setProperty("java.home", javaHome);
        }
        File dir = new File(".");
        URLClassLoader cl = new URLClassLoader(new URL[]{dir.toURI().toURL()});
        Class<?> c = cl.loadClass("CallsForName");
        Method m = c.getMethod("run");
        m.invoke(null);
    }
}
