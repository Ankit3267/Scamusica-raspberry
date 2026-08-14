package com.musicplayer.scamusica.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class VolumeSettingsTest {

    @Test
    public void testParseVolumeSettings() {
        String json = "{ \"volume_source\": \"player\", \"music_volume\": 30, \"ad_volume\": 0, \"schedules\": [ { \"id\": 30, \"start_time\": \"17:06\", \"end_time\": \"17:08\", \"music_volume\": 10, \"ad_volume\": 100 } ] }";
        Gson gson = new Gson();
        VolumeSettings settings = gson.fromJson(json, VolumeSettings.class);
        
        assertNotNull(settings);
        assertEquals("player", settings.getVolumeSource());
        assertEquals(30, settings.getMusicVolume());
        assertEquals(0, settings.getAdVolume());
        assertNotNull(settings.getSchedules());
        assertEquals(1, settings.getSchedules().size());
        
        VolumeSchedule schedule = settings.getSchedules().get(0);
        assertEquals(30, schedule.getId());
        assertEquals("17:06", schedule.getStartTime());
        assertEquals("17:08", schedule.getEndTime());
        assertEquals(10, schedule.getMusicVolume());
        assertEquals(100, schedule.getAdVolume());
    }
}
