package com.osrsflipper.sync;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WorkerRefreshCadenceTest
{
    @Test
    public void missingAdviceUsesOneMinuteAndPresentAdviceStaysBetweenFifteenAndSixtySeconds()
    {
        Gson gson = new Gson();
        assertEquals(60, gson.fromJson("{}", WorkerOverviewResponse.class).refreshAfterSeconds());
        assertEquals(60, gson.fromJson("{\"refresh_after_seconds\":null}", WorkerOverviewResponse.class).refreshAfterSeconds());
        int[][] cases = {{Integer.MIN_VALUE, 15}, {-1, 15}, {0, 15}, {14, 15},
            {15, 15}, {30, 30}, {60, 60}, {61, 60}, {Integer.MAX_VALUE, 60}};
        for (int[] entry : cases)
        {
            WorkerOverviewResponse response = gson.fromJson(
                "{\"refresh_after_seconds\":" + entry[0] + "}", WorkerOverviewResponse.class);
            assertEquals("Advice " + entry[0], entry[1], response.refreshAfterSeconds());
        }
    }
}
