package hawk.gr.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web entry point for the Hawk-GR search page.
 * <p>
 * Scans only the {@code hawk.gr.web} package so the existing
 * {@code BartONNXInference} CLI (which is also {@code @SpringBootApplication}
 * and implements {@code CommandLineRunner}) is NOT picked up as a bean and
 * its blocking Scanner loop never runs at startup.
 *
 * <p>Run from the project root (relative resource paths):
 * <pre>./run.sh hawk.gr.web.Application</pre>
 */
@SpringBootApplication(scanBasePackages = "hawk.gr.web")
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
