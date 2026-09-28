import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Packages the shipped DOS metadata as the independently downloadable catalog. */
class BuildOnlineDosCatalog {
    private static final Path SOURCE = Path.of("kairodos/src/main/assets/catalog/dos");
    private static final Path OUTPUT = Path.of("catalog/online-v1.zip");

    private static List<String> names() {
        List<String> names = new ArrayList<>();
        for (int prefix = 0; prefix < 256; prefix++) names.add(String.format("%02x.json", prefix));
        names.add("folders.json");
        names.add("controller-profiles-v1.json");
        return names;
    }

    private static void check() throws IOException {
        try (ZipFile archive = new ZipFile(OUTPUT.toFile())) {
            List<String> names = names();
            if (archive.size() != names.size()) throw new IOException("Online catalog entry count differs");
            for (String name : names) {
                ZipEntry entry = archive.getEntry(name);
                if (entry == null) throw new IOException("Online catalog lacks " + name);
                try (InputStream input = archive.getInputStream(entry)) {
                    if (!Arrays.equals(input.readAllBytes(), Files.readAllBytes(SOURCE.resolve(name))))
                        throw new IOException("Online catalog differs from " + name);
                }
            }
        }
        System.out.println("Online DOS catalog matches bundled metadata");
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--check")) { check(); return; }
        if (args.length != 0) throw new IllegalArgumentException("Usage: java tools/BuildOnlineDosCatalog.java [--check]");
        Files.createDirectories(OUTPUT.getParent());
        Path temporary = Files.createTempFile(OUTPUT.getParent(), "online-v1-", ".zip");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                output.setLevel(9);
                for (String name : names()) {
                    ZipEntry entry = new ZipEntry(name);
                    entry.setTime(315532800000L); // ZIP's earliest date: 1980-01-01 UTC.
                    output.putNextEntry(entry);
                    Files.copy(SOURCE.resolve(name), output);
                    output.closeEntry();
                }
            }
            Files.move(temporary, OUTPUT, StandardCopyOption.REPLACE_EXISTING);
            check();
        } finally { Files.deleteIfExists(temporary); }
    }
}
