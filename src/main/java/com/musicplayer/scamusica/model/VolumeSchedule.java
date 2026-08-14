package com.musicplayer.scamusica.model;

import com.google.gson.annotations.SerializedName;

public class VolumeSchedule {

    private Integer id;

    @SerializedName("start_time")
    private String startTime;

    @SerializedName("end_time")
    private String endTime;

    @SerializedName("music_volume")
    private Integer musicVolume;

    @SerializedName("ad_volume")
    private Integer adVolume;

    public VolumeSchedule() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
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

    @Override
    public String toString() {
        return "VolumeSchedule{" +
                "id=" + id +
                ", startTime='" + startTime + '\'' +
                ", endTime='" + endTime + '\'' +
                ", musicVolume=" + musicVolume +
                ", adVolume=" + adVolume +
                '}';
    }
}
