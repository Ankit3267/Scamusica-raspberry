package com.musicplayer.scamusica.controller;

import com.musicplayer.scamusica.model.VolumeSchedule;
import com.musicplayer.scamusica.model.VolumeSettings;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

public class VolumeSchedulerTest {

    @Test
    public void testScheduleMatching() {
        VolumeSettings settings = new VolumeSettings();
        
        VolumeSchedule s1 = new VolumeSchedule();
        s1.setId(30);
        s1.setStartTime("17:06");
        s1.setEndTime("17:08");
        s1.setMusicVolume(10);
        s1.setAdVolume(100);
        
        VolumeSchedule s2 = new VolumeSchedule();
        s2.setId(31);
        s2.setStartTime("17:10");
        s2.setEndTime("17:12");
        s2.setMusicVolume(85);
        s2.setAdVolume(10);
        
        settings.setSchedules(Arrays.asList(s1, s2));
        
        // Match first schedule
        LocalTime now = LocalTime.parse("17:07");
        VolumeSchedule active = getActiveSchedule(settings, now);
        assertNotNull(active);
        assertEquals(30, active.getId());
        
        // Match edge of first schedule
        now = LocalTime.parse("17:06");
        active = getActiveSchedule(settings, now);
        assertNotNull(active);
        assertEquals(30, active.getId());
        
        // Outside of schedule
        now = LocalTime.parse("17:09");
        active = getActiveSchedule(settings, now);
        assertNull(active);
        
        // Match second schedule
        now = LocalTime.parse("17:11");
        active = getActiveSchedule(settings, now);
        assertNotNull(active);
        assertEquals(31, active.getId());
    }
    
    private VolumeSchedule getActiveSchedule(VolumeSettings settings, LocalTime now) {
        if (settings == null || settings.getSchedules() == null) return null;
        for (VolumeSchedule schedule : settings.getSchedules()) {
            try {
                LocalTime start = LocalTime.parse(schedule.getStartTime());
                LocalTime end = LocalTime.parse(schedule.getEndTime());
                if (!now.isBefore(start) && now.isBefore(end)) {
                    return schedule;
                }
            } catch (Exception ex) {
                // Ignore
            }
        }
        return null;
    }
}
