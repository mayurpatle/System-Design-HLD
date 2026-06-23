package org.example.behavioralPattern.Observer.NotificationFeature.observer;

public class EmailNotificationObserver implements StockNotificationObserver{

    private final String userId ;
    private final String emailAddress ;

    public EmailNotificationObserver(String userId, String emailAddress) {
        this.userId = userId;
        this.emailAddress = emailAddress;
    }


    @Override
    public void update() {
        sendEmail();
    }

    private void sendEmail() {
        System.out.println("Email send to  " + emailAddress +  "product  is back in the stock   hurry");

    }
    @Override
    public String getNotificationMethod() {
        return "Email";
    }

    @Override
    public String getUserId() {
        return userId   ;

    }
}
