package com.osrsflipper.sync;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import net.runelite.client.RuneLite;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

public class TestRuntimeIsolationTest
{
    @Test
    public void runeLitePathsAndTemporaryFilesStayInsideTheFreshGradleTestDirectory() throws Exception
    {
        String isolatedHome = System.getProperty("osrsflipper.test.home");
        assertNotNull("Run tests with Gradle so RuneLite cannot use the real user profile", isolatedHome);
        Path home = Paths.get(isolatedHome).toAbsolutePath().normalize();
        assertEquals(home, Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize());
        assertEquals("test-runtime", home.getParent().getParent().getFileName().toString());
        assertTrue(Files.isDirectory(home));
        assertEquals(home.resolve(".runelite"), RuneLite.RUNELITE_DIR.toPath());

        int checkedDirectories = 0;
        for (Field field : RuneLite.class.getDeclaredFields())
        {
            if (field.getType() != File.class || !Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            File location = (File) field.get(null);
            assertTrue(field.getName() + " escapes the isolated test profile",
                location.toPath().toAbsolutePath().normalize().startsWith(home));
            checkedDirectories++;
        }
        assertTrue("RuneLite storage, session and log paths must be checked", checkedDirectories >= 5);
        Path temporary = Files.createTempFile("isolation-check-", ".tmp");
        assertTrue("temporary fixtures must also stay within this test execution",
            temporary.toAbsolutePath().normalize().startsWith(home.getParent().resolve("tmp")));
    }

    @Test
    public void testLoggerHasNoFileAppenderAndDoesNotCreateRuneLiteClientLog()
    {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertNotNull("the test logging configuration must be available",
            getClass().getClassLoader().getResource("logback-test.xml"));
        assertNotNull(context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("TEST_CONSOLE"));
        for (Logger logger : context.getLoggerList())
        {
            Iterator<Appender<ILoggingEvent>> appenders = logger.iteratorForAppenders();
            while (appenders.hasNext())
            {
                assertFalse("tests must never install a file logger", appenders.next() instanceof FileAppender);
            }
        }
        LoggerFactory.getLogger(TestRuntimeIsolationTest.class).info("Test logging stays in Gradle output");
        assertFalse("RuneLite's production logger must not create a client.log during tests",
            Files.exists(RuneLite.RUNELITE_DIR.toPath().resolve("logs/client.log")));
    }
}
