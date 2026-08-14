import com.google.gson.Gson;
import com.musicplayer.scamusica.model.VolumeSettings;

public class TestParse {
    public static void main(String[] args) {
        String json = "{ \"volume_source\": \"player\", \"music_volume\": 30, \"ad_volume\": 0, \"schedules\": [ { \"id\": 30, \"start_time\": \"17:06\", \"end_time\": \"17:08\", \"music_volume\": 10, \"ad_volume\": 100 } ] }";
        Gson gson = new Gson();
        VolumeSettings settings = gson.fromJson(json, VolumeSettings.class);
        System.out.println("Parsed: " + settings);
    }
}
