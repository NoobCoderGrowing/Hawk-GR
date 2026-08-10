package hawk.gr;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * ONNX Runtime utilities, including GPU/CPU auto-detection.
 * <p>
 * Tries CUDA first; falls back to CPU if no GPU or CUDA libraries are unavailable.
 */
public final class OnnxUtils {

    private static volatile Boolean cudaAvailable = null;

    private OnnxUtils() {}

    /**
     * Create session options with GPU (CUDA) if available, otherwise CPU.
     * Uses device 0 if CUDA is present.
     */
    public static OrtSession.SessionOptions createSessionOptions() throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);

        if (isCudaAvailable()) {
            try {
                opts.addCUDA(0);  // GPU device 0
                System.out.println("[OnnxUtils] Using CUDA GPU (device 0)");
            } catch (OrtException e) {
                System.err.println("[OnnxUtils] CUDA init failed: " + e.getMessage());
                System.err.println("[OnnxUtils] Falling back to CPU");
                cudaAvailable = false;
            }
        } else {
            System.out.println("[OnnxUtils] CUDA not available, using CPU");
        }
        return opts;
    }

    /**
     * Check whether CUDA execution provider is available at runtime.
     * Cached after first detection.
     */
    public static boolean isCudaAvailable() {
        if (cudaAvailable == null) {
            synchronized (OnnxUtils.class) {
                if (cudaAvailable == null) {
                    cudaAvailable = detectCuda();
                }
            }
        }
        return cudaAvailable;
    }

    private static boolean detectCuda() {
        // Try creating a temporary session with CUDA to test availability
        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.addCUDA(0);
            // If we get here without exception, CUDA is available
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reset the CUDA detection cache (useful for testing).
     */
    public static void resetCudaDetection() {
        cudaAvailable = null;
    }
}
