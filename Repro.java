import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

public class Repro {
    public static void main(String[] args) throws Throwable {
        // Lookup the C standard library (platform dependent, here "c" works on Linux/macOS)
        SymbolLookup stdlib = Linker.nativeLinker().defaultLookup();

        // Find the strlen symbol
        MemorySegment strlenAddr = stdlib.find("strlen")
                                         .orElseThrow(() -> new RuntimeException("strlen not found"));

        // Describe the signature: strlen(const char*) -> size_t
        FunctionDescriptor strlenDesc = FunctionDescriptor.of(JAVA_LONG, ADDRESS);

        // Create a MethodHandle to call strlen
        MethodHandle strlen = Linker.nativeLinker().downcallHandle(strlenAddr, strlenDesc);

        // Allocate a C string
        String javaString = "Hello Panama!";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cString = arena.allocateFrom(javaString);

            // Cast to long for consistency
            long len = (long) strlen.invoke(cString);

            System.out.println("Java string: " + javaString);
            System.out.println("C strlen: " + len);
            System.out.println("Java length: " + javaString.length()); // For comparison
        }
    }
}
