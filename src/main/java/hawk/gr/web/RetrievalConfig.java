package hawk.gr.web;

import hawk.gr.RetrievalService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link RetrievalService} as a startup-time singleton bean.
 * <p>
 * The heavy model loading (DictEncoder AC automaton + BART ONNX + the two
 * JSON indexes) happens once here, at application startup, instead of per
 * request. {@code destroyMethod = "close"} releases the BART OrtSession when
 * the context shuts down.
 */
@Configuration
public class RetrievalConfig {

    @Bean(destroyMethod = "close")
    public RetrievalService retrievalService() throws Exception {
        return new RetrievalService();
    }
}
