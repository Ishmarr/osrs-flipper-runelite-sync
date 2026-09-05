package com.osrsflipper.sync;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EventJournalTest
{
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void moreThanFiveHundredEventsSurviveRestartInTheirOriginalOrder() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        EventJournal original = new EventJournal(root, "endpoint|owner|device|account");
        for (int index = 0; index < 507; index++)
        {
            original.append("event-" + index, payload(index));
        }

        EventJournal restored = new EventJournal(root, "endpoint|owner|device|account");
        assertEquals(507, restored.size());
        List<EventJournal.Entry> first = restored.readHead(Integer.MAX_VALUE);
        assertEquals(500, first.size());
        assertEquals("event-0", first.get(0).eventId);
        assertEquals("event-499", first.get(499).eventId);
        assertEquals(payload(499), first.get(499).eventJson);

        for (int index = 0; index < 8; index++)
        {
            assertTrue(restored.acknowledge("event-" + index));
        }
        List<EventJournal.Entry> next = restored.readHead(500);
        assertEquals(499, next.size());
        for (int index = 0; index < next.size(); index++)
        {
            assertEquals("event-" + (index + 8), next.get(index).eventId);
            assertEquals(payload(index + 8), next.get(index).eventJson);
        }
    }

    @Test
    public void migrationCanResumeAfterRestartWithoutDuplicatingOrReorderingEvents() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        EventJournal beforeCrash = new EventJournal(root, "context");
        beforeCrash.append("first", payload(1));
        beforeCrash.append("second", payload(2));

        EventJournal resumedMigration = new EventJournal(root, "context");
        resumedMigration.append("first", payload(1));
        resumedMigration.append("second", payload(2));
        resumedMigration.append("third", payload(3));
        assertEquals(3, resumedMigration.size());
        assertEquals("first", resumedMigration.readHead(1).get(0).eventId);
        assertTrue(resumedMigration.acknowledge("first"));

        EventJournal afterAck = new EventJournal(root, "context");
        assertEquals("second", afterAck.readHead(1).get(0).eventId);
        assertEquals(2, afterAck.size());
    }

    @Test
    public void onlyTheExactCurrentHeadCanBeAcknowledged() throws Exception
    {
        EventJournal journal = new EventJournal(temporary.newFolder().toPath(), "context");
        journal.append("first", payload(1));
        journal.append("second", payload(2));
        assertFalse(journal.acknowledge("second"));
        assertFalse(journal.acknowledge(null));
        assertFalse(journal.acknowledge("unknown"));
        assertEquals(2, journal.size());

        assertTrue(journal.acknowledge("first"));
        assertFalse(journal.acknowledge("first"));
        assertEquals("second", journal.readHead(1).get(0).eventId);
        assertTrue(journal.acknowledge("second"));
        assertTrue(journal.isEmpty());
        assertFalse(journal.acknowledge("second"));
    }

    @Test
    public void reusingAnEventIdWithDifferentDataFailsWithoutChangingTheStoredEvent() throws Exception
    {
        EventJournal journal = new EventJournal(temporary.newFolder().toPath(), "context");
        journal.append("same-id", payload(1));
        expectIo(() -> journal.append("same-id", payload(2)));
        assertEquals(1, journal.size());
        assertEquals(payload(1), journal.readHead(1).get(0).eventJson);
    }

    @Test
    public void eachCompleteConnectionAndAccountContextHasSeparateStorage() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        String[] contexts = {
            "https://one.test|alice|device-1|rs-1",
            "https://two.test|alice|device-1|rs-1",
            "https://one.test|bob|device-1|rs-1",
            "https://one.test|alice|device-2|rs-1",
            "https://one.test|alice|device-1|rs-2"
        };
        for (int index = 0; index < contexts.length; index++)
        {
            new EventJournal(root, contexts[index]).append("same-id", payload(index));
        }
        for (int index = 0; index < contexts.length; index++)
        {
            EventJournal journal = new EventJournal(root, contexts[index]);
            assertEquals(1, journal.size());
            assertEquals(payload(index), journal.readHead(1).get(0).eventJson);
        }
        try (Stream<Path> paths = Files.list(root))
        {
            assertTrue(paths.allMatch(path -> path.getFileName().toString().matches("[0-9a-f]{64}")));
        }
    }

    @Test
    public void interruptedTemporaryWriteIsNeverTreatedAsACommittedEvent() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        EventJournal journal = new EventJournal(root, "context");
        journal.append("first", payload(1));
        Files.writeString(directory(root).resolve(".event-interrupted.tmp"), "{incomplete",
            StandardCharsets.UTF_8);

        EventJournal reopened = new EventJournal(root, "context");
        reopened.append("second", payload(2));
        assertEquals(2, reopened.size());
        assertEquals("first", reopened.readHead(1).get(0).eventId);
        assertTrue(reopened.acknowledge("first"));
        assertEquals("second", reopened.readHead(1).get(0).eventId);
    }

    @Test
    public void corruptHeadStopsReadingAndAcknowledgementWithoutDroppingAnyFiles() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        EventJournal journal = new EventJournal(root, "context");
        journal.append("first", payload(1));
        journal.append("second", payload(2));
        Path first = entries(root).get(0);
        Files.writeString(first, "{broken", StandardCharsets.UTF_8);

        EventJournal reopened = new EventJournal(root, "context");
        expectIo(() -> reopened.readHead(500));
        expectIo(() -> reopened.acknowledge("first"));
        assertEquals(2, reopened.size());
        assertTrue(Files.exists(first));
    }

    @Test
    public void validJsonWithModifiedPayloadStillFailsItsChecksum() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        EventJournal journal = new EventJournal(root, "context");
        journal.append("first", "{\"quantity\":123}");
        Path first = entries(root).get(0);
        String corrupted = Files.readString(first, StandardCharsets.UTF_8).replace("123", "456");
        Files.writeString(first, corrupted, StandardCharsets.UTF_8);
        expectIo(() -> journal.readHead(1));
        expectIo(() -> journal.acknowledge("first"));
        assertEquals(1, journal.size());
    }

    @Test
    public void boundedHeadDoesNotReadOverflowBodiesEarly() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        EventJournal journal = new EventJournal(root, "context");
        for (int index = 0; index < 501; index++)
        {
            journal.append("event-" + index, payload(index));
        }
        Files.writeString(entries(root).get(500), "{broken", StandardCharsets.UTF_8);
        assertEquals(500, journal.readHead(Integer.MAX_VALUE).size());
        assertTrue(journal.acknowledge("event-0"));
        expectIo(() -> journal.readHead(500));
        assertEquals("event-1", journal.readHead(1).get(0).eventId);
        assertEquals(500, journal.size());
    }

    @Test
    public void anotherWriterLockFailsClosedAndCanRecoverAfterRelease() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        EventJournal journal = new EventJournal(root, "context");
        journal.append("first", payload(1));
        try (FileChannel channel = FileChannel.open(directory(root).resolve(".lock"), StandardOpenOption.WRITE);
             FileLock ignored = channel.lock())
        {
            expectIo(() -> journal.append("second", payload(2)));
            expectIo(() -> journal.acknowledge("first"));
        }
        journal.append("second", payload(2));
        assertEquals(2, journal.size());
        assertEquals("first", journal.readHead(1).get(0).eventId);
    }

    @Test
    public void concurrentCallsOnOneJournalNeverLoseAnAppend() throws Exception
    {
        EventJournal journal = new EventJournal(temporary.newFolder().toPath(), "context");
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> writers = new ArrayList<>();
        for (int writer = 0; writer < 2; writer++)
        {
            final int writerId = writer;
            Thread thread = new Thread(() ->
            {
                try
                {
                    start.await();
                    for (int index = 0; index < 15; index++)
                    {
                        journal.append(writerId + "-" + index, payload(index));
                    }
                }
                catch (Throwable exception)
                {
                    failure.compareAndSet(null, exception);
                }
            });
            writers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread writer : writers)
        {
            writer.join(10_000);
            assertFalse("Writer did not finish", writer.isAlive());
        }
        if (failure.get() != null)
        {
            throw new AssertionError(failure.get());
        }
        assertEquals(30, journal.size());
        assertEquals(30, journal.readHead(500).stream().map(entry -> entry.eventId).distinct().count());
    }

    @Test
    public void migrationMarkerPreventsResurrectionAfterPartialImportAndAcknowledgement() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        EventJournal partial = new EventJournal(root, "context");
        partial.append("first", payload(1));
        assertFalse(partial.legacyImported());
        List<EventJournal.Entry> legacy = List.of(
            new EventJournal.Entry("first", payload(1)), new EventJournal.Entry("second", payload(2)));
        EventJournal resumed = new EventJournal(root, "context");
        resumed.importLegacy(legacy, "{\"pendingCash\":123}");
        assertTrue(resumed.legacyImported());
        assertEquals(2, resumed.size());
        assertTrue(resumed.acknowledge("first"));
        resumed.writeState("{\"pendingCash\":456}");

        EventJournal afterAck = new EventJournal(root, "context");
        afterAck.importLegacy(legacy, "{\"pendingCash\":123}");
        assertEquals(1, afterAck.size());
        assertEquals("second", afterAck.readHead(1).get(0).eventId);
        assertEquals("{\"pendingCash\":456}", afterAck.readState());
    }

    @Test
    public void legacyConnectionIsBoundOnceBeforeAnyAccountIsLoaded() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        assertEquals("original-connection", EventJournal.claimLegacyConnection(root, "original-connection"));
        assertEquals("original-connection", EventJournal.claimLegacyConnection(root, "new-owner"));
    }

    private static String payload(int value)
    {
        return "{\"event_sequence\":" + value + ",\"item_name\":\"Rune \\\"test\\\" \\n item\"}";
    }

    private static Path directory(Path root) throws IOException
    {
        try (Stream<Path> paths = Files.list(root))
        {
            return paths.filter(Files::isDirectory).findFirst().orElseThrow(IOException::new);
        }
    }

    private static List<Path> entries(Path root) throws IOException
    {
        try (Stream<Path> paths = Files.list(directory(root)))
        {
            return paths.filter(path -> path.toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .collect(Collectors.toList());
        }
    }

    private static void expectIo(CheckedAction action) throws Exception
    {
        try
        {
            action.run();
            fail("Expected an IOException without a queue mutation");
        }
        catch (IOException expected)
        {
            // Caller must surface this and preserve its durable queue.
        }
    }

    private interface CheckedAction
    {
        void run() throws Exception;
    }
}
