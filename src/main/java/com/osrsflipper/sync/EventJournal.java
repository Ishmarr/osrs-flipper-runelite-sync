package com.osrsflipper.sync;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, ordered storage for immutable GE events. An event is published only
 * after its temporary file has been forced to disk and atomically renamed.
 * Unacknowledged files survive restart; a crash before acknowledgement can
 * replay the same event ID, which the Worker must handle idempotently.
 *
 * The caller supplies the complete owner/endpoint/device/RuneScape context.
 * Only its hash is used in paths. Retry counters belong to the caller and must
 * not be included in the immutable event JSON.
 */
final class EventJournal
{
    static final int MAX_HEAD_ENTRIES = 500;
    private static final int FORMAT_VERSION = 1;
    private static final Pattern EVENT_FILE = Pattern.compile("([0-9]{20})_([0-9a-f]{64})\\.json");
    private static final Gson GSON = new Gson();
    private final Path directory;

    EventJournal(Path root, String contextKey) throws IOException
    {
        if (root == null || contextKey == null || contextKey.trim().isEmpty())
        {
            throw new IllegalArgumentException("A journal root and complete context are required");
        }
        directory = root.toAbsolutePath().normalize().resolve(hash(contextKey));
        Files.createDirectories(directory);
    }

    synchronized void append(String eventId, String eventJson) throws IOException
    {
        validate(eventId, eventJson);
        try (LockedDirectory ignored = lock())
        {
            appendLocked(eventId, eventJson);
        }
    }

    private void appendLocked(String eventId, String eventJson) throws IOException
    {
        validate(eventId, eventJson);
        long maximumSequence = 0;
        String eventHash = hash(eventId);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json"))
        {
            for (Path file : files)
            {
                Matcher name = eventFile(file);
                maximumSequence = Math.max(maximumSequence, sequence(name, file));
                if (eventHash.equals(name.group(2)))
                {
                    Entry existing = read(file);
                    if (!eventId.equals(existing.eventId) || !eventJson.equals(existing.eventJson))
                    {
                        throw new IOException("Event ID already has a different immutable payload");
                    }
                    return;
                }
            }
        }
        if (maximumSequence == Long.MAX_VALUE)
        {
            throw new IOException("Event journal sequence exhausted");
        }
        Record record = new Record();
        record.version = FORMAT_VERSION;
        record.eventId = eventId;
        record.eventJson = eventJson;
        record.payloadHash = hash(eventJson);
        Path destination = directory.resolve(String.format(Locale.ROOT, "%020d_%s.json",
            maximumSequence + 1, eventHash));
        writeAtomic(destination, GSON.toJson(record));
    }

    /** No dispatch is allowed until this entire migration has committed. */
    synchronized boolean legacyImported() throws IOException
    {
        return legacyImported("default");
    }

    synchronized boolean legacyImported(String sourceKey) throws IOException
    {
        try (LockedDirectory ignored = lock())
        {
            return Files.exists(legacyMarker(sourceKey));
        }
    }

    synchronized void importLegacy(List<Entry> entries, String stateJson) throws IOException
    {
        importLegacy(entries, stateJson, "default");
    }

    synchronized void importLegacy(List<Entry> entries, String stateJson, String sourceKey) throws IOException
    {
        try (LockedDirectory ignored = lock())
        {
            Path marker = legacyMarker(sourceKey);
            if (Files.exists(marker))
            {
                return;
            }
            for (Entry entry : entries)
            {
                appendLocked(entry.eventId, entry.eventJson);
            }
            if (stateJson != null && !Files.exists(directory.resolve(".account-state")))
            {
                writeAtomic(directory.resolve(".account-state"), stateJson);
            }
            writeAtomic(marker, "1");
        }
    }

    private Path legacyMarker(String sourceKey)
    {
        return directory.resolve(".legacy-imported-" + hash(sourceKey));
    }

    synchronized String readState() throws IOException
    {
        try (LockedDirectory ignored = lock())
        {
            Path state = directory.resolve(".account-state");
            return Files.exists(state) ? Files.readString(state, StandardCharsets.UTF_8) : null;
        }
    }

    synchronized void writeState(String stateJson) throws IOException
    {
        try (LockedDirectory ignored = lock())
        {
            writeAtomic(directory.resolve(".account-state"), stateJson);
        }
    }

    /** Bind pre-upgrade configuration once, before a pairing can change owner. */
    static String claimLegacyConnection(Path root, String proposed) throws IOException
    {
        return claimLegacyConnection(root, "default", proposed);
    }

    static String claimLegacyConnection(Path root, String profileKey, String proposed) throws IOException
    {
        // A separate journal supplies the same cross-process lock and atomic writes.
        EventJournal binding = new EventJournal(root, "legacy-connection-binding-v1:" + profileKey);
        synchronized (binding)
        {
            try (LockedDirectory ignored = binding.lock())
            {
                Path marker = binding.directory.resolve(".connection");
                if (!Files.exists(marker))
                {
                    writeAtomic(marker, proposed);
                }
                return Files.readString(marker, StandardCharsets.UTF_8);
            }
        }
    }

    private static void writeAtomic(Path destination, String value) throws IOException
    {
        Path temporary = Files.createTempFile(destination.getParent(), ".state-", ".tmp");
        try
        {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                ByteBuffer buffer = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining())
                {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized List<Entry> readHead(int limit) throws IOException
    {
        if (limit < 0)
        {
            throw new IllegalArgumentException("Head size cannot be negative");
        }
        if (limit == 0)
        {
            return Collections.emptyList();
        }
        try (LockedDirectory ignored = lock())
        {
            List<Entry> result = new ArrayList<>();
            for (Path file : headFiles(Math.min(limit, MAX_HEAD_ENTRIES)))
            {
                result.add(read(file));
            }
            return Collections.unmodifiableList(result);
        }
    }

    synchronized boolean acknowledge(String expectedHeadId) throws IOException
    {
        if (expectedHeadId == null || expectedHeadId.isEmpty())
        {
            return false;
        }
        try (LockedDirectory ignored = lock())
        {
            List<Path> head = headFiles(1);
            if (head.isEmpty() || !expectedHeadId.equals(read(head.get(0)).eventId))
            {
                return false;
            }
            Files.delete(head.get(0));
            return true;
        }
    }

    synchronized long size() throws IOException
    {
        try (LockedDirectory ignored = lock();
             DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json"))
        {
            long count = 0;
            for (Path file : files)
            {
                eventFile(file);
                count++;
            }
            return count;
        }
    }

    synchronized boolean isEmpty() throws IOException
    {
        try (LockedDirectory ignored = lock())
        {
            return headFiles(1).isEmpty();
        }
    }

    private List<Path> headFiles(int limit) throws IOException
    {
        Comparator<Path> order = Comparator.comparing(file -> file.getFileName().toString());
        PriorityQueue<Path> head = new PriorityQueue<>(limit, order.reversed());
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json"))
        {
            for (Path file : files)
            {
                eventFile(file);
                head.add(file);
                if (head.size() > limit)
                {
                    head.poll();
                }
            }
        }
        List<Path> ordered = new ArrayList<>(head);
        ordered.sort(order);
        return ordered;
    }

    private Entry read(Path file) throws IOException
    {
        Matcher filename = eventFile(file);
        try
        {
            Record record = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Record.class);
            if (record == null || record.version != FORMAT_VERSION)
            {
                throw new IllegalArgumentException("Unknown event journal format");
            }
            validate(record.eventId, record.eventJson);
            if (!filename.group(2).equals(hash(record.eventId)) ||
                !hash(record.eventJson).equals(record.payloadHash))
            {
                throw new IllegalArgumentException("Event journal checksum mismatch");
            }
            return new Entry(record.eventId, record.eventJson);
        }
        catch (RuntimeException exception)
        {
            throw new IOException("Unreadable event journal entry: " + file.getFileName(), exception);
        }
    }

    private static Matcher eventFile(Path file) throws IOException
    {
        Matcher matcher = EVENT_FILE.matcher(file.getFileName().toString());
        if (!matcher.matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("Invalid event journal file: " + file.getFileName());
        }
        sequence(matcher, file);
        return matcher;
    }

    private static long sequence(Matcher filename, Path file) throws IOException
    {
        try
        {
            long value = Long.parseLong(filename.group(1));
            if (value <= 0)
            {
                throw new NumberFormatException("Nonpositive sequence");
            }
            return value;
        }
        catch (NumberFormatException exception)
        {
            throw new IOException("Invalid event journal sequence: " + file.getFileName(), exception);
        }
    }

    private static void validate(String eventId, String eventJson)
    {
        if (eventId == null || eventId.trim().isEmpty() || eventJson == null ||
            !new JsonParser().parse(eventJson).isJsonObject())
        {
            throw new IllegalArgumentException("An event ID and JSON object are required");
        }
    }

    private static String hash(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte part : digest)
            {
                result.append(Character.forDigit((part >>> 4) & 15, 16));
                result.append(Character.forDigit(part & 15, 16));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }

    private LockedDirectory lock() throws IOException
    {
        FileChannel channel = FileChannel.open(directory.resolve(".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try
        {
            FileLock lock = channel.tryLock();
            if (lock == null)
            {
                throw new IOException("Event journal is in use by another client");
            }
            return new LockedDirectory(channel, lock);
        }
        catch (IOException | OverlappingFileLockException exception)
        {
            channel.close();
            throw new IOException("Could not lock the event journal", exception);
        }
    }

    static final class Entry
    {
        final String eventId;
        final String eventJson;

        Entry(String eventId, String eventJson)
        {
            this.eventId = eventId;
            this.eventJson = eventJson;
        }
    }

    private static final class Record
    {
        int version;
        String eventId;
        String eventJson;
        String payloadHash;
    }

    private static final class LockedDirectory implements AutoCloseable
    {
        private final FileChannel channel;
        private final FileLock lock;

        private LockedDirectory(FileChannel channel, FileLock lock)
        {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException
        {
            try
            {
                lock.release();
            }
            finally
            {
                channel.close();
            }
        }
    }
}
