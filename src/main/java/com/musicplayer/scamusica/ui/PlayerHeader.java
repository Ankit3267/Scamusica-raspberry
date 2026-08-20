package com.musicplayer.scamusica.ui;

import com.musicplayer.scamusica.manager.LanguageManager;
import com.musicplayer.scamusica.manager.SessionManager;
import com.musicplayer.scamusica.service.NetworkMonitor;
import com.musicplayer.scamusica.util.Utility;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

public class PlayerHeader {
    public HBox createLeftMeta() {
        HBox leftMeta = new HBox(14);
        leftMeta.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label versionLbl = new Label();
        versionLbl.textProperty().bind(
                Bindings.concat(
                        LanguageManager.createStringBinding("label.version")," 11"
                )
        );
        versionLbl.getStyleClass().add("meta-text");
        Label idLbl = new Label("ID: "+ (SessionManager.isUserLoggedIn()? SessionManager.getUserId():null));
        idLbl.getStyleClass().add("meta-text");
        Button supportBtn = new Button();
        supportBtn.textProperty().bind(LanguageManager.createStringBinding("button.support"));
        supportBtn.getStyleClass().add("support-pill");
        supportBtn.setCursor(javafx.scene.Cursor.HAND);
        
        supportBtn.setOnAction(event -> {
            new Thread(() -> {
                String licenseCode = SessionManager.getLicenseCode();
                String url = Utility.SUPPORT_URL.get() + (licenseCode != null && !licenseCode.isEmpty() ? "/" + licenseCode : "");
                try {
                    if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                        java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                    } else {
                        Runtime.getRuntime().exec(new String[]{"xdg-open", url});
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        });
        
        leftMeta.getChildren().addAll(versionLbl, idLbl, supportBtn);
        return leftMeta;
    }

    public ImageView createLogoView(Class<?> clazz) {
        ImageView logoView = new ImageView();
        try {
            Image logo = clazz.getResource("/images/logo.png") == null ? null :
                    new Image(clazz.getResource("/images/logo.png").toExternalForm());
            if (logo != null) logoView.setImage(logo);
        } catch (Exception ignored) {}
        logoView.setPreserveRatio(true);
        logoView.setFitHeight(50);
        return logoView;
    }

    public HBox createRightMeta() {
        javafx.scene.shape.Circle statusCircle =
                new javafx.scene.shape.Circle(8, javafx.scene.paint.Color.web("#ef4444"));

        Label onlineLbl = new Label();
        onlineLbl.setStyle("-fx-color: #000000; -fx-font-size: 14px;");

        NetworkMonitor monitor = NetworkMonitor.getInstance();

        Runnable updateUI = () -> {
            boolean isOnline = monitor.isOnline();
            statusCircle.setFill(
                    isOnline
                            ? javafx.scene.paint.Color.web("#248924")
                            : javafx.scene.paint.Color.web("#ef4444")
            );
            String key = isOnline ? "label.online" : "label.offline";
            onlineLbl.textProperty().bind(LanguageManager.createStringBinding(key));
        };

        monitor.onlineProperty().addListener((obs, old, now) ->
                javafx.application.Platform.runLater(updateUI)
        );

        javafx.application.Platform.runLater(updateUI);

        HBox rightMeta = new HBox(8, statusCircle, onlineLbl);
        rightMeta.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        return rightMeta;
    }

    public BorderPane createHeader(HBox left, ImageView center, HBox right) {
        BorderPane header = new BorderPane();
        header.getStyleClass().add("app-header");
        header.setLeft(left);
        header.setCenter(center);
        header.setRight(right);
        header.setPadding(new javafx.geometry.Insets(20, 40, 20, 40));
        return header;
    }

    public Label createPlayerTitle() {
        Label titleCentered = new Label("Escuchando Top 40");
        titleCentered.getStyleClass().add("player-title");
        titleCentered.setAlignment(javafx.geometry.Pos.CENTER);
        return titleCentered;
    }

    public VBox createCenterContainer(Label titleCentered) {
        VBox centerContainer = new VBox();
        centerContainer.setAlignment(javafx.geometry.Pos.CENTER);
        centerContainer.getChildren().add(titleCentered);
        return centerContainer;
    }

    public void loadFonts(Class<?> clazz) {
        try {
            Font.loadFont(clazz.getResourceAsStream("/fonts/Poppins-Regular.ttf"), 12);
            Font.loadFont(clazz.getResourceAsStream("/fonts/Poppins-Bold.ttf"), 12);

            // Lazy load large CJK/Arabic fonts in background
            new Thread(() -> {
                try {
                    Font.loadFont(clazz.getResourceAsStream("/fonts/NotoSans-Regular.ttf"), 12);
                    Font.loadFont(clazz.getResourceAsStream("/fonts/NotoSansArabic-Regular.ttf"), 12);
                    Font.loadFont(clazz.getResourceAsStream("/fonts/NotoSansDevanagari-Regular.ttf"), 12);
                    Font.loadFont(clazz.getResourceAsStream("/fonts/NotoSansJP-Regular.ttf"), 12);
                    Font.loadFont(clazz.getResourceAsStream("/fonts/NotoSansSC-Regular.ttf"), 12);
                    Font.loadFont(clazz.getResourceAsStream("/fonts/NotoSansTC-Regular.ttf"), 12);
                } catch (Exception ignored) {}
            }, "FontLoader-Thread").start();

        } catch (Exception ex) {}
    }
}
