package ai.mindconnect.file.manager.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

//@Service
@Slf4j
public class FileManagerWithLockService extends FileManagerService {

    @Value("${file-manager.base-directory}")
    private String basePath;

    public boolean deleteFile(String namespace, String path) throws ResponseStatusException {
        Path resolvedPath = getResolvedPath(namespace, path);
        File file = resolvedPath.toFile();

        if (!file.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Path not found");
        }

        // Using FileChannel and FileLock to ensure thread-safe delete operation
        //        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE);
        //                FileLock lock = channel.lock()) {
        try {
            boolean deleted = file.delete();
            return deleted;
        } catch (Exception e) {
            log.error("Failed to delete file: {}", file.getAbsolutePath(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to delete file"
            );
        }
    }

    public byte[] readFileContent(String namespace, String path) {
        log.info("readFileContent: namespace: {}, path: {}", namespace, path);
        Path resolvedPath = getResolvedPath(namespace, path);
        File file = resolvedPath.toFile();

        if (!file.exists()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Cannot read file from path" + path + " because not found"
            );
        }

        // Using FileChannel and FileLock to ensure thread-safe read operation
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
                FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {

            byte[] content = Files.readAllBytes(file.toPath());
            log.debug(
                    "readFileContent: namespace: {}, path: {}, file content: {}",
                    namespace,
                    path,
                    content
            );
            return content;
        } catch (IOException e) {
            log.error("Failed to read file content: {}", file.getAbsolutePath(), e);
            throw new RuntimeException("Failed to read file content", e);
        }
    }

    public Path createTextFile(String namespace, String path, CreateTextFile content)
            throws IOException {
        log.info("createTextFile: namespace: {}, path: {}", namespace, path);
        Path resolvedPath = getResolvedPath(namespace, path);
        createPathIfNotExists(resolvedPath);
        Path filePath = Path.of(resolvedPath.toFile().getAbsolutePath(), content.getFileName());

        // Using FileChannel and FileLock to ensure thread-safe write operation
        try (FileChannel channel = FileChannel.open(
                filePath,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.CREATE
        );
                FileLock lock = channel.lock()) {

            Files.writeString(filePath, content.getContent());
            log.info("createTextFile: wrote file {}", filePath);
            return filePath;
        } catch (IOException e) {
            log.error("Failed to write to file: {}", filePath, e);
            throw new IOException("Failed to write to file", e);
        }
    }
}

