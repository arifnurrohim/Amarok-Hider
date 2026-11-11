package deltazero.amarok.filehider;

import static java.nio.charset.StandardCharsets.UTF_8;
import static deltazero.amarok.filehider.BaseFileHider.ProcessMethod.HIDE;
import static deltazero.amarok.filehider.BaseFileHider.ProcessMethod.UNHIDE;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import deltazero.amarok.PrefMgr;
import deltazero.amarok.utils.FileHiderUtil;
import deltazero.amarok.utils.MediaStoreHelper;

public class ObfuscateFileHider extends BaseFileHider {
    private final static String TAG = "FileHider";

    private final static int MAX_PROCESS_WHOLE_FILE_SIZE_KB = 10 * 1024; // In KB.
    private final static int MAX_PROCESS_ENHANCED_WHOLE_FILE_SIZE_KB = 30 * 1024;
    private final static int BASE64_TAG = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
    private final static int MAX_FILENAME_BYTES = 255;
    private final static byte[] COMPRESSED_PAYLOAD_MAGIC = {0, 'Z'};
    private final static byte[] MAPPED_PAYLOAD_MAGIC = {0, 'M'};
    private final static String NAME_MAP_PREFS = "obfuscate_file_hider_name_map";

    public final static String FILENAME_NO_PROCESS_MARK = "!amk";
    public final static String FILENAME_FULL_PROCESS_MARK = "!amk1";
    public final static String FILENAME_HEADER_PROCESS_MARK = "!amk2";

    public boolean processHeader;
    public boolean processTextFile;
    public boolean processTextFileEnhanced;
    public boolean obfuscateFolderNames;

    public ObfuscateFileHider(Context context) {
        super(context);
        processHeader = PrefMgr.getEnableObfuscateFileHeader();
        processTextFile = PrefMgr.getEnableObfuscateTextFile();
        processTextFileEnhanced = PrefMgr.getEnableObfuscateTextFileEnhanced();
        obfuscateFolderNames = PrefMgr.getEnableObfuscateFolderName();
    }

    @Override
    protected void process(Set<String> targetDirs, ProcessMethod method) throws InterruptedException {
        List<TreeSnapshot> snapshots = new ArrayList<>();
        int total = 0;
        reportProgress(0, 0, PHASE_SCANNING);

        for (String dir : targetDirs) {
            if (isCancelled())
                throw new InterruptedException();

            try {
                TreeSnapshot snapshot = snapshotTree(Paths.get(dir));
                snapshots.add(snapshot);
                total += getUnitCount(snapshot, method);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                Log.w(TAG, String.format("Failed to scan %s: ", dir), e);
            }
        }

        int processed = 0;
        for (TreeSnapshot snapshot : snapshots) {
            processed = processSnapshot(snapshot, method, processed, total);
        }

        reportProgress(processed, total, PHASE_FINALIZING);

        // Refreshing the media store while hiding can make MediaProvider walk a
        // large folder that is in the middle of being renamed. Files are hidden
        // from MediaProvider by the rename itself; only refresh after unhide.
        if (method == ProcessMethod.UNHIDE)
            MediaStoreHelper.scan(context, targetDirs);
    }

    @Override
    public void tryToActive(ActivationCallbackListener activationCallbackListener) {
        activationCallbackListener.onActivateCallback(this.getClass(), true, 0);
    }

    @Override
    public String getName() {
        return "Obfuscate";
    }

    private TreeSnapshot snapshotTree(Path targetDir) throws IOException, InterruptedException {
        Log.i(TAG, "Scanning file tree: " + targetDir);
        TreeSnapshot snapshot = new TreeSnapshot();

        Files.walkFileTree(targetDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                if (isCancelled() || Thread.currentThread().isInterrupted()) {
                    snapshot.cancelled = true;
                    return FileVisitResult.TERMINATE;
                }

                if (path.getFileName().toString().equals(".nomedia"))
                    return FileVisitResult.CONTINUE;

                snapshot.files.add(path);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                Log.w(TAG, "Unable to visit " + file, exc);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null)
                    Log.w(TAG, "Unable to finish directory " + dir, exc);
                if (!dir.equals(targetDir))
                    snapshot.directories.add(dir);
                return FileVisitResult.CONTINUE;
            }
        });

        if (snapshot.cancelled) {
            Thread.interrupted();
            throw new InterruptedException();
        }
        return snapshot;
    }

    private int processSnapshot(TreeSnapshot snapshot, ProcessMethod method,
                                int processed, int total) throws InterruptedException {
        reportProgress(processed, total, PHASE_FILES);

        for (Path path : snapshot.files) {
            throwIfCancelled();

            // Check whether the whole file should be processed before renaming.
            boolean shouldProcessHeader = checkShouldProcessHeader(path, method);
            boolean shouldProcessWhole = checkShouldProcessWhole(path, method);

            String endingMark = FILENAME_NO_PROCESS_MARK;
            if (shouldProcessHeader)
                endingMark = FILENAME_HEADER_PROCESS_MARK;
            if (shouldProcessWhole)
                endingMark = FILENAME_FULL_PROCESS_MARK;

            Path newPath = processFilename(path, method, endingMark);

            if (newPath != null) {
                if (shouldProcessWhole) {
                    processWholeFile(newPath);
                } else if (shouldProcessHeader) {
                    processFileHeader(newPath);
                }
            }

            processed++;
            reportProgress(processed, total, PHASE_FILES);
            throttle(processed);
        }

        boolean shouldProcessDirs = method == ProcessMethod.UNHIDE || obfuscateFolderNames;
        if (!shouldProcessDirs)
            return processed;

        snapshot.directories.sort((first, second) ->
                Integer.compare(second.getNameCount(), first.getNameCount()));

        reportProgress(processed, total, PHASE_FOLDERS);
        for (Path dir : snapshot.directories) {
            throwIfCancelled();
            processFilename(dir, method, FILENAME_NO_PROCESS_MARK);
            processed++;
            reportProgress(processed, total, PHASE_FOLDERS);
            throttle(processed);
        }

        return processed;
    }

    private int getUnitCount(TreeSnapshot snapshot, ProcessMethod method) {
        int count = snapshot.files.size();
        if (method == ProcessMethod.UNHIDE || obfuscateFolderNames)
            count += snapshot.directories.size();
        return count;
    }

    private void throwIfCancelled() throws InterruptedException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
            throw new InterruptedException();
        }
    }

    private void throttle(int processed) throws InterruptedException {
        if (processed % 50 == 0)
            Thread.sleep(40);
        throwIfCancelled();
    }

    private static final class TreeSnapshot {
        final List<Path> files = new ArrayList<>();
        final List<Path> directories = new ArrayList<>();
        boolean cancelled;
    }

    /**
     * Process the Filename and check if the process succeeds.
     *
     * @param path            The path to be processed.
     * @param method          Process method.
     * @param extraEndingMark (only effective when `HIDE`) Extra mark to be append to the end of the filename.
     * @return If the process succeeds, return the new path. Otherwise, return null.
     */
    @Nullable
    private Path processFilename(Path path, ProcessMethod method, String extraEndingMark) {
        String filename = path.getFileName().toString();
        String newFilename = null;
        Path newPath;

        boolean hasEncoded = FileHiderUtil.checkIsMarkInFilename(filename);

        if (method == HIDE) {
            if (hasEncoded) {
                Log.d(TAG, "Found encoded name: " + filename + ", skip...");
                return null;
            }

            newFilename = encodeFilename(filename, extraEndingMark);
            if (newFilename == null) {
                Log.w(TAG, "Unable to encode filename: " + filename);
                return null;
            }
            Log.d(TAG, "Encode: " + path + " -> " + newFilename);

        } else if (method == UNHIDE) {
            if (!hasEncoded) {
                Log.w(TAG, "Found not coded name: " + filename + ", skip...");
                return null;
            }

            newFilename = decodeFilename(filename);
            if (newFilename == null) {
                Log.w(TAG, "Unable to decode: " + filename);
                return null;
            }

            Log.d(TAG, "Decode: " + path + " -> " + newFilename);
        }

        // Try to rename.

        assert newFilename != null;
        newPath = Paths.get(path.getParent().toString(), newFilename);

        try {
            boolean isSucceeded = path.toFile().renameTo(newPath.toFile());
            if (!isSucceeded) {
                Log.w(TAG, "Error when renaming file: " + path + " -> " + newPath);
                return null;
            }
            return newPath;
        } catch (SecurityException e) {
            Log.w(TAG, "Permission denied when renaming " + path, e);
            return null;
        }
    }

    @Nullable
    private String encodeFilename(String filename, String extraEndingMark) {
        byte[] rawFilename = filename.getBytes(UTF_8);

        // Keep the original format when the encoded name fits. This preserves
        // compatibility with files obfuscated by older versions.
        String encoded = "." + Base64.encodeToString(rawFilename, BASE64_TAG) + extraEndingMark;
        if (!isFilenameTooLong(encoded))
            return encoded;

        // Compress before encoding. Natural-language filenames usually fit now.
        byte[] compressed = compress(rawFilename);
        if (compressed != null) {
            encoded = "." + Base64.encodeToString(
                    concat(COMPRESSED_PAYLOAD_MAGIC, compressed), BASE64_TAG
            ) + extraEndingMark;
            if (!isFilenameTooLong(encoded))
                return encoded;
        }

        // Extremely long or incompressible names cannot fit in a single
        // filesystem filename. Use a short hash and remember the original name
        // in app-private storage so it can be restored on unhide.
        byte[] digest = sha256(rawFilename);
        String hashKey = Base64.encodeToString(digest, BASE64_TAG);
        encoded = "." + Base64.encodeToString(
                concat(MAPPED_PAYLOAD_MAGIC, digest), BASE64_TAG
        ) + extraEndingMark;
        if (isFilenameTooLong(encoded))
            return null;

        // The mapping must be durable before the file is renamed, otherwise a
        // crash or forced stop makes the original name impossible to restore.
        context.getSharedPreferences(NAME_MAP_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(hashKey, filename)
                .commit();
        return encoded;
    }

    @Nullable
    private String decodeFilename(String filename) {
        byte[] payload;
        try {
            payload = Base64.decode(FileHiderUtil.stripFilenameExtras(filename), BASE64_TAG);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Unable to decode: " + filename);
            return null;
        }

        if (startsWith(payload, COMPRESSED_PAYLOAD_MAGIC)) {
            byte[] decompressed = decompress(payload, COMPRESSED_PAYLOAD_MAGIC.length);
            return decompressed == null ? null : new String(decompressed, UTF_8);
        }

        if (startsWith(payload, MAPPED_PAYLOAD_MAGIC)) {
            if (payload.length != MAPPED_PAYLOAD_MAGIC.length + 32)
                return null;
            byte[] digest = Arrays.copyOfRange(
                    payload, MAPPED_PAYLOAD_MAGIC.length, payload.length
            );
            String hashKey = Base64.encodeToString(digest, BASE64_TAG);
            return context.getSharedPreferences(NAME_MAP_PREFS, Context.MODE_PRIVATE)
                    .getString(hashKey, null);
        }

        return new String(payload, UTF_8);
    }

    private static boolean isFilenameTooLong(String filename) {
        return filename.getBytes(UTF_8).length > MAX_FILENAME_BYTES;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length)
            return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i])
                return false;
        }
        return true;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    @Nullable
    private static byte[] compress(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(input);
            deflater.finish();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to compress filename", e);
            return null;
        } finally {
            deflater.end();
        }
    }

    @Nullable
    private static byte[] decompress(byte[] input, int offset) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input, offset, input.length - offset);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary()))
                    return null;
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (DataFormatException | RuntimeException e) {
            Log.w(TAG, "Unable to decompress filename", e);
            return null;
        } finally {
            inflater.end();
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void processFileHeader(Path path) {
        Log.d(TAG, "Processing file header: " + path);

        try {

            File file = path.toFile();

            // Preserve original lastModified time
            var lastModified = path.toFile().lastModified();

            try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {

                byte[] bytes = new byte[8];
                int numBytesRead = randomAccessFile.read(bytes);
                int numBytesToReplace = Math.max(Math.min(numBytesRead, 8), 0);

                for (int i = 0; i < numBytesToReplace; i++) {
                    bytes[i] = (byte) ~bytes[i];
                }

                randomAccessFile.seek(0);
                randomAccessFile.write(bytes, 0, numBytesToReplace);

            } catch (IOException e) {
                Log.w(TAG, "processFileHeader failed: ", e);
            }

            // noinspection ResultOfMethodCallIgnored
            file.setLastModified(lastModified);

        } catch (SecurityException e) {
            Log.w(TAG, "processFileHeader failed: ", e);
        }
    }

    private void processWholeFile(Path path) {
        Log.d(TAG, "Processing whole file: " + path);

        byte[] buffer = new byte[1024];
        long numReadLoops = 0;
        int numBytesRead, numBytesToReplace;

        try {

            File file = path.toFile();

            // Preserve original lastModified time
            var lastModified = path.toFile().lastModified();

            try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
                while ((numBytesRead = randomAccessFile.read(buffer)) != -1) {

                    numBytesToReplace = Math.max(Math.min(numBytesRead, buffer.length), 0);

                    for (int i = 0; i < numBytesToReplace; i++) {
                        buffer[i] = (byte) ~buffer[i];
                    }

                    randomAccessFile.seek(numReadLoops * buffer.length);
                    randomAccessFile.write(buffer, 0, numBytesToReplace);

                    numReadLoops++;
                }
            } catch (IOException e) {
                Log.w(TAG, "processWholeFile failed: ", e);
            }

            // noinspection ResultOfMethodCallIgnored
            file.setLastModified(lastModified);

        } catch (SecurityException e) {
            Log.w(TAG, "processWholeFile failed: ", e);
        }
    }

    private boolean checkShouldProcessWhole(Path path, ProcessMethod method) {

        if ((!processHeader) || (!processTextFile)) {
            return false;
        }

        String filename = path.getFileName().toString();
        if (method == HIDE) {

            if (processTextFileEnhanced) {
                return FileHiderUtil.checkIsTextFileEnhanced(path)
                        && FileHiderUtil.getFileSizeKB(path) <= MAX_PROCESS_WHOLE_FILE_SIZE_KB;
            } else { // Not enhanced
                return FileHiderUtil.checkIsTextFile(filename)
                        && FileHiderUtil.getFileSizeKB(path) <= MAX_PROCESS_ENHANCED_WHOLE_FILE_SIZE_KB;
            }

        } else { // UNHIDE
            return filename.endsWith(FILENAME_FULL_PROCESS_MARK);
        }
    }

    private boolean checkShouldProcessHeader(Path path, ProcessMethod method) {
        String filename = path.getFileName().toString();
        if (method == HIDE) return processHeader;
        else return filename.endsWith(FILENAME_HEADER_PROCESS_MARK);
    }
}
