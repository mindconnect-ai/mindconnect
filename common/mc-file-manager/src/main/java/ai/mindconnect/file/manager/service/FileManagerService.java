package ai.mindconnect.file.manager.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

@Setter @Getter
@Service
@Slf4j
public class FileManagerService {

    @Value("${mindconnect.file-manager.base-directory}")
    private String basePath;

    public boolean deleteFile(
            String namespace,
            String path
    ) throws ResponseStatusException {
        Path resolvedPath = getResolvedPath(namespace, path);
        File file = resolvedPath.toFile();

        if (!file.exists()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "File:" + path + " not found. Could not be deleted"
            );
        } else {
            boolean deleted = file.delete();
            if (deleted) {
                return true;
            } else {
                return false;
            }
        }
    }

    public List<FileInfo> listFiles(
            String namespace,
            String path,
            String filterName,
            String sortDirection
    ) {
        return listFiles(namespace, path, (f) -> filterName == null ?
                        true :
                        (filterName.trim().startsWith(f.name) ? true : false),
                sortDirection,
                true
        );
    }

    public List<FileInfo> listFiles(
            String namespace,
            String path,
            Predicate<FileInfo> filter,
            String sortDirection,
            boolean ignoreSilentlyNonExistingDir
    ) {
        log.info("listFiles: namespace: {}, path: {}", namespace, path);
        Path resolvedPath = getResolvedPath(namespace, path);
        File file = resolvedPath.toFile();

        if (!file.exists()) {
            if (ignoreSilentlyNonExistingDir) {
                return List.of();
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Path " + path + " not found");
        }
        log.info("listFiles: namespace: {}, path: {} exists!", namespace, path);
        if (file.isDirectory()) {
            log.info("listFiles: namespace: {}, path: {} is a directory", namespace, path);
            return listFiles(
                    path,
                    file,
                    filter,
                    sortDirection == null ?
                            SortDirection.ASC :
                            SortDirection.fromString(sortDirection)

            );
        }
        return null;
    }

    public byte[] readFileContent(
            String namespace,
            String path
    ) {
        log.info("readFileContent: namespace: {}, path: {}", namespace, path);
        Path resolvedPath = getResolvedPath(namespace, path);
        File file = resolvedPath.toFile();

        if (!file.exists()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Path not found:" + path + " was resolved to:" + resolvedPath
            );
        }
        log.info("readFileContent: namespace: {}, path: {} exists!", namespace, path);
        byte[] content = readFileContent(file);
        log.debug(
                "readFileContent: namespace: {}, path: {}, file content:",
                namespace,
                path,
                content
        );
        return content;
    }

    public boolean fileExists(
            String namespace,
            String path
    ) {
        log.info("fileExists: namespace: {}, path: {}", namespace, path);
        Path resolvedPath = getResolvedPath(namespace, path);
        File file = resolvedPath.toFile();
        return file.exists();
    }

    public Path getResolvedPath(String namespace, String path) {
        Path baseDir = getBaseDir(namespace);

        Path resolvedPath = baseDir;
        if (path != null && !path.isBlank()) {
            resolvedPath = Path.of(basePath, namespace, PathSanitizer.sanitizePath(path));
        }
        return resolvedPath;
    }

    public Path createDirectory(
            String namespace,
            String path
    ) {
        log.info("createDirectory: namespace: {}, path: {}", namespace, path);
        Path resolvedPath = getResolvedPath(namespace, path);
        createPathIfNotExists(resolvedPath);

        return resolvedPath;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateTextFile {
        String fileName;
        String content;
    }

    public Path createTextFile(
            String namespace,
            String path,
            CreateTextFile content
    ) throws IOException {
        log.info("createTextFile: namespace: {}, path: {}", namespace, path);
        Path resolvedPath = getResolvedPath(namespace, path);
        createPathIfNotExists(resolvedPath);
        Path filePath = Path.of(resolvedPath.toFile().getAbsolutePath(), content.getFileName());

        Files.writeString(filePath, content.getContent());
        log.info("createTextFile: wrote file {}", filePath);
        return filePath;
    }

    public static void createPathIfNotExists(Path resolvedPath) {
        if (!resolvedPath.toFile().exists()) {
            resolvedPath.toFile().mkdirs();
        }
    }

    public Path getBaseDir(String namespace) {
        var baseDir = Path.of(basePath, PathSanitizer.sanitizePath(namespace));
        createPathIfNotExists(baseDir);
        return baseDir;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FileInfo {
        private String name;
        private String type;
        private String directory;

        @JsonIgnore
        public String getFilePath() {
            return directory + "/" + name;
        }
    }

    private List<FileInfo> listFiles(
            String relativeDiretory,
            File absoluteDirectory,
            Predicate<FileInfo> predicate,
            SortDirection direction
    ) {
        List<FileInfo> response = new ArrayList<>();
        File[] files = absoluteDirectory.listFiles();

        if (files != null) {
            for (File file : files) {
                var fileInfo = new FileInfo(
                        file.getName(),
                        file.isDirectory() ? "directory" : "file",
                        relativeDiretory
                );
                if (predicate.test(fileInfo)) {
                    response.add(fileInfo);
                }
            }
        }
        response.sort((a, b) -> direction == SortDirection.ASC ?
                a.name.compareTo(b.name) :
                b.name.compareTo(a.name));
        return response;
    }

    public FileInfo upload(
            String namespace,
            String directory,
            MultipartFile file
    ) throws ResponseStatusException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload file because it is empty."
            );
        }

        try {
            // Save the file to a directory on the server
            File uploadDir = new File(getBasePath() + "/" + namespace + "/"
                    + PathSanitizer.sanitizePath(directory));
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            File uploadedFile = new File(uploadDir + "/" + file.getOriginalFilename());
            file.transferTo(uploadedFile);

            return new FileInfo(file.getName(), file.getContentType(), directory);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to upload file: " + e.getMessage()
            );
        }
    }

    private byte[] readFileContent(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file content", e);
        }
    }
}
