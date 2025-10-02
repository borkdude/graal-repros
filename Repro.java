import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;
import java.security.Provider;
import java.security.Security;

public class Repro {
    public static void main(String[] args) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        int counter = 1;

        SSLSocketFactory factory = ctx.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            System.out.println("=== Supported Cipher Suites ===");
            for (String suite : socket.getSupportedCipherSuites()) {
                System.out.println(counter++ + " " + suite);
            }
            counter = 1;

            System.out.println("\n=== Default Enabled Cipher Suites ===");
            for (String suite : socket.getEnabledCipherSuites()) {
                System.out.println(counter++ + " " + suite);
            }
        }

        System.out.println("\n=== Registered Security Providers ===");
        counter = 1;
        for (Provider provider : Security.getProviders()) {
            System.out.println(counter++ + " " + provider.getName() + " - version: " + provider.getVersion());
        }
    }
}
