package com.estudotron.backend;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Sobe e derruba o processo do backend Python.
 *
 * <p>Porta livre aleatória, token de sessão aleatório (repassado por variável de ambiente)
 * e espera por /health antes de liberar a interface. O processo recebe
 * {@code --exit-on-stdin-eof}: se o JavaFX morrer sem chamar {@link #close()}, o pipe
 * de stdin fecha e o backend se encerra sozinho.
 *
 * <p>Configuração para desenvolvimento:
 * <ul>
 *   <li>{@code -Destudotron.backend.dir=...} (padrão: {@code ../backend})</li>
 *   <li>variável {@code ESTUDOTRON_PYTHON} (padrão: {@code .venv} do backend, senão {@code python})</li>
 * </ul>
 */
public final class BackendManager implements AutoCloseable {

    private static final String TOKEN_ENV = "ESTUDOTRON_TOKEN";
    private static final long START_TIMEOUT_MS = 30_000;

    private Process process;
    private ApiClient client;

    /** Inicia o backend e bloqueia até ele responder. Chamar fora da thread de UI. */
    public synchronized ApiClient start() throws IOException, InterruptedException {
        if (client != null) {
            return client;
        }
        Path backendDir = resolveBackendDir();
        int port = freePort();
        String token = newToken();

        ProcessBuilder pb = new ProcessBuilder(
                resolvePython(backendDir), "-m", "app",
                "--port", String.valueOf(port), "--exit-on-stdin-eof");
        pb.directory(backendDir.toFile());
        pb.environment().put(TOKEN_ENV, token);
        String dataDir = System.getProperty("estudotron.data.dir"); // isola o banco em testes
        if (dataDir != null && !dataDir.isBlank()) {
            pb.environment().put("ESTUDOTRON_DATA_DIR", dataDir);
        }
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        process = pb.start();
        ApiClient candidate = new ApiClient(port, token);

        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (!candidate.health()) {
            if (!process.isAlive()) {
                throw new IOException("O backend encerrou durante a inicialização (código "
                        + process.exitValue() + "). Veja o log no terminal.");
            }
            if (System.currentTimeMillis() > deadline) {
                close();
                throw new IOException("O backend não respondeu em " + START_TIMEOUT_MS / 1000 + " s");
            }
            Thread.sleep(200);
        }
        client = candidate;
        return client;
    }

    @Override
    public synchronized void close() {
        client = null;
        if (process == null) {
            return;
        }
        try {
            process.getOutputStream().close(); // dispara o watchdog de stdin do backend
        } catch (IOException ignored) {
            // processo já pode ter saído
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Path resolveBackendDir() throws IOException {
        String configured = System.getProperty("estudotron.backend.dir");
        Path dir = configured != null
                ? Path.of(configured)
                : Path.of(System.getProperty("user.dir")).resolve("..").resolve("backend");
        dir = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IOException("Pasta do backend não encontrada: " + dir
                    + " (use -Destudotron.backend.dir=...)");
        }
        return dir;
    }

    private static String resolvePython(Path backendDir) {
        String env = System.getenv("ESTUDOTRON_PYTHON");
        if (env != null && !env.isBlank()) {
            return env;
        }
        Path venv = backendDir.resolve(".venv").resolve("Scripts").resolve("python.exe");
        return Files.isRegularFile(venv) ? venv.toString() : "python";
    }
}
