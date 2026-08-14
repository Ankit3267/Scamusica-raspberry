package com.musicplayer.scamusica.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class VolumeSettings {

    @SerializedName("volume_source")
    private String volumeSource;

    @SerializedName("music_volume")
    private Integer musicVolume;

    @SerializedName("ad_volume")
    private Integer adVolume;

    private List<VolumeSchedule> schedules;

    public VolumeSettings() {
    }

    public String getVolumeSource() {
        return volumeSource;
    }

    public void setVolumeSource(String volumeSource) {
        this.volumeSource = volumeSource;
    }

    public Integer getMusicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(Integer musicVolume) {
        this.musicVolume = musicVolume;
    }

    public Integer getAdVolume() {
        return adVolume;
    }

    public void setAdVolume(Integer adVolume) {
        this.adVolume = adVolume;
    }

    public List<VolumeSchedule> getSchedules() {
        return schedules;
    }

    public void setSchedules(List<VolumeSchedule> schedules) {
        this.schedules = schedules;
    }

    @Override
    public String toString() {
        return "VolumeSettings{" +
                "volumeSource='" + volumeSource + '\'' +
                ", musicVolume=" + musicVolume +
                ", adVolume=" + adVolume +
                ", schedules=" + schedules +
                '}';
    }
}
