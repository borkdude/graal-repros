import org.graalvm.nativeimage.hosted.Feature;

/**
 * GraalVM Feature that forces deterministic Clojure core initialization
 * before the parallel analysis phase begins.
 *
 * Without this, --initialize-at-build-time=clojure causes native-image to
 * eagerly initialize clojure.* classes in parallel, leading to circular
 * class init deadlocks (RT <-> fn/deftype classes).
 *
 * By forcing RT.init() in beforeAnalysis(), all core namespaces, fn classes,
 * and deftype classes are initialized sequentially on a single thread.
 * When analysis later discovers these classes, they're already initialized.
 */
public class ClojureFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        try {
            Class.forName("clojure.lang.RT");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
