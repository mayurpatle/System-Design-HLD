package org.example.behavioralPattern.Observer.NotificationFeature.observer;

public class PushNotificationObserver implements StockNotificationObserver{

    private final String userId  ;
    private final String deviceToken  ;

    public PushNotificationObserver(String userId, String deviceToken) {
        this.userId = userId;
        this.deviceToken = deviceToken;
    }

    private void sendPushNotification() {
        System.out.println("push notification send");

    }

    @Override
    public void update() {
        sendPushNotification();
        
    }

    @Override
    public String getNotificationMethod() {
        return "Push Notification";

    }

    @Override
    public String getUserId() {
        return userId;
    }
}
