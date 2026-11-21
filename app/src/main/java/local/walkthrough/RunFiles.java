package local.walkthrough;

import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.*;

final class RunFiles {
    static final ExecutorService io = Executors.newSingleThreadExecutor();

    static File create(Context context) throws IOException {
        File base = new File(context.getFilesDir(), "runs");
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Cannot create capture storage.");
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        return Files.createTempDirectory(base.toPath(), stamp + "_").toFile();
    }

    static File[] sessions(Context context) {
        File[] dirs = new File(context.getFilesDir(), "runs").listFiles(File::isDirectory);
        if (dirs == null) return new File[0];
        Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
        return dirs;
    }

    static File archive(File dir) { return new File(dir.getParentFile(), dir.getName() + ".zip"); }

    static void registerVideo(File dir) throws IOException {
        File marker = new File(dir, "video-session");
        if (!marker.createNewFile() && !marker.isFile()) {
            throw new IOException("Could not register this video session.");
        }
    }

    static boolean isVideo(File dir) { return new File(dir, "video-session").isFile(); }

    static File video(File dir) { return new File(dir, "walkthrough.mp4"); }

    static void packageRun(File dir) {
        packageRun(dir, null);
    }

    static void packageRun(File dir, Consumer<Boolean> onPackaged) {
        if (isVideo(dir)) {
            if (onPackaged != null) onPackaged.accept(false);
            return;
        }
        io.execute(() -> {
            boolean packaged = false;
            try { build(dir); packaged = true; }
            catch (IOException | RuntimeException e) {
                try (FileWriter out = new FileWriter(new File(dir, "archive-error.txt"))) {
                    out.write("Could not create ZIP: " + e.getMessage());
                } catch (IOException | RuntimeException ignored) { /* Activity can retry to the selected destination. */ }
            } finally {
                if (onPackaged != null) onPackaged.accept(packaged);
            }
        });
    }

    static File build(File dir) throws IOException {
        if (isVideo(dir)) throw new IOException("Save this video session as an MP4.");
        File result = archive(dir);
        if (result.isFile()) return result;
        File part = new File(dir.getParentFile(), dir.getName() + ".zip.tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part)))) {
                zip.setLevel(1);
                add(dir, dir, zip);
                if (!new File(dir, "status.txt").isFile()) {
                    zip.putNextEntry(new ZipEntry("status.txt"));
                    zip.write("Incomplete. The run ended before the walker wrote its final status.\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            Files.move(part.toPath(), result.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part.toPath());
        }
        return result;
    }

    private static void add(File root, File folder, ZipOutputStream zip) throws IOException {
        File[] children = folder.listFiles();
        if (children == null) throw new IOException("Cannot read session files.");
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File f : children) {
            if (f.isDirectory()) { add(root, f, zip); continue; }
            String name = f.getName();
            if (!(name.endsWith(".png") || name.endsWith(".mp4") || name.equals("status.txt"))) continue;
            zip.putNextEntry(new ZipEntry(root.toPath().relativize(f.toPath()).toString()));
            Files.copy(f.toPath(), zip);
            zip.closeEntry();
        }
    }
    private RunFiles() {}
}
