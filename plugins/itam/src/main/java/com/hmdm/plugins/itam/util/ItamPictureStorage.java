package com.hmdm.plugins.itam.util;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Writes/deletes ITAM picture uploads under &lt;files.directory&gt;/&lt;customer files dir&gt;/itam/.
 * Stored picture references are paths relative to files.directory (i.e. already including the
 * customer's dir), so the background purge job can delete them without a logged-in user/SecurityContext.
 */
@Singleton
public class ItamPictureStorage {

    private static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    private final String filesDirectory;
    private final CustomerDAO customerDAO;

    @Inject
    public ItamPictureStorage(@Named("files.directory") String filesDirectory, CustomerDAO customerDAO) {
        this.filesDirectory = filesDirectory;
        this.customerDAO = customerDAO;
    }

    /**
     * @param baseName the desired file name, without extension (e.g. "LMWH1-000_2007_114836", or with a
     *                 "_2", "_3"... suffix already appended by the caller for additional pictures in the
     *                 same entry). The original file's extension is preserved; a numeric suffix is added
     *                 automatically if a file with that exact name already exists on disk.
     * @return the path (relative to files.directory) to store in the DB, or null if the upload was rejected.
     */
    public String save(int customerId, InputStream content, String originalFileName, String contentType, String baseName) throws IOException {
        if (!isAllowedType(contentType, originalFileName)) {
            throw new IOException("Unsupported picture type: " + contentType);
        }
        Customer customer = customerDAO.findById(customerId);
        String customerDir = (customer != null && customer.getFilesDir() != null) ? customer.getFilesDir() : "";

        String safeBaseName = FileUtil.adjustFileName(baseName);
        String extension = extensionOf(originalFileName, contentType);
        String dirPath = (customerDir.isEmpty() ? "" : customerDir + File.separator) + "itam";

        String storedName = safeBaseName + extension;
        File target = new File(this.filesDirectory, dirPath + File.separator + storedName);
        int collisionSuffix = 2;
        while (target.exists()) {
            storedName = safeBaseName + "_" + collisionSuffix + extension;
            target = new File(this.filesDirectory, dirPath + File.separator + storedName);
            collisionSuffix++;
        }
        String relativePath = dirPath + File.separator + storedName;

        if (!FileUtil.isSafePath(target.getPath())) {
            throw new IOException("Invalid picture path");
        }
        File dir = target.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create ITAM picture directory: " + dir);
        }

        long size = Files.copy(content, target.toPath());
        if (size > MAX_SIZE_BYTES) {
            target.delete();
            throw new IOException("Picture exceeds 5MB limit: " + originalFileName);
        }

        return relativePath;
    }

    private static String extensionOf(String originalFileName, String contentType) {
        String name = originalFileName == null ? "" : originalFileName.toLowerCase();
        if (name.endsWith(".jpeg")) {
            return ".jpeg";
        }
        if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp")) {
            return name.substring(name.lastIndexOf('.'));
        }
        String type = (contentType != null ? contentType : "").toLowerCase();
        if (type.equals("image/png")) {
            return ".png";
        }
        if (type.equals("image/webp")) {
            return ".webp";
        }
        return ".jpg";
    }

    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return;
        }
        File file = new File(this.filesDirectory, relativePath);
        if (FileUtil.isSafePath(file.getPath())) {
            file.delete();
        }
    }

    private static boolean isAllowedType(String contentType, String fileName) {
        String lower = (contentType != null ? contentType : "").toLowerCase();
        if (lower.equals("image/jpeg") || lower.equals("image/png") || lower.equals("image/webp")) {
            return true;
        }
        String name = fileName == null ? "" : fileName.toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp");
    }
}
