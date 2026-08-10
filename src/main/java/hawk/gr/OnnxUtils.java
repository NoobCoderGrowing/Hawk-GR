package hawk.gr;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * ONNX Runtime utilities, including GPU/CPU auto-detection.
 * <p>
 * Tries CUDA first; falls back to CPU if no GPU or CUDA libraries are unavailable.
 * Pre-loads bundled CUDA 12 runtime libraries from {@code lib/cuda12/} so that
 * the application works without setting {@code LD_LIBRARY_PATH} manually.
 */
public final class OnnxUtils {

    private static volatile Boolean cudaAvailable = null;
    private static volatile boolean cudaLibsLoaded = false;

    private OnnxUtils() {}

    /**
     * Pre-load CUDA 12 runtime libraries from the project's bundled {@code lib/cuda12/}
     * directory. Call this once before any ONNX session creation.
     * <p>
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    public static synchronized void preloadCudaLibs() {
        if (cudaLibsLoaded) return;
        cudaLibsLoaded = true;

        Path cudaDir = findCudaLibDir();
        if (cudaDir == null) {
            System.out.println("[OnnxUtils] No bundled CUDA libs found at lib/cuda12/");
            return;
        }

        try {
            // Load cudart first (base runtime), then the rest
            List<Path> libs = new ArrayList<>();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(cudaDir)) {
                for (Path dir : dirs) {
                    if (Files.isDirectory(dir)) {
                        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.so*")) {
                            for (Path f : files) {
                                libs.add(f);
                            }
                        }
                    }
                }
            }
            if (libs.isEmpty()) return;

            // Sort: cudart first, then any order
            libs.sort((a, b) -> {
                boolean aRt = a.getFileName().toString().startsWith("libcudart");
                boolean bRt = b.getFileName().toString().startsWith("libcudart");
                if (aRt && !bRt) return -1;
                if (!aRt && bRt) return 1;
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            });

            for (Path lib : libs) {
                try {
                    System.load(lib.toAbsolutePath().toString());
                } catch (UnsatisfiedLinkError e) {
                    // Some libs may have unsatisfied dependencies — skip and retry later
                }
            }
            // Second pass to catch remaining
            for (Path lib : libs) {
                try {
                    System.load(lib.toAbsolutePath().toString());
                } catch (UnsatisfiedLinkError ignored) {
                }
            }
            System.out.println("[OnnxUtils] Pre-loaded CUDA libs from " + cudaDir);
        } catch (IOException e) {
            System.err.println("[OnnxUtils] Failed to scan CUDA lib dir: " + e.getMessage());
        }
    }

    private static Path findCudaLibDir() {
        // Try project root/lib/cuda12 first
        Path[] candidates = {
            Paths.get("lib/cuda12"),
            Paths.get("../lib/cuda12"),
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p.toAbsolutePath();
        }
        // Try relative to class location (jar or target/classes)
        try {
            Path classesDir = Paths.get("target/classes");
            if (Files.isDirectory(classesDir)) {
                Path relative = classesDir.getParent().resolve("lib/cuda12");
                if (Files.isDirectory(relative)) return relative.toAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Create session options with GPU (CUDA) if available, otherwise CPU.
     * Uses device 0 if CUDA is present.
     */
    public static OrtSession.SessionOptions createSessionOptions() throws OrtException {
        preloadCudaLibs();  // ensure CUDA libs are loaded

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
