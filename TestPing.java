import java.net.HttpURLConnection;
import java.net.URL;
import java.io.IOException;

public class TestPing {
    public static void main(String[] args) {
        HttpURLConnection connection = null;
        try {
            System.out.println("Trying to connect to https://api.scamusica.com/");
            connection = (HttpURLConnection) new URL("https://api.scamusica.com/").openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Connection", "close");
            
            int responseCode = connection.getResponseCode();
            System.out.println("Response code: " + responseCode);
        } catch (IOException e) {
            System.err.println("Exception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {}
            }
        }
    }
}
