package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkerResponseValidationTest
{
    private static final String DEVICE = "fixture-device";
    private static final String OWNER = "owner@example.test";
    private static final String CASH = "{\"success\":true,\"cash\":{\"available\":1000," +
        "\"reserved\":20,\"available_plus_reserved\":1020,\"updated_at\":123}}";
    private static final String STATUS = "{\"success\":true,\"server_time\":123," +
        "\"device\":{\"active\":true,\"status\":\"active\",\"device_id\":\"fixture-device\"}," +
        "\"owner\":{\"email\":\"owner@example.test\"}}";
    private static final String HEARTBEAT = "{\"success\":true,\"heartbeat_at\":123," +
        "\"status\":\"active\",\"device_id\":\"fixture-device\",\"owner_email\":\"owner@example.test\"}";

    @Test
    public void acceptsCompleteExistingWorkerContractsAndCanonicalOwnerMatching()
    {
        assertTrue(WorkerResponseValidation.cash(CASH));
        assertTrue(WorkerResponseValidation.status(STATUS, DEVICE, OWNER));
        assertTrue(WorkerResponseValidation.heartbeat(HEARTBEAT, DEVICE, OWNER));
        assertTrue(WorkerResponseValidation.status(STATUS, DEVICE, " OWNER@EXAMPLE.TEST "));
        assertTrue(WorkerResponseValidation.heartbeat(HEARTBEAT, DEVICE, " OWNER@EXAMPLE.TEST "));
    }

    @Test
    public void emptyHtmlTruncatedAndMissingAcknowledgementsCannotCountAsSuccess()
    {
        for (String body : new String[] {null, "", "<html>Worker error</html>", "{\"success\":true",
            "[]", "null", "{}", "{\"success\":true}", "{\"success\":false}"})
        {
            assertFalse(String.valueOf(body), WorkerResponseValidation.cash(body));
            assertFalse(String.valueOf(body), WorkerResponseValidation.status(body, DEVICE, OWNER));
            assertFalse(String.valueOf(body), WorkerResponseValidation.heartbeat(body, DEVICE, OWNER));
        }
        for (String replacement : new String[] {"false", "\"true\"", "1", "null"})
        {
            assertFalse(WorkerResponseValidation.cash(CASH.replace("\"success\":true", "\"success\":" + replacement)));
            assertFalse(WorkerResponseValidation.status(STATUS.replace("\"success\":true", "\"success\":" + replacement), DEVICE, OWNER));
            assertFalse(WorkerResponseValidation.heartbeat(HEARTBEAT.replace("\"success\":true", "\"success\":" + replacement), DEVICE, OWNER));
        }
    }

    @Test
    public void cashRequiresWholeSafeNumbersAndAConsistentReservedTotal()
    {
        for (String replacement : new String[] {"null", "\"1000\"", "1000.5", "true", "{}", "9007199254740992"})
        {
            assertFalse(replacement, WorkerResponseValidation.cash(CASH.replace("\"available\":1000", "\"available\":" + replacement)));
        }
        assertFalse(WorkerResponseValidation.cash(CASH.replace("\"available_plus_reserved\":1020", "\"available_plus_reserved\":1000")));
        assertFalse(WorkerResponseValidation.cash(CASH.replace("\"reserved\":20", "\"reserved\":-20")));
        assertFalse(WorkerResponseValidation.cash(CASH.replace("\"updated_at\":123", "\"updated_at\":-1")));
        assertFalse(WorkerResponseValidation.cash(CASH.replace(",\"updated_at\":123", "")));
        assertTrue(WorkerResponseValidation.cash(CASH.replace("1000", "0").replace("1020", "20")));
    }

    @Test
    public void statusMustNameTheSameActiveDeviceAndOwnerWithAServerTimestamp()
    {
        assertFalse(WorkerResponseValidation.status(STATUS, "other-device", OWNER));
        assertFalse(WorkerResponseValidation.status(STATUS, DEVICE, "other@example.test"));
        assertFalse(WorkerResponseValidation.status(STATUS, DEVICE, null));
        assertFalse(WorkerResponseValidation.status(STATUS.replace("\"active\":true", "\"active\":false"), DEVICE, OWNER));
        assertFalse(WorkerResponseValidation.status(STATUS.replace("\"active\":true", "\"active\":\"true\""), DEVICE, OWNER));
        assertFalse(WorkerResponseValidation.status(STATUS.replace("\"status\":\"active\"", "\"status\":\"revoked\""), DEVICE, OWNER));
        for (String timestamp : new String[] {"0", "-1", "null", "\"123\"", "123.5"})
        {
            assertFalse(timestamp, WorkerResponseValidation.status(STATUS.replace("\"server_time\":123", "\"server_time\":" + timestamp), DEVICE, OWNER));
        }
        assertFalse(WorkerResponseValidation.status(STATUS.replace("\"device_id\":\"fixture-device\"", "\"device_id\":123"), DEVICE, OWNER));
        assertFalse(WorkerResponseValidation.status(STATUS.replace("\"email\":\"owner@example.test\"", "\"email\":false"), DEVICE, OWNER));
    }

    @Test
    public void heartbeatMustConfirmTheCurrentIdentityAndAnActualHeartbeatTime()
    {
        assertFalse(WorkerResponseValidation.heartbeat(HEARTBEAT, "other-device", OWNER));
        assertFalse(WorkerResponseValidation.heartbeat(HEARTBEAT, DEVICE, "other@example.test"));
        assertFalse(WorkerResponseValidation.heartbeat(HEARTBEAT, DEVICE, null));
        assertFalse(WorkerResponseValidation.heartbeat(HEARTBEAT.replace("\"status\":\"active\"", "\"status\":\"revoked\""), DEVICE, OWNER));
        for (String timestamp : new String[] {"0", "-1", "null", "\"123\"", "123.5"})
        {
            assertFalse(timestamp, WorkerResponseValidation.heartbeat(HEARTBEAT.replace("\"heartbeat_at\":123", "\"heartbeat_at\":" + timestamp), DEVICE, OWNER));
        }
        assertFalse(WorkerResponseValidation.heartbeat(HEARTBEAT.replace("\"device_id\":\"fixture-device\"", "\"device_id\":123"), DEVICE, OWNER));
        assertFalse(WorkerResponseValidation.heartbeat(HEARTBEAT.replace("\"owner_email\":\"owner@example.test\"", "\"owner_email\":false"), DEVICE, OWNER));
    }
}
