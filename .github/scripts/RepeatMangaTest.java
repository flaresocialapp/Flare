import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;

public class RepeatMangaTest {
    public static void main(String[] args) throws Exception {
        var results = System.out;
        var quiet = new PrintStream(OutputStream.nullOutputStream());
        System.setOut(quiet);
        System.setErr(quiet);
        var tests = Class.forName("dev.dimension.flare.ui.presenter.manga.MangaReaderPresenterTest");
        int iterations = Integer.parseInt(args[1]);
        for (int i = 1; i <= iterations; i++) {
            if (i == 1 || i % 10 == 0) results.printf("Starting %d/%d%n", i, iterations);
            var request = args[0].equals("all") ? Request.aClass(tests) : Request.method(tests, args[0]);
            var result = new JUnitCore().run(request);
            if (!result.wasSuccessful()) {
                results.printf("Failure at iteration %d, tests=%d, failures=%d%n", i, result.getRunCount(), result.getFailureCount());
                for (var failure : result.getFailures()) {
                    results.println(failure.getTestHeader());
                    var error = failure.getException();
                    for (int depth = 0; depth < 5 && error != null; depth++, error = error.getCause()) {
                        var description = error.toString();
                        results.println(description.substring(0, Math.min(800, description.length())));
                        Arrays.stream(error.getStackTrace())
                            .filter(frame -> frame.getClassName().contains("MangaReaderPresenterTest"))
                            .limit(6)
                            .forEach(results::println);
                    }
                }
                System.exit(1);
            }
        }
        results.printf("Passed %d/%d%n", iterations, iterations);
    }
}
